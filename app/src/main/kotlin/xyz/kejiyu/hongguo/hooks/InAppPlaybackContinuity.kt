package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import xyz.kejiyu.hongguo.LogUtil

/**
 * Opt-in audio continuation without a mini View, native PiP or new decoder.
 * Normal player navigation, user commands, stop/release and failures remain
 * owned by the host. Native exit sharing is limited to the inspected APK.
 */
internal object InAppPlaybackContinuity {
    private const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private val main = Handler(Looper.getMainLooper())
    @Volatile var enabled = false
        private set
    @Volatile private var verified = false
    @Volatile private var engineRef = WeakReference<Any>(null)
    @Volatile private var ownerRef = WeakReference<Activity>(null)
    private val wrappers = ArrayList<WeakReference<Any>>()
    private var readEngine: Method? = null
    private var setWithoutSurface: Method? = null
    private var flagField: Field? = null
    private var audioSingleton: Field? = null
    private var audioPlayer: Field? = null
    private var wrapperClass: Class<*>? = null
    private data class Held(
        val engine: Any, val owner: Activity, val wrapper: Any?,
        val previousFlag: Boolean?, val reason: String,
        val deadline: Long
    )
    @Volatile private var held: Held? = null
    private data class Exit(val owner: Activity, val engine: Any)
    private val exiting = ThreadLocal<Exit?>()
    @Volatile private var transferred = WeakReference<Any>(null)

    fun configure(context: Context, requested: Boolean) {
        val supported = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName == "7.4.2.32"
        }.getOrDefault(false)
        verified = supported
        if (enabled && !requested) finishHold("disabled")
        enabled = requested
        if (!requested) transferred.clear()
    }

    fun resolve(loader: ClassLoader): Class<*>? = try {
        val wrapper = Class.forName("l65.x", false, loader)
        val engine = Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, loader)
        val audio = Class.forName("n65.b", false, loader)
        val read = wrapper.getMethod("I")
        val without = wrapper.getMethod("W", Boolean::class.javaPrimitiveType)
        val flag = wrapper.getDeclaredField("S")
        val singleton = audio.getDeclaredField("a")
        val player = audio.getDeclaredField("d")
        if (read.returnType != engine || without.returnType != Void.TYPE ||
            flag.type != Boolean::class.javaPrimitiveType || singleton.type != audio ||
            !player.type.isAssignableFrom(wrapper)) null
        else {
            flag.isAccessible = true
            singleton.isAccessible = true
            player.isAccessible = true
            readEngine = read
            setWithoutSurface = without
            flagField = flag
            audioSingleton = singleton
            audioPlayer = player
            wrapperClass = wrapper
            wrapper
        }
    } catch (_: Throwable) { null }

    private fun playerPage(a: Activity?) = a?.javaClass?.name in setOf(MAIN, SERIES)
    private fun state(engine: Any?) = (NativeMetadataAccess.call(engine, "getPlaybackState") as? Number)?.toInt()
    private fun playing(engine: Any?) = engine != null &&
        NativeMetadataAccess.call(engine, "isReleased") != true && state(engine) == 1
    private fun existingEngine(wrapper: Any?): Any? =
        if (wrapper == null || wrapperClass?.isInstance(wrapper) != true) null
        else runCatching { readEngine?.invoke(wrapper) }.getOrNull()
    private fun audioWrapper(): Any? = runCatching {
        val manager = audioSingleton?.get(null) ?: return@runCatching null
        audioPlayer?.get(manager)
    }.getOrNull()

    fun observeWrapper(wrapper: Any?) {
        if (!enabled || !verified || wrapper == null) return
        synchronized(wrappers) {
            wrappers.removeAll { it.get() == null }
            if (wrappers.none { it.get() === wrapper }) wrappers.add(WeakReference(wrapper))
            while (wrappers.size > 48) wrappers.removeAt(0)
        }
    }

    private fun wrapperFor(engine: Any): Any? = synchronized(wrappers) {
        wrappers.mapNotNull { it.get() }.filter { existingEngine(it) === engine }.singleOrNull()
    }

    private fun exactEngine(activity: Activity): Any? {
        if (!playerPage(activity) || activity.isDestroyed || activity.isFinishing) return null
        val local = engineRef.get()?.takeIf { ownerRef.get() === activity && playing(it) }
        val shared = SystemMediaBridge.currentMiniEngine(activity)?.takeIf { playing(it) }
        if (local != null && shared != null && local !== shared) return null
        return shared ?: local
    }

    fun observeEngine(engine: Any?, action: String, activity: Activity?) {
        if (!enabled || engine == null) return
        when (action) {
            "play", "start" -> {
                if (playerPage(activity)) {
                    engineRef = WeakReference(engine)
                    ownerRef = WeakReference(activity)
                }
                val old = transferred.get()
                // A new real video must win over any transferred audio,
                // but never pause another decoder before it starts.
                if (old != null && old !== engine) {
                    main.postDelayed({
                        if (!enabled || transferred.get() !== old ||
                            !playing(engine) || !playing(old)) return@postDelayed
                        val wrapper = audioWrapper()
                        if (existingEngine(wrapper) === old) {
                            runCatching { wrapper?.javaClass?.getMethod("pause")?.invoke(wrapper) }
                            LogUtil.info("持续播放: 新视频已播放，停止旧音频会话")
                            transferred.clear()
                        }
                    }, 850L)
                }
                if (held?.engine != null && held?.engine !== engine)
                    main.post { if (held?.engine !== engine) finishHold("new-native-engine") }
            }
            "pause", "stop", "releaseAsync", "release" -> {
                if (held?.engine === engine)
                    main.post { if (held?.engine === engine) finishHold("native-$action") }
                if (action.startsWith("release") && engineRef.get() === engine) {
                    engineRef.clear()
                    ownerRef.clear()
                }
                if (transferred.get() === engine && action != "pause") transferred.clear()
            }
        }
    }

    fun beforeTab(activity: Activity, tab: String) {
        if (!enabled) return
        if (tab == "首页") finishHold("home-tab")
        else begin(activity, "tab:$tab")
    }

    fun beforeLaunch(activity: Activity?, intent: Intent?) {
        if (!enabled || activity == null || intent == null) return
        val target = intent.component ?: runCatching {
            intent.resolveActivity(activity.packageManager)
        }.getOrNull() ?: return
        if (target.packageName != activity.packageName ||
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) return
        if (target.className in setOf(MAIN, SERIES)) {
            finishHold("native-player-navigation")
            return
        }
        begin(activity, "activity:" + target.className.substringAfterLast('.'))
    }

    private fun begin(activity: Activity, reason: String) {
        if (!enabled || Looper.myLooper() != Looper.getMainLooper()) return
        val engine = exactEngine(activity) ?: return
        val previous = held
        if (previous?.engine === engine && previous.owner === activity &&
            SystemClock.uptimeMillis() < previous.deadline) return
        finishHold("new-navigation")
        val wrapper = if (verified) wrapperFor(engine) else null
        val flag = wrapper?.let {
            runCatching { flagField?.getBoolean(it) }.getOrNull()
        }
        val entry = Held(engine, activity, wrapper, flag, reason, SystemClock.uptimeMillis() + 3500L)
        held = entry
        if (wrapper != null && flag != null) {
            runCatching { setWithoutSurface?.invoke(wrapper, true) }
                .onFailure { LogUtil.warn("持续播放: 原生无Surface标志设置失败 " + it.javaClass.simpleName) }
        }
        LogUtil.info("持续播放: 导航保护 reason=$reason wrapperMatched=" + (wrapper != null))
        LogUtil.incr("inAppContinuityNavigation")
    }

    private fun finishHold(reason: String) {
        val entry = held ?: return
        held = null
        if (entry.previousFlag != null && entry.wrapper != null &&
            existingEngine(entry.wrapper) === entry.engine &&
            NativeMetadataAccess.call(entry.engine, "isReleased") != true) {
            runCatching { setWithoutSurface?.invoke(entry.wrapper, entry.previousFlag) }
        }
        LogUtil.info("持续播放: 结束导航保护 reason=$reason")
    }

    fun onResumed(activity: Activity?) {
        val entry = held ?: return
        if (!enabled || activity == null) return
        if (activity === entry.owner && entry.reason.startsWith("activity:")) {
            finishHold("return-to-player")
        } else if (playerPage(activity) && activity !== entry.owner) {
            finishHold("new-player-page")
        }
    }

    fun onDestroyed(activity: Activity?) {
        if (held?.owner === activity) finishHold("owner-destroyed")
    }

    /** No global pause hook: exact engine, explicit navigation, short
     * window, known lifecycle stack, and no system-media user transport. */
    fun keepLifecyclePause(candidate: Any?): Boolean {
        val entry = held ?: return false
        if (!enabled || candidate == null || SystemMediaBridge.userTransportActive() ||
            SystemClock.uptimeMillis() > entry.deadline || !playing(entry.engine)) return false
        val bound = candidate === entry.engine ||
            existingEngine(candidate) === entry.engine ||
            NativeMetadataAccess.call(candidate, "getVideoEngine") === entry.engine
        if (!bound) return false
        val stack = Thread.currentThread().stackTrace
        if (stack.any { it.methodName in setOf("onAudioFocusChange", "onCompletion",
                    "onError", "release", "stop", "playNext") }) return false
        val lifecycle = stack.any {
            it.methodName in setOf("onPause", "onStop", "onHiddenChanged", "setUserVisibleHint",
                "onPageSelected", "onTabUnselected", "onLifeCycleOnPause", "onLifeCycleOnStop",
                "onWindowVisibilityChanged", "onVisibilityChanged") &&
                (it.className.startsWith("com.dragon.read.") ||
                    it.className.startsWith("androidx.") ||
                    it.className.startsWith("com.ss.android.videoshop.") ||
                    it.className == "nt4.a1")
        }
        if (!lifecycle) return false
        LogUtil.incr("inAppContinuityLifecyclePause")
        LogUtil.info("持续播放: 保留当前播放器生命周期暂停 reason=" + entry.reason)
        return true
    }

    /** Original presenter c() is the official exit/share entry. No direct
     * invocation of ph(), pool mutation, release suppression or new media. */
    fun beginNativeExit(presenter: Any?): Boolean {
        if (!enabled || !verified || wrapperClass == null || exiting.get() != null) return false
        val activity = NativeMetadataAccess.call(presenter, "getActivity") as? Activity ?: return false
        if (activity.javaClass.name != SERIES || activity.isDestroyed) return false
        val engine = exactEngine(activity) ?: return false
        val existingAudio = audioWrapper()
        if (existingAudio != null && existingEngine(existingAudio) !== engine) {
            LogUtil.warn("持续播放: 已存在其他原生音频会话，跳过共享退出")
            return false
        }
        exiting.set(Exit(activity, engine))
        LogUtil.info("持续播放: 请求官方全集音频交接")
        return true
    }

    fun exitArgs(activity: Any?, original: Any?): Any? {
        val exit = exiting.get() ?: return original
        if (!enabled || activity !== exit.owner || original !is Bundle) return original
        return Bundle(original).apply {
            putBoolean("key_enable_share_player_when_exit", true)
        }
    }

    fun exitAudioMode(fragment: Any?): Boolean {
        val exit = exiting.get() ?: return false
        return enabled && NativeMetadataAccess.call(fragment, "getActivity") === exit.owner
    }

    fun endNativeExit() {
        val exit = exiting.get() ?: return
        exiting.remove()
        val actual = existingEngine(audioWrapper())
        val matched = actual === exit.engine
        LogUtil.info("持续播放: 官方音频交接 matched=$matched state=" + state(actual))
        if (matched) {
            transferred = WeakReference(exit.engine)
            // The native manager now owns the wrapper and its flag. Never
            // overwrite its playWithoutSurface setting with the old owner value.
            if (held?.engine === exit.engine) held = null
            LogUtil.incr("inAppContinuityNativeAudioTransferred")
        } else LogUtil.incr("inAppContinuityNativeAudioUnconfirmed")
    }
}
