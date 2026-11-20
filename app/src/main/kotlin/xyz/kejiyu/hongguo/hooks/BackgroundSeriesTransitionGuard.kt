package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * Guard the user's task focus during native feed -> full-series transitions.
 *
 * A native ShortSeriesActivity may be created after the user has left Hongguo.
 * The old SystemMediaBridge assumed every Activity.onResume meant the user had
 * returned. That is not true when the app itself starts another Activity.
 *
 * Only a confirmed *invisible own task* followed by a fresh ShortSeriesActivity
 * qualifies. Never block the original launch, finish the Activity, restart the
 * app, or call a media transport method. A normal user-initiated foreground
 * series launch is left entirely alone.
 */
internal object BackgroundSeriesTransitionGuard {
    private const val FEED =
        "com.dragon.read.pages.main.MainFragmentActivity"
    private const val SERIES =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"

    private val main = Handler(Looper.getMainLooper())
    private var sourceActivity = WeakReference<Activity>(null)
    private var pendingSeriesActivity = WeakReference<Activity>(null)
    private var taskBackgrounded = false
    private var lastInvisibleAt = 0L
    private var pendingSeriesAt = 0L
    private var lastFocusReason = ""
    private var pendingRelocation = false

    private fun active(): Boolean = SystemMediaBridge.enabled

    private fun taskVisible(activity: Activity): Boolean? {
        return try {
            val manager = activity.getSystemService(Context.ACTIVITY_SERVICE)
                as? ActivityManager ?: return null
            manager.appTasks.firstOrNull {
                it.taskInfo.taskId == activity.taskId
            }?.taskInfo?.isVisible
        } catch (e: Throwable) {
            LogUtil.warn("后台切集守护: 任务可见性检测不可用 " +
                e.javaClass.simpleName)
            null
        }
    }

    private fun processImportance(): Int {
        val state = ActivityManager.RunningAppProcessInfo()
        return try {
            ActivityManager.getMyMemoryState(state)
            state.importance
        } catch (_: Throwable) { -1 }
    }

    /**
     * Called after an Activity truly loses foreground visibility. Do not infer
     * background merely from onUserLeaveHint or onPause: both also occur
     * during ordinary feed -> series navigation while the app is visible.
     */
    private fun inspectBackground(activity: Activity, source: String) {
        if (!active()) return
        val visibility = taskVisible(activity)
        val importance = processImportance()
        val confirmedHidden = visibility == false ||
            (visibility == null &&
                importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                !activity.hasWindowFocus())
        if (confirmedHidden && activity.javaClass.name == FEED) {
            if (!taskBackgrounded) {
                lastInvisibleAt = SystemClock.uptimeMillis()
                sourceActivity = WeakReference(activity)
                taskBackgrounded = true
                LogUtil.info("后台切集守护: 已确认刷剧任务在后台" +
                    " source=" + source + " taskVisible=" + visibility +
                    " importance=" + importance)
                LogUtil.incr("systemMediaSeriesTaskBackgroundVerified")
            }
        } else if (source == "pause+650ms" || source == "stop+350ms") {
            if (!taskBackgrounded) {
                LogUtil.info("后台切集守护: 未确认后台，不拦截 Activity" +
                    " source=" + source +
                    " taskVisible=" + visibility +
                    " importance=" + importance)
            }
        }
    }

    fun onActivityPaused(activity: Activity?) {
        if (!active() || activity?.javaClass?.name != FEED) return
        main.postDelayed({
            inspectBackground(activity, "pause+250ms")
        }, 250L)
        main.postDelayed({
            inspectBackground(activity, "pause+650ms")
        }, 650L)
    }

    fun onActivityStopped(activity: Activity?) {
        if (!active() || activity?.javaClass?.name != FEED) return
        main.postDelayed({
            inspectBackground(activity, "stop+350ms")
        }, 350L)
    }

    fun onUserLeaveHint(activity: Activity?) {
        if (!active() || activity?.javaClass?.name != FEED) return
        // Diagnostic only. This callback is also sent for in-app navigation.
        LogUtil.info("后台切集守护: 刷剧 onUserLeaveHint" +
            " taskVisible=" + taskVisible(activity) +
            " importance=" + processImportance() +
            " previouslyBackground=" + taskBackgrounded)
    }

    fun onLaunchRequested(activity: Activity?, intent: Intent?) {
        if (!active() || activity == null || intent == null) return
        val destination = intent.component?.className
        if (destination != SERIES || !taskBackgrounded) return
        LogUtil.info("后台切集守护: 捕获红果原生全集 Activity 启动" +
            " caller=" + activity.javaClass.simpleName +
            " taskVisible=" + taskVisible(activity) +
            " importance=" + processImportance())
        LogUtil.incr("systemMediaSeriesBackgroundLaunchObserved")
    }

    fun onActivityCreated(activity: Activity?) {
        if (!active() || activity?.javaClass?.name != SERIES ||
            activity.isFinishing || !taskBackgrounded) return
        val source = sourceActivity.get() ?: return
        if (source.taskId != activity.taskId) {
            // Never move another task. The background latch lasts until an
            // explicit resume, not until an arbitrary video-length timeout.
            return
        }
        val intent = activity.intent
        if (intent?.action == Intent.ACTION_VIEW ||
            intent?.hasCategory(Intent.CATEGORY_BROWSABLE) == true ||
            intent?.getBooleanExtra("xhongguo_return_to_video", false) == true) {
            LogUtil.info("后台切集守护: 外部用户打开全集，允许前台")
            taskBackgrounded = false
            return
        }
        pendingSeriesActivity = WeakReference(activity)
        pendingSeriesAt = SystemClock.uptimeMillis()
        pendingRelocation = true
        LogUtil.info("后台切集守护: 后台创建原生全集 Activity" +
            " taskVisible=" + taskVisible(activity) +
            " importance=" + processImportance())
        LogUtil.incr("systemMediaSeriesBackgroundCreated")
    }

    /**
     * Returns true only for the first resume of the just-created background
     * series activity. The caller uses this to preserve the media bridge's
     * background state instead of treating it as an explicit user return.
     */
    fun onActivityResumed(activity: Activity?): Boolean {
        if (!active() || activity == null) return false
        val sameAutoSeries = pendingRelocation &&
            pendingSeriesActivity.get() === activity &&
            SystemClock.uptimeMillis() - pendingSeriesAt in 0L..10_000L

        if (!sameAutoSeries) {
            if (taskBackgrounded) {
                taskBackgrounded = false
                pendingRelocation = false
                pendingSeriesActivity.clear()
                LogUtil.info("后台切集守护: 用户正常返回前台" +
                    " activity=" + activity.javaClass.simpleName +
                    " taskVisible=" + taskVisible(activity))
            }
            return false
        }
        pendingRelocation = false
        val visibility = taskVisible(activity)
        var moved = false
        if (visibility != false) {
            try {
                moved = activity.moveTaskToBack(true)
            } catch (e: Throwable) {
                LogUtil.warn("后台切集守护: 恢复任务后台失败 " +
                    e.javaClass.simpleName + ": " + e.message)
            }
        }
        lastFocusReason = "background-series-resume"
        LogUtil.info("后台切集守护: 自动全集切换不允许抢前台" +
            " originalVisible=" + visibility +
            " moveTaskToBack=" + moved +
            " importance=" + processImportance())
        LogUtil.incr("systemMediaSeriesForegroundGuardApplied")
        // A window can become focused slightly after onResume. One bounded
        // check covers that race, but must not keep forcing the task back.
        val owner = WeakReference(activity)
        main.postDelayed({
            val target = owner.get() ?: return@postDelayed
            if (!active() || target.isDestroyed || target.isFinishing ||
                !taskBackgrounded || lastFocusReason != "background-series-resume")
                return@postDelayed
            if (taskVisible(target) == true && target.hasWindowFocus()) {
                val retry = try { target.moveTaskToBack(true) }
                    catch (_: Throwable) { false }
                LogUtil.info("后台切集守护: 原生窗口延迟获得焦点，单次退回后台" +
                    " moved=" + retry)
                LogUtil.incr("systemMediaSeriesFocusRetry")
            }
        }, 180L)
        pendingSeriesActivity.clear()
        return true
    }
}
