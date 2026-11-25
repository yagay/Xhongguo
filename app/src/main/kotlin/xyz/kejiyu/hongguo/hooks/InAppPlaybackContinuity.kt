package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * Only guard explicitly identified non-player navigation on the existing
 * engine. Never alter native wrappers, Surface flags, exit arguments,
 * player ownership, stop/release, or a normal series launch.
 */
internal object InAppPlaybackContinuity {
    private const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    @Volatile var enabled = false
        private set
    @Volatile private var engineRef = WeakReference<Any>(null)
    @Volatile private var ownerRef = WeakReference<Activity>(null)
    private data class Hold(val engine: Any, val owner: Activity, val reason: String, val until: Long)
    @Volatile private var held: Hold? = null
    @Volatile private var playerNavigationUntil = 0L

    fun configure(context: Context, requested: Boolean) {
        if (enabled && !requested) {
            clear("disabled")
            engineRef.clear()
            ownerRef.clear()
            playerNavigationUntil = 0L
        }
        enabled = requested
    }

    private fun playerPage(activity: Activity?) =
        NativeBusinessResolver.page(activity, NativePlaybackSession.engine()) !=
            NativeBusinessResolver.Page.UNKNOWN

    private fun playing(engine: Any?) = engine != null &&
        NativeMetadataAccess.call(engine, "isReleased") != true &&
        (NativeMetadataAccess.call(engine, "getPlaybackState") as? Number)?.toInt() == 1

    private fun clear(reason: String) {
        val old = held ?: return
        held = null
        LogUtil.info("持续播放: 导航保护结束 reason=$reason previous=" + old.reason)
    }

    fun observeEngine(engine: Any?, action: String, activity: Activity?) {
        if (!enabled || engine == null) return
        when (action) {
            "play", "start" -> {
                if (held?.engine != null && held?.engine !== engine) clear("new-engine")
                if (playerPage(activity)) {
                    engineRef = WeakReference(engine)
                    ownerRef = WeakReference(activity)
                }
            }
            "pause", "stop", "release", "releaseAsync" -> {
                if (held?.engine === engine) clear("native-$action")
                if (action.startsWith("release") && engineRef.get() === engine) {
                    engineRef.clear()
                    ownerRef.clear()
                }
            }
        }
    }

    private fun begin(activity: Activity, reason: String) {
        if (!enabled || !playerPage(activity) || activity.isFinishing || activity.isDestroyed ||
            SystemClock.uptimeMillis() < playerNavigationUntil) return
        val confirmed = NativePlaybackSession.engine()
        if (confirmed != null && NativePlaybackSession.currentFor(activity) !== confirmed) return
        val shared = (NativePlaybackSession.currentFor(activity) ?: SystemMediaBridge.currentMiniEngine(activity))
            ?.takeIf { playing(it) }
        val local = engineRef.get()?.takeIf { ownerRef.get() === activity && playing(it) }
        if (shared != null && local != null && shared !== local) return
        val engine = shared ?: local ?: return
        val old = held
        if (old?.engine === engine && old.owner === activity &&
            SystemClock.uptimeMillis() < old.until) return
        held = Hold(engine, activity, reason, SystemClock.uptimeMillis() + 3500L)
        LogUtil.info("持续播放: 确认非播放页导航 reason=$reason")
        LogUtil.incr("inAppContinuityNavigation")
    }

    fun beforeTab(activity: Activity, tab: String) {
        if (!enabled) return
        if (tab == "首页") clear("home-tab")
        else begin(activity, "tab:$tab")
    }

    fun beforeLaunch(activity: Activity?, intent: Intent?) {
        if (!enabled || activity == null || intent == null) return
        val target = intent.component ?: runCatching {
            intent.resolveActivity(activity.packageManager)
        }.getOrNull() ?: return
        if (target.packageName != activity.packageName) return
        val targetType = runCatching {
            Class.forName(target.className, false, activity.classLoader)
        }.getOrNull()
        if (NativeBusinessResolver.knownPage(targetType) != NativeBusinessResolver.Page.UNKNOWN ||
            target.className in setOf(MAIN, SERIES)) {
            // This must happen before the first feed pause and series resume.
            clear("native-player-launch")
            playerNavigationUntil = SystemClock.uptimeMillis() + 5000L
            LogUtil.info("持续播放: 原生播放页跳转完全放行 target=" +
                target.className.substringAfterLast('.'))
            return
        }
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) return
        // Limit to explicit browse destinations; unknown playback routes
        // are deliberately untouched.
        val name = target.className.substringAfterLast('.').lowercase()
        if (targetType == null ||
            listOf("search", "detail", "setting", "favorite", "history", "profile", "mine")
                .none { name.contains(it) } ||
            listOf("player", "playactivity", "fullscreen", "shortseries", "videoactivity")
                .any { name.contains(it) }) {
            clear("unknown-or-player-route")
            return
        }
        begin(activity, "activity:" + target.className.substringAfterLast('.'))
    }

    fun onResumed(activity: Activity?) {
        if (!enabled || activity == null) return
        val entry = held ?: return
        if (playerPage(activity) &&
            (activity !== entry.owner || entry.reason.startsWith("activity:")))
            clear("player-resumed")
    }

    fun onDestroyed(activity: Activity?) {
        if (held?.owner === activity) clear("owner-destroyed")
    }

    fun cancelAudioRecovery() { clear("audio-interruption") }

    fun keepLifecyclePause(candidate: Any?): Boolean {
        if (AudioFocusCoordinator.blocksPauseProtection()) return false
        val entry = held ?: return false
        if (!enabled || candidate !== entry.engine || entry.owner.isDestroyed ||
            SystemMediaBridge.userTransportActive() ||
            SystemClock.uptimeMillis() < playerNavigationUntil ||
            SystemClock.uptimeMillis() > entry.until || !playing(entry.engine)) return false
        val stack = Thread.currentThread().stackTrace
        if (stack.any { it.methodName in setOf("onAudioFocusChange", "onCompletion",
                    "onError", "release", "stop", "playNext") ||
                    it.className.contains("ShortSeriesActivity") &&
                    it.methodName in setOf("onCreate", "onResume", "finish") }) return false
        if (stack.none {
            it.methodName in setOf("onPause", "onStop", "onHiddenChanged", "setUserVisibleHint",
                "onTabUnselected", "onLifeCycleOnPause", "onLifeCycleOnStop",
                "onWindowVisibilityChanged", "onVisibilityChanged") &&
                (it.className.startsWith("com.dragon.read.") ||
                    it.className.startsWith("androidx.") ||
                    it.className.startsWith("com.ss.android.videoshop."))
        }) return false
        LogUtil.incr("inAppContinuityLifecyclePause")
        LogUtil.info("持续播放: 已保护明确非播放页生命周期暂停 reason=" + entry.reason)
        return true
    }
}
