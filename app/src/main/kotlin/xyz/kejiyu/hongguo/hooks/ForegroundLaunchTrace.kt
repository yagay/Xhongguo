package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import xyz.kejiyu.hongguo.FeatureTrace

/**
 * Investigation and narrowly scoped prevention of host task foregrounding.
 *
 * Observes host-side requests. Only a confirmed feed EOF, followed by an
 * official background ShortSeriesImpl launch, is blocked. All other launch
 * paths are observational. Cross-process launches may bypass these hooks.
 *
 * No Intent extras, URIs, identifiers, raw stack traces or user data logged.
 */
internal object ForegroundLaunchTrace {
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val HOST_PREFIX = "com.dragon.read."
    @Volatile private var lastPauseMs = 0L
    @Volatile private var lastFeedEofMs = 0L

    private fun importance(): Int = try {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance
    } catch (_: Throwable) { -1 }

    private fun recentStack(): String = try {
        Throwable().stackTrace.asSequence()
            .filter { it.className.startsWith(HOST_PREFIX) ||
                it.className.startsWith("xyz.kejiyu.hongguo.") }
            .filterNot { it.className == ForegroundLaunchTrace::class.java.name }
            .take(3)
            .map { frame ->
                val className = frame.className.substringAfterLast('.')
                    .replace(Regex("[^A-Za-z0-9_$]"), "_")
                val method = frame.methodName
                    .replace(Regex("[^A-Za-z0-9_$]"), "_")
                "$className.$method"
            }.joinToString(";").take(115)
    } catch (_: Throwable) { "" }

    private fun intentType(intent: Intent?): String {
        val clazz = intent?.component?.className ?: return "implicit"
        return when {
            clazz == SERIES -> "series"
            clazz.contains("ShortSeriesDetailActivity") -> "series_detail"
            clazz.contains("MainFragmentActivity") -> "main"
            clazz.startsWith(HOST_PREFIX) -> "host_other"
            else -> "external"
        }
    }

    /** Native completion is evidence, not a request to navigate. */
    fun onNativeCompletion() {
        if (importance() >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
            lastFeedEofMs = SystemClock.elapsedRealtime()
    }

    /**
     * 21:38:46 diagnostic: native EOF while MainFragmentActivity is stopped,
     * followed 55 ms later by ShortSeriesImpl.openShortSeriesActivityForResult.
     *
     * This is NOT the user's foreground tap nor the series ViewPager's
     * ordinary next episode. Never block an unknown caller or new task.
     */
    fun shouldSuppressBackgroundFeedEofLaunch(
        source: Activity?, intent: Intent?
    ): Boolean {
        if (source?.javaClass?.name !=
            "com.dragon.read.pages.main.MainFragmentActivity" ||
            intentType(intent) != "series") return false
        val now = SystemClock.elapsedRealtime()
        val elapsedSincePause = now - lastPauseMs
        val elapsedSinceEof = now - lastFeedEofMs
        if (lastPauseMs <= 0 || lastFeedEofMs <= 0 ||
            elapsedSincePause < 1000L ||
            elapsedSinceEof !in 0L..2500L ||
            importance() < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ||
            source.hasWindowFocus()) return false
        val officialEofLaunch = try {
            Throwable().stackTrace.any {
                it.className.contains("ShortSeriesImpl") &&
                    it.methodName == "openShortSeriesActivityForResult"
            }
        } catch (_: Throwable) { false }
        if (!officialEofLaunch) return false
        // One EOF can cause only one suppression. No global Activity block.
        lastFeedEofMs = 0L
        FeatureTrace.event("foreground", "background_eof_series_launch",
            "blocked", "origin=official_feed_eof,background=true," +
                "pauseAgeMs=$elapsedSincePause,eofAgeMs=$elapsedSinceEof")
        return true
    }

    fun activityStart(source: Activity?, intent: Intent?, entry: String) {
        FeatureTrace.event("foreground", "activity_launch", "observed",
            "api=$entry,target=" + intentType(intent) +
            ",flags=" + (intent?.flags ?: 0) +
            ",importance=" + importance() +
            ",focused=" + (source?.hasWindowFocus() == true) +
            ",task=" + (source?.taskId ?: -1) +
            ",caller=" + recentStack())
    }

    fun contextStart(intent: Intent?, entry: String) {
        // Distinguish context-based NEW_TASK launches from Activity navigation.
        FeatureTrace.event("foreground", "context_launch", "observed",
            "api=$entry,target=" + intentType(intent) +
            ",newTask=" + ((intent?.flags ?: 0).and(Intent.FLAG_ACTIVITY_NEW_TASK) != 0) +
            ",importance=" + importance() +
            ",caller=" + recentStack())
    }

    fun moveTask(taskId: Int?, flags: Int?) {
        FeatureTrace.event("foreground", "task_to_front", "observed",
            "targetOwnTask=" + (taskId != null && taskId == lastTaskId) +
            ",flags=" + (flags ?: -1) +
            ",importance=" + importance() +
            ",caller=" + recentStack())
    }

    @Volatile private var lastTaskId = -1

    fun onNewIntent(activity: Activity?) {
        FeatureTrace.event("foreground", "new_intent", "observed",
            "page=" + pageType(activity) +
            ",importance=" + importance() +
            ",caller=" + recentStack())
    }

    private fun pageType(activity: Activity?): String {
        val name = activity?.javaClass?.name ?: return "unknown"
        return when {
            name == SERIES -> "series"
            name.contains("MainFragmentActivity") -> "main"
            name.contains("DetailActivity") -> "detail"
            else -> "other"
        }
    }

    fun onPause(activity: Activity?) {
        lastPauseMs = SystemClock.elapsedRealtime()
        if (activity != null) lastTaskId = activity.taskId
        FeatureTrace.event("foreground", "activity_pause", "observed",
            "page=" + pageType(activity) +
            ",importance=" + importance() +
            ",focused=" + (activity?.hasWindowFocus() == true))
    }

    fun onWindowFocus(activity: Activity?, focused: Boolean) {
        if (!focused) return
        // Focus is stronger evidence of an actual foreground takeover than
        // onResume() alone, which can run before a window becomes visible.
        FeatureTrace.event("foreground", "window_focus", "observed",
            "page=" + pageType(activity) +
            ",importance=" + importance() +
            ",task=" + (activity?.taskId ?: -1))
    }

    fun onResume(activity: Activity?) {
        val now = SystemClock.elapsedRealtime()
        val pauseAge = if (lastPauseMs > 0L) now - lastPauseMs else -1L
        if (activity != null) lastTaskId = activity.taskId
        FeatureTrace.event("foreground", "activity_resume", "observed",
            "page=" + pageType(activity) +
            ",importance=" + importance() +
            ",pauseAgeMs=$pauseAge" +
            ",focused=" + (activity?.hasWindowFocus() == true) +
            ",caller=" + recentStack())
    }
}
