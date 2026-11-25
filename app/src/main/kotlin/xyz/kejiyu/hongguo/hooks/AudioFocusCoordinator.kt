package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook

/**
 * Audio interruption wins over every module-owned keep-playing policy.
 * Observe real media focus requests, never replace native listeners.
 * A single supplementary focus request is made only while a confirmed
 * player is foreground and no native media focus request was observed.
 */
internal object AudioFocusCoordinator {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    @Volatile private var pauseFocus = true
    @Volatile private var pauseCall = true
    @Volatile private var autoResume = false
    @Volatile private var focusLost = false
    @Volatile private var callActive = false
    @Volatile private var manualHold = false
    @Volatile private var manager: AudioManager? = null
    private var modeRegistration: Any? = null
    @Volatile private var own: AudioFocusRequest? = null
    @Volatile private var nativeRequest = WeakReference<Any>(null)
    @Volatile private var nativeListener = WeakReference<Any>(null)
    @Volatile private var nativeLegacyGain = AudioManager.AUDIOFOCUS_GAIN
    private val builderListeners = WeakHashMap<Any, WeakReference<Any>>()
    private val requestListeners = WeakHashMap<Any, WeakReference<Any>>()
    private val hooked = HashSet<Method>()
    private val internalRequest = ThreadLocal<Boolean>()
    private var lastRequestEngine = WeakReference<Any>(null)
    private var lastRequestVideo: String? = null
    private data class ResumeTicket(val engine: WeakReference<Any>, val video: String?, val epoch: Long)
    private var ticket: ResumeTicket? = null

    fun configure(ctx: Context, active: Boolean, focus: Boolean, calls: Boolean, resume: Boolean) {
        val primary = runCatching { Application.getProcessName() == ctx.packageName }.getOrDefault(false)
        val task = Runnable {
            enabled = active && primary && (focus || calls)
            pauseFocus = focus
            pauseCall = calls
            autoResume = resume
            if (!enabled) {
                releaseOwn()
                unregisterMode()
                focusLost = false
                callActive = false
                manualHold = false
                ticket = null
                lastRequestEngine.clear()
                return@Runnable
            }
            manager = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (!pauseFocus) { focusLost = false; releaseOwn() }
            if (!pauseCall) { callActive = false; unregisterMode() }
            else {
                registerMode()
                manager?.let { onMode(runCatching { it.mode }.getOrDefault(AudioManager.MODE_NORMAL)) }
            }
            if (!autoResume) ticket = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    fun interrupted(): Boolean = enabled && (focusLost || callActive || manualHold)
    fun blocksPauseProtection(): Boolean {
        if (interrupted()) return true
        // Native focus callbacks may be observed before a framework request
        // could be associated with a listener; never swallow such a pause.
        return Thread.currentThread().stackTrace.any {
            it.methodName.equals("onAudioFocusChange", true) ||
                it.methodName.contains("audioFocusLoss", true)
        }
    }
    private fun inCall(mode: Int) = mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    private fun onMode(mode: Int) {
        val next = enabled && pauseCall && inCall(mode)
        if (next == callActive) return
        callActive = next
        if (next) interrupt("call-mode:$mode", true)
        else gained("call-mode-ended")
    }
    private fun registerMode() {
        if (Build.VERSION.SDK_INT < 31 || modeRegistration != null) return
        val audio = manager ?: return
        try {
            val listener = AudioManager.OnModeChangedListener { mode -> onMode(mode) }
            audio.addOnModeChangedListener({ command -> main.post(command) }, listener)
            modeRegistration = listener
            LogUtil.info("音频协调: 系统通话模式监听已注册")
        } catch (e: Throwable) { LogUtil.warn("音频协调: 通话模式监听不可用 " + e) }
    }
    private fun unregisterMode() {
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = modeRegistration as? AudioManager.OnModeChangedListener
            if (listener != null) runCatching { manager?.removeOnModeChangedListener(listener) }
        }
        modeRegistration = null
    }
    private fun releaseOwn() {
        val previous = own ?: return
        own = null
        internalRequest.set(true)
        try { manager?.abandonAudioFocusRequest(previous) }
        catch (e: Throwable) { LogUtil.warn("音频协调: 释放补充焦点失败 " + e) }
        finally { internalRequest.remove() }
    }
    private fun acquire(): Boolean {
        if (!enabled || !pauseFocus || callActive || Build.VERSION.SDK_INT < 26) return false
        val audio = manager ?: return false
        if (own != null && !focusLost) return true
        releaseOwn()
        return try {
            lateinit var request: AudioFocusRequest
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (own === request) onFocus(change, "supplemental")
            }
            request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener, main)
                .build()
            own = request
            internalRequest.set(true)
            val result = try { audio.requestAudioFocus(request) } finally { internalRequest.remove() }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                own = null
                LogUtil.warn("音频协调: 补充焦点未获批准 result=$result")
                false
            } else {
                focusLost = false
                LogUtil.info("音频协调: 补充媒体焦点已取得")
                true
            }
        } catch (e: Throwable) {
            own = null
            LogUtil.warn("音频协调: 补充焦点不可用 " + e)
            false
        }
    }

    private fun onFocus(change: Int, source: String) {
        if (!enabled || !pauseFocus) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Set immediately, before the native focus listener calls pause.
                focusLost = true
                interrupt("focus:$source:$change", change != AudioManager.AUDIOFOCUS_LOSS)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLost = false
                main.post { gained("focus-gain:$source") }
            }
        }
    }
    private fun interrupt(reason: String, temporary: Boolean) {
        manualHold = true
        val engine = SystemMediaBridge.audioFocusEngine()
        val playing = NativePlaybackSession.playing(engine)
        val epoch = NativePlaybackSession.generation()
        val id = NativePlaybackSession.videoId(engine)
        main.post {
            if (!enabled) return@post
            if (!temporary) ticket = null
            else if (autoResume && playing && engine != null && ticket == null)
                ticket = ResumeTicket(WeakReference(engine), id, epoch)
            LogUtil.info("音频协调: 中断 $reason playing=$playing autoResume=$autoResume")
            LogUtil.incr("audioFocusInterruption")
            InAppMiniPlayer.cancelAudioRecovery()
            InAppPlaybackContinuity.cancelAudioRecovery()
            SystemMediaBridge.onAudioInterruption(reason)
            if (!temporary) releaseOwn()
        }
    }
    private fun gained(reason: String) {
        if (!enabled || focusLost || callActive) return
        val resume = ticket
        ticket = null
        if (!autoResume || resume == null) {
            LogUtil.info("音频协调: $reason 等待用户主动播放")
            return
        }
        val target = resume.engine.get()
        if (target == null || SystemMediaBridge.audioFocusEngine() !== target ||
            resume.epoch != NativePlaybackSession.generation() ||
            resume.video != NativePlaybackSession.videoId(target)) return
        manualHold = false
        SystemMediaBridge.resumeAfterAudioFocus(target)
    }
    /** Called only for explicit media buttons; no automatic reacquisition. */
    fun userPlayAllowed(): Boolean {
        if (!enabled) return true
        if (pauseCall && manager?.let { runCatching { inCall(it.mode) }.getOrDefault(false) } == true)
            return false
        if (callActive) return false
        ticket = null
        if (pauseFocus) {
            val existing = nativeRequest.get()
            if (focusLost && existing != null) {
                // An explicit user action can re-request the original focus
                // client. Do not make a competing client steal its focus.
                val audio = manager ?: return false
                val result = try {
                    when (existing) {
                        is AudioFocusRequest -> audio.requestAudioFocus(existing)
                        is AudioManager.OnAudioFocusChangeListener -> {
                            @Suppress("DEPRECATION")
                            audio.requestAudioFocus(existing, AudioManager.STREAM_MUSIC, nativeLegacyGain)
                        }
                        else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
                    }
                } catch (_: Throwable) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
                focusLost = false
            } else if (existing == null && (own == null || focusLost) && !acquire()) {
                return false
            }
        }
        manualHold = false
        return true
    }
    fun userPaused() {
        ticket = null
        manualHold = true
        lastRequestEngine.clear()
        lastRequestVideo = null
        releaseOwn()
    }
    fun onPlaying(engine: Any, activity: Activity?) {
        if (!enabled) return
        if (pauseCall) manager?.let { audio ->
            onMode(runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL))
        }
        if (interrupted()) {
            // A confirmed new foreground play after gain is allowed. A native
            // background auto-resume after interruption is not module recovery.
            if (!focusLost && !callActive && activity?.hasWindowFocus() == true) manualHold = false
            else {
                main.post {
                    if (interrupted() && SystemMediaBridge.audioFocusEngine() === engine &&
                        NativePlaybackSession.playing(engine))
                        SystemMediaBridge.onAudioInterruption("native-play-during-interruption")
                }
                return
            }
        }
        if (!pauseFocus || nativeRequest.get() != null || own != null ||
            activity?.hasWindowFocus() != true || !NativePlaybackSession.playing(engine)) return
        val id = NativePlaybackSession.videoId(engine)
        if (lastRequestEngine.get() === engine && lastRequestVideo == id) return
        lastRequestEngine = WeakReference(engine)
        lastRequestVideo = id
        acquire()
    }
    fun onEngineEnded(engine: Any, action: String) {
        if (!enabled) return
        if (action == "stop" || action.startsWith("release")) {
            if (ticket?.engine?.get() === engine) ticket = null
            if (lastRequestEngine.get() === engine) lastRequestEngine.clear()
            releaseOwn()
        } else if (action == "pause") {
            // Retain focus briefly for the existing background handoff,
            // but abandon it for a real user/native pause with no recovery.
            main.postDelayed({
                if (!interrupted() && SystemMediaBridge.audioFocusEngine() === engine &&
                    !NativePlaybackSession.playing(engine)) {
                    releaseOwn()
                    lastRequestEngine.clear()
                }
            }, 2500L)
        }
    }
    fun stop() {
        releaseOwn()
        ticket = null
        lastRequestEngine.clear()
    }

    private fun listenerFor(request: Any): Any? = synchronized(requestListeners) {
        requestListeners[request]?.get()
    }
    private fun observeListener(module: MainHook, listener: Any?) {
        if (listener !is AudioManager.OnAudioFocusChangeListener) return
        try {
            val method = listener.javaClass.getMethod("onAudioFocusChange", Integer.TYPE)
            if (!HookBindingAudit.instanceVoid(method, "int")) return
            synchronized(hooked) {
                if (method in hooked) return
                module.hook(method).setId("xhAudioFocusListener_" + method.declaringClass.name.hashCode())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (nativeListener.get() === chain.thisObject) {
                            val change = chain.getArg(0) as? Int
                            if (change != null) onFocus(change, "native")
                        }
                        chain.proceed()
                    })
                hooked.add(method)
            }
        } catch (e: Throwable) { LogUtil.warn("音频协调: 原生焦点监听无法绑定 " + e) }
    }
    /** Public Android request/builder hooks; do not reflect hidden Android APIs. */
    fun install(module: MainHook) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val builder = AudioFocusRequest.Builder::class.java
            for ((i, method) in builder.declaredMethods.filter {
                it.name == "setOnAudioFocusChangeListener" && it.parameterTypes.firstOrNull() ==
                    AudioManager.OnAudioFocusChangeListener::class.java
            }.withIndex()) {
                module.hook(method).setId("xhAudioFocusBuilder_$i")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        val owner = chain.thisObject
                        val listener = chain.getArg(0)
                        if (owner != null && listener != null) synchronized(requestListeners) {
                            builderListeners[owner] = WeakReference(listener)
                        }
                        result
                    })
            }
            module.hook(builder.getDeclaredMethod("build")).setId("xhAudioFocusBuild")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    val owner = chain.thisObject
                    if (owner != null && result != null) synchronized(requestListeners) {
                        builderListeners[owner]?.let { requestListeners[result] = it }
                    }
                    result
                })
            val audio = AudioManager::class.java
            module.hook(audio.getDeclaredMethod("requestAudioFocus", AudioFocusRequest::class.java))
                .setId("xhNativeAudioFocusRequest")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val request = chain.getArg(0) as? AudioFocusRequest
                    if (internalRequest.get() == true || request == null ||
                        request.audioAttributes.usage != AudioAttributes.USAGE_MEDIA) return@Hooker chain.proceed()
                    val listener = listenerFor(request)
                    observeListener(module, listener)
                    // Do not leave competing focus clients inside the same app.
                    if (own != null) releaseOwn()
                    val result = chain.proceed()
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        nativeRequest = WeakReference(request)
                        nativeListener = WeakReference(listener)
                        // A new granted request is evidence of real focus gain.
                        // Keep manualHold; a background request alone does not
                        // authorize automatic playback after an interruption.
                        focusLost = false
                        LogUtil.info("音频协调: 原生媒体焦点已确认 listener=" + (listener != null))
                    }
                    result
                })
            module.hook(audio.getDeclaredMethod("requestAudioFocus",
                AudioManager.OnAudioFocusChangeListener::class.java, Integer.TYPE, Integer.TYPE))
                .setId("xhLegacyAudioFocusRequest")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val listener = chain.getArg(0)
                    if (internalRequest.get() == true || chain.getArg(1) != AudioManager.STREAM_MUSIC)
                        return@Hooker chain.proceed()
                    observeListener(module, listener)
                    if (own != null) releaseOwn()
                    val result = chain.proceed()
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && listener != null) {
                        nativeRequest = WeakReference(listener)
                        nativeListener = WeakReference(listener)
                        nativeLegacyGain = chain.getArg(2) as? Int ?: AudioManager.AUDIOFOCUS_GAIN
                        focusLost = false
                    }
                    result
                })
            module.hook(audio.getDeclaredMethod("abandonAudioFocusRequest", AudioFocusRequest::class.java))
                .setId("xhNativeAudioFocusAbandon")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val request = chain.getArg(0)
                    val result = chain.proceed()
                    if (internalRequest.get() != true && nativeRequest.get() === request) {
                        nativeRequest.clear()
                        nativeListener.clear()
                    }
                    result
                })
            module.hook(audio.getDeclaredMethod("abandonAudioFocus", AudioManager.OnAudioFocusChangeListener::class.java))
                .setId("xhLegacyAudioFocusAbandon")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val listener = chain.getArg(0)
                    val result = chain.proceed()
                    if (internalRequest.get() != true && nativeListener.get() === listener) {
                        nativeRequest.clear()
                        nativeListener.clear()
                    }
                    result
                })
            LogUtil.info("音频协调: Android 原生焦点请求观察已安装")
        } catch (e: Throwable) { LogUtil.warn("音频协调: 部分焦点观察安装失败 " + e) }
    }
}
