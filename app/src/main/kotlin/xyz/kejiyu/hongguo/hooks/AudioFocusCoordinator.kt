package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    @Volatile private var resumeCalls = true
    @Volatile private var pauseDuck = false
    @Volatile private var externalEnabled = false
    @Volatile private var externalActive = false
    private var externalReceiver: BroadcastReceiver? = null
    private var externalContext: Context? = null
    private var externalSignalAt = 0L
    private var externalRawActive = false
    private var externalSignalGeneration = 0L
    private var lastExternalDiagnosticAt = 0L
    private var lastConfiguration = ""
    private var lastIgnoredFocusAt = 0L
    private var retryGeneration = 0L
    private var interruptionGeneration = 0L
    private var permanentlyInterrupted = false
    @Volatile private var focusOwnerObserved = false
    private var resumeAuthorized = false
    @Volatile private var focusLost = false
    @Volatile private var callActive = false
    @Volatile private var manualHold = false
    @Volatile private var manager: AudioManager? = null
    private var modeRegistration: Any? = null
    @Volatile private var own: AudioFocusRequest? = null
    // Keep the granted request alive until abandon/replacement; weak references can
    // disappear while AudioService still owns this client.
    @Volatile private var nativeRequest: Any? = null
    @Volatile private var nativeListener: Any? = null
    private val focusLock = Any()
    private val grantedClients = LinkedHashMap<Any, Pair<Any?, Int>>()
    @Volatile private var nativeLegacyGain = AudioManager.AUDIOFOCUS_GAIN
    private val builderListeners = WeakHashMap<Any, WeakReference<Any>>()
    private val requestListeners = WeakHashMap<Any, WeakReference<Any>>()
    private val hooked = HashSet<Method>()
    private val internalRequest = ThreadLocal<Boolean>()
    private var lastRequestEngine = WeakReference<Any>(null)
    private var lastRequestVideo: String? = null
    private data class ResumeTicket(val engine: WeakReference<Any>, val video: String?, val epoch: Long)
    @Volatile private var ticket: ResumeTicket? = null

    fun configure(ctx: Context, active: Boolean, focus: Boolean, calls: Boolean, resume: Boolean,
                  callResume: Boolean, duck: Boolean, external: Boolean) {
        val primary = runCatching { Application.getProcessName() == ctx.packageName }.getOrDefault(false)
        val task = Runnable {
            enabled = active && primary && (focus || calls || external)
            pauseFocus = focus
            pauseCall = calls
            autoResume = resume
            resumeCalls = callResume
            pauseDuck = duck
            externalEnabled = enabled && external
            val configuration = "enabled=$enabled primary=$primary focus=$pauseFocus calls=$pauseCall" +
                " external=$externalEnabled autoResume=$autoResume callResume=$resumeCalls duck=$pauseDuck"
            if (configuration != lastConfiguration) {
                lastConfiguration = configuration
                LogUtil.info("音频协调: 实际配置 $configuration")
            }
            configureExternal(ctx.applicationContext)
            if (!enabled) {
                releaseOwn()
                unregisterMode()
                focusLost = false
                callActive = false
                manualHold = false
                ticket = null
                permanentlyInterrupted = false
                resumeAuthorized = false
                ++retryGeneration
                ++interruptionGeneration
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
            if (!autoResume && !resumeCalls) { ticket = null; resumeAuthorized = false }
            NativePlaybackSession.engine()?.let { engine ->
                if (isPlaying(engine)) onPlaying(engine, NativePlaybackSession.owner(), false)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    fun interrupted(): Boolean = enabled && (focusLost || callActive || externalActive || manualHold)
    private fun hardInterrupted() = enabled && (focusLost || callActive || externalActive)

    private fun isPlaying(engine: Any?): Boolean =
        NativePlaybackSession.playing(engine) ||
            (NativePlaybackSession.state(engine) == null &&
                NativeMetadataAccess.call(engine, "isPlaying") == true)

    // Only suppress an automatic start from the confirmed player or its
    // background owner. Do not intercept unrelated/preload decoders.
    fun blocksNativeBackgroundStart(engine: Any?, activity: Activity?): Boolean {
        if (!interrupted() || !NativePlaybackSession.isEngine(engine)) return false
        val owner = NativePlaybackSession.owner()
        val active = SystemMediaBridge.audioFocusEngine() ?: return false
        if (activity?.hasWindowFocus() == true || owner?.hasWindowFocus() == true) return false
        if (engine !== active && (owner == null || activity !== owner)) return false
        LogUtil.info("音频协调: 已阻止后台自动开始播放")
        LogUtil.incr("audioBackgroundStartBlocked")
        return true
    }

    fun blocksEpisodeNavigation(): Boolean = hardInterrupted()

    private fun configureExternal(ctx: Context) {
        if (!externalEnabled) {
            externalReceiver?.let { receiver -> runCatching { externalContext?.unregisterReceiver(receiver) } }
            externalReceiver = null
            externalContext = null
            externalActive = false
            externalRawActive = false
            externalSignalAt = 0L
            ++externalSignalGeneration
            return
        }
        if (externalReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!externalEnabled || intent.action != SystemAudioActivity.ACTION) return
                    // Both endpoints use elapsedRealtime; reject old queued
                    // snapshots without clearing an existing interruption.
                    val sent = intent.getLongExtra("snapshot_elapsed", -1L)
                    val now = SystemClock.elapsedRealtime()
                    if (sent < 0 || now - sent !in 0L..15_000L ||
                        sent < externalSignalAt) return
                    val active = intent.getBooleanExtra("external", false)
                    val ownActive = intent.getBooleanExtra("own", false)
                    val first = externalSignalAt == 0L
                    val changed = active != externalRawActive
                    externalSignalAt = sent
                    externalRawActive = active
                    if (first || changed || now - lastExternalDiagnosticAt >= 60_000L) {
                        lastExternalDiagnosticAt = now
                        LogUtil.info("音频协调: 系统音频状态同步 external=$active own=$ownActive first=$first")
                    }
                    val next = active && (ownActive || externalActive ||
                        isPlaying(SystemMediaBridge.audioFocusEngine()))
                    if (next == externalActive) return
                    externalActive = next
                    if (next) interrupt("external-media", false) else gained("external-media-ended")
                }
            }
            val filter = IntentFilter(SystemAudioActivity.ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, SystemAudioActivity.SENDER_PERMISSION, main, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(receiver, filter, SystemAudioActivity.SENDER_PERMISSION, main)
            }
            externalReceiver = receiver
            externalContext = ctx
            LogUtil.info("音频协调: 高级音频活动接收器已启用，等待系统框架信号")
            val generation = ++externalSignalGeneration
            main.postDelayed({
                if (generation == externalSignalGeneration && externalEnabled &&
                    externalSignalAt == 0L) LogUtil.warn(
                    "音频协调: 未收到系统音频状态；检查 LSPosed 系统框架作用域和重启")
            }, 18_000L)
        } catch (e: Throwable) { LogUtil.warn("音频协调: 高级监听不可用 " + e.javaClass.simpleName) }
    }
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
        if (next) interrupt("call-mode:$mode", true, resumeCalls)
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
        if (!enabled || !pauseFocus || callActive || externalActive || Build.VERSION.SDK_INT < 26) return false
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
                .setWillPauseWhenDucked(pauseDuck)
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
        LogUtil.info("音频协调: 焦点回调 source=$source change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && !pauseDuck) return
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
    private fun interrupt(reason: String, temporary: Boolean, allowResume: Boolean = autoResume) {
        manualHold = true
        val engine = SystemMediaBridge.audioFocusEngine()
        val playing = isPlaying(engine)
        val epoch = NativePlaybackSession.generation()
        val id = NativePlaybackSession.videoId(engine)
        val task = Runnable {
            if (!enabled) return@Runnable
            if (!temporary) {
                permanentlyInterrupted = true
                ticket = null
                resumeAuthorized = false
            } else if (!permanentlyInterrupted) {
                // A transient focus callback can precede the call-mode event.
                // Capture once, then let either enabled policy authorize resume.
                if (playing && engine != null && ticket == null)
                    ticket = ResumeTicket(WeakReference(engine), id, epoch)
                if (allowResume && ticket != null) resumeAuthorized = true
            }
            ++retryGeneration
            LogUtil.info("音频协调: 中断 $reason playing=$playing resume=$allowResume")
            LogUtil.incr("audioFocusInterruption")
            InAppMiniPlayer.cancelAudioRecovery()
            InAppPlaybackContinuity.cancelAudioRecovery()
            SystemMediaBridge.onAudioInterruption(reason)
            enforcePause(reason)
            if (!temporary) releaseOwn()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    // Recheck the confirmed engine, not a stale decoder. Bounded retries only;
    // the native play observer separately catches new episodes during a hold.
    private fun enforcePause(reason: String) {
        val serial = ++interruptionGeneration
        var confirmed = false
        fun check() {
            if (serial != interruptionGeneration || !interrupted()) return
            val current = SystemMediaBridge.audioFocusEngine() ?: return
            val state = NativePlaybackSession.state(current)
            val playing = isPlaying(current)
            if (!playing) {
                if (!confirmed && (state == 0 || state == 2 ||
                        NativeMetadataAccess.call(current, "isPlaying") == false)) {
                    confirmed = true
                    LogUtil.info("音频协调: 暂停状态已确认 reason=$reason")
                    LogUtil.incr("audioPauseConfirmed")
                }
                return
            }
            confirmed = false
            val sent = transport(current, false)
            LogUtil.info("音频协调: 暂停确认 reason=$reason sent=$sent")
        }
        check()
        for (delay in longArrayOf(160L, 450L, 1100L, 2200L)) main.postDelayed({ check() }, delay)
    }

    private fun transport(engine: Any, play: Boolean): Boolean {
        if (SystemMediaBridge.audioFocusEngine() !== engine || !NativePlaybackSession.isEngine(engine) ||
            NativeMetadataAccess.call(engine, "isReleased") == true) return false
        return runCatching {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == (if (play) "play" else "pause") && HookBindingAudit.instanceVoid(it)
            } ?: return false
            method.invoke(engine)
            true
        }.getOrElse {
            LogUtil.warn("音频协调: 原生控制失败 " + it.javaClass.simpleName)
            false
        }
    }

    private fun gained(reason: String) {
        if (!enabled || hardInterrupted()) return
        val resume = ticket
        val authorized = resumeAuthorized
        ticket = null
        resumeAuthorized = false
        if (permanentlyInterrupted || !authorized || resume == null) {
            LogUtil.info("音频协调: $reason 等待用户主动播放")
            return
        }
        val target = resume.engine.get()
        if (target == null || SystemMediaBridge.audioFocusEngine() !== target ||
            resume.epoch != NativePlaybackSession.generation() ||
            resume.video != NativePlaybackSession.videoId(target)) return
        val generation = interruptionGeneration
        main.postDelayed({
            if (generation != interruptionGeneration || hardInterrupted() || permanentlyInterrupted ||
                SystemMediaBridge.audioFocusEngine() !== target ||
                resume.epoch != NativePlaybackSession.generation() ||
                resume.video != NativePlaybackSession.videoId(target)) return@postDelayed
            manualHold = false
            ++interruptionGeneration
            if (!NativePlaybackSession.playing(target)) transport(target, true)
            LogUtil.info("音频协调: 恢复同一播放会话 reason=$reason")
        }, 350L)
    }
    /** Called only for explicit media buttons; no automatic reacquisition. */
    fun userPlayAllowed(): Boolean {
        if (!enabled) return true
        if (pauseCall && manager?.let { runCatching { inCall(it.mode) }.getOrDefault(false) } == true)
            return false
        if (callActive || externalActive ||
            (externalEnabled && externalRawActive &&
                SystemClock.elapsedRealtime() - externalSignalAt in 0L..15_000L)) return false
        ticket = null
        resumeAuthorized = false
        ++interruptionGeneration
        if (pauseFocus) {
            val existing = nativeRequest
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
        permanentlyInterrupted = false
        return true
    }
    fun userPaused() {
        ticket = null
        resumeAuthorized = false
        manualHold = true
        permanentlyInterrupted = true
        ++retryGeneration
        ++interruptionGeneration
        lastRequestEngine.clear()
        lastRequestVideo = null
        releaseOwn()
    }
    fun onPlaying(engine: Any, activity: Activity?, allowForegroundRestart: Boolean = true) {
        if (!enabled) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { if (SystemMediaBridge.audioFocusEngine() === engine) onPlaying(engine, activity, allowForegroundRestart) }
            return
        }
        if (pauseCall) manager?.let { audio ->
            onMode(runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL))
        }
        if (externalEnabled && externalRawActive &&
            SystemClock.elapsedRealtime() - externalSignalAt in 0L..15_000L &&
            !externalActive) {
            externalActive = true
            interrupt("external-media-on-play", false)
            return
        }
        if (interrupted()) {
            // A real foreground play after focus gain may be user initiated.
            // Never clear a hold solely because a background callback played.
            if (allowForegroundRestart && !hardInterrupted() &&
                activity?.hasWindowFocus() == true && ticket == null) {
                manualHold = false
                permanentlyInterrupted = false
                ++interruptionGeneration
            } else {
                SystemMediaBridge.onAudioInterruption("native-play-during-interruption")
                enforcePause("native-play-during-interruption")
                return
            }
        }
        if (!pauseFocus || own != null || nativeRequest != null) return
        val id = NativePlaybackSession.videoId(engine)
        if (lastRequestEngine.get() === engine && lastRequestVideo == id) return
        lastRequestEngine = WeakReference(engine)
        lastRequestVideo = id
        val generation = ++retryGeneration
        fun attempt() {
            if (generation != retryGeneration || interrupted() || !enabled || !pauseFocus ||
                SystemMediaBridge.audioFocusEngine() !== engine ||
                !NativePlaybackSession.playing(engine) || own != null || nativeRequest != null) return
            // Do not steal focus with a new background request.
            if (activity?.hasWindowFocus() != true) return
            if (acquire()) ++retryGeneration
        }
        attempt()
        for (delay in longArrayOf(250L, 800L, 1800L, 3500L)) main.postDelayed({ attempt() }, delay)
        main.postDelayed({
            if (generation == retryGeneration && own == null && nativeRequest == null) {
                lastRequestEngine.clear()
                LogUtil.warn("音频协调: 补充焦点重试结束，等待新的真实播放事件；后台不抢焦点")
            }
        }, 4000L)
    }

    private fun registerGranted(request: Any, listener: Any?, gain: Int, observable: Boolean) {
        synchronized(focusLock) {
            grantedClients.remove(request)
            grantedClients[request] = listener to gain
            // Limit stale client bookkeeping; Android owns actual focus arbitration.
            while (grantedClients.size > 12) grantedClients.remove(grantedClients.keys.first())
            nativeRequest = request
            nativeListener = listener
            nativeLegacyGain = gain
            focusOwnerObserved = observable
            LogUtil.info("音频协调: 原生焦点客户端更新 observable=$observable tracked=" + grantedClients.size)
            if (!observable) LogUtil.warn("音频协调: 原生焦点已获批但回调不可观察；依靠系统音频状态，不抢占原生焦点")
        }
    }

    private fun unregisterGranted(request: Any?) {
        if (request == null) return
        val changed = synchronized(focusLock) {
            grantedClients.remove(request)
            if (nativeRequest !== request) false
            else {
                // Do not assume an older client still holds system focus.
                nativeRequest = null
                nativeListener = null
                focusOwnerObserved = false
                true
            }
        }
        if (changed) main.post {
            lastRequestEngine.clear()
            ++retryGeneration
            val engine = SystemMediaBridge.audioFocusEngine() ?: return@post
            if (isPlaying(engine)) onPlaying(engine, NativePlaybackSession.owner(), false)
        }
    }
    fun onEngineEnded(engine: Any, action: String) {
        if (!enabled) return
        if (action == "stop" || action.startsWith("release")) {
            if (ticket?.engine?.get() === engine) { ticket = null; resumeAuthorized = false }
            if (lastRequestEngine.get() === engine) lastRequestEngine.clear()
            if (SystemMediaBridge.audioFocusEngine() === engine || SystemMediaBridge.audioFocusEngine() == null) releaseOwn()
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
        resumeAuthorized = false
        lastRequestEngine.clear()
        ++retryGeneration
    }

    private fun listenerFor(request: Any): Any? = synchronized(requestListeners) {
        requestListeners[request]?.get()
    }
    private fun observeListener(module: MainHook, listener: Any?): Boolean {
        if (listener !is AudioManager.OnAudioFocusChangeListener) return false
        try {
            val method = listener.javaClass.getMethod("onAudioFocusChange", Integer.TYPE)
            if (!HookBindingAudit.instanceVoid(method, "int")) return false
            synchronized(hooked) {
                if (method in hooked) return true
                module.hook(method).setId("xhAudioFocusListener_" + method.declaringClass.name.hashCode())
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val change = chain.getArg(0) as? Int
                        if (enabled && change != null) {
                            val isCurrent = synchronized(focusLock) {
                                focusOwnerObserved && nativeListener === chain.thisObject &&
                                    grantedClients.values.any { it.first === chain.thisObject }
                            }
                            if (isCurrent) onFocus(change, "native")
                            else if (SystemClock.elapsedRealtime() - lastIgnoredFocusAt >= 3000L) {
                                lastIgnoredFocusAt = SystemClock.elapsedRealtime()
                                LogUtil.info("音频协调: 忽略非当前焦点客户端回调 change=$change")
                            }
                        }
                        chain.proceed()
                    })
                hooked.add(method)
            }
            return true
        } catch (e: Throwable) { LogUtil.warn("音频协调: 原生焦点监听无法绑定 " + e); return false }
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
                    val listener = listenerFor(request) ?: runCatching {
                        request.javaClass.getMethod("getOnAudioFocusChangeListener").invoke(request)
                    }.getOrNull()
                    val observable = observeListener(module, listener)
                    val result = chain.proceed()
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        if (own != null) releaseOwn()
                        registerGranted(request, listener, AudioManager.AUDIOFOCUS_GAIN, observable)
                        // A new granted request is evidence of real focus gain.
                        // Keep manualHold; a background request alone does not
                        // authorize automatic playback after an interruption.
                        focusLost = false // grant is real; manualHold still blocks background recovery
                        LogUtil.info("音频协调: 原生媒体焦点已确认 observable=$observable")
                    } else if (enabled) LogUtil.warn("音频协调: 原生焦点申请未获批准 result=$result")
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
                    val observable = observeListener(module, listener)
                    val result = chain.proceed()
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && listener != null) {
                        if (own != null) releaseOwn()
                        registerGranted(listener, listener, chain.getArg(2) as? Int ?: AudioManager.AUDIOFOCUS_GAIN, observable)
                        focusLost = false // grant is real; manualHold still blocks background recovery
                        LogUtil.info("音频协调: 原生旧式媒体焦点已确认 observable=$observable")
                    } else if (enabled) LogUtil.warn("音频协调: 旧式焦点未观察到有效批准 result=$result")
                    result
                })
            module.hook(audio.getDeclaredMethod("abandonAudioFocusRequest", AudioFocusRequest::class.java))
                .setId("xhNativeAudioFocusAbandon")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val request = chain.getArg(0)
                    val result = chain.proceed()
                    if (internalRequest.get() != true && result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) unregisterGranted(request)
                    result
                })
            module.hook(audio.getDeclaredMethod("abandonAudioFocus", AudioManager.OnAudioFocusChangeListener::class.java))
                .setId("xhLegacyAudioFocusAbandon")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val listener = chain.getArg(0)
                    val result = chain.proceed()
                    if (internalRequest.get() != true && result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) unregisterGranted(listener)
                    result
                })
            LogUtil.info("音频协调: Android 原生焦点请求观察已安装")
        } catch (e: Throwable) { LogUtil.warn("音频协调: 部分焦点观察安装失败 " + e) }
    }
}
