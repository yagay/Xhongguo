package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * An independent, fail-closed native episode navigator.
 *
 * Do not seek, call TTVideoEngine.play, recreate Activities, synthesize
 * gestures, or replace the current playback engine.  Select a page only
 * through the real inner video list's ViewPager / RecyclerView, after its
 * selected position and adapter count match the native episode data list.
 * The existing VideoShop / TTVideoEngine observers confirm actual playback.
 */
internal object EpisodeNavigator {
    data class Controls(val previous: Boolean = false, val next: Boolean = false)

    private const val SERIES_ACTIVITY =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val TIMEOUT_MS = 8000L
    private val main = Handler(Looper.getMainLooper())

    private data class Target(
        val activity: Activity,
        val model: FullSeriesMediaInfo.NavigationCandidate,
        val view: Any,
        val type: String
    )
    private data class Pending(
        val activity: WeakReference<Activity>,
        val sourceId: String,
        val destinationId: String,
        val destinationIndex: Int,
        val started: Long,
        val direction: Int,
        var sawNativeStart: Boolean = false
    )
    private var pending: Pending? = null
    private var lastUnsupportedAt = 0L

    private fun call(obj: Any?, methodName: String, vararg params: Any): Any? {
        if (obj == null) return null
        return try {
            val method = obj.javaClass.methods.firstOrNull { method ->
                method.name == methodName && method.parameterCount == params.size &&
                    method.parameterTypes.indices.all { i ->
                        val type = method.parameterTypes[i]
                        val value = params[i]
                        if (type.isPrimitive) {
                            (type == Integer.TYPE && value is Int) ||
                            (type == java.lang.Boolean.TYPE && value is Boolean)
                        } else type.isInstance(value)
                    }
            } ?: return null
            method.invoke(obj, *params)
        } catch (_: Throwable) { null }
    }

    private fun extendsClass(obj: Any, target: String): Boolean {
        var type: Class<*>? = obj.javaClass
        while (type != null) {
            if (type.name == target) return true
            type = type.superclass
        }
        return false
    }

    private fun matchesPager(view: View, index: Int, count: Int): Boolean {
        val adapter = call(view, "getAdapter") ?: return false
        val adapterCount = (call(adapter, "getItemCount") as? Number)?.toInt()
            ?: (call(adapter, "getCount") as? Number)?.toInt()
        if (adapterCount != count) return false
        return (call(view, "getCurrentItem") as? Number)?.toInt() == index
    }

    private fun matchesRecycler(view: View, index: Int, count: Int): Boolean {
        val adapter = call(view, "getAdapter") ?: return false
        if ((call(adapter, "getItemCount") as? Number)?.toInt() != count)
            return false
        val manager = call(view, "getLayoutManager") ?: return false
        // A horizontally scrolling episode selector must never be mistaken
        // for the vertically scrolling video page.
        if ((call(manager, "getOrientation") as? Number)?.toInt() != 1)
            return false
        val selected = (call(manager, "findFirstCompletelyVisibleItemPosition")
            as? Number)?.toInt()
            ?: (call(manager, "findFirstVisibleItemPosition")
                as? Number)?.toInt()
        if (selected != index) return false
        return view.height >= 300 && view.width >= 200
    }

    private fun locate(activity: Activity, model: FullSeriesMediaInfo.NavigationCandidate): Target? {
        val fragmentView = call(model.fragment, "getView") as? View
            ?: return null
        val candidates = ArrayList<Target>()
        val queue = java.util.ArrayDeque<View>()
        queue.addLast(fragmentView)
        var scanned = 0
        while (queue.isNotEmpty() && scanned++ < 300) {
            val view = queue.removeFirst()
            if (extendsClass(view, "androidx.viewpager2.widget.ViewPager2") ||
                extendsClass(view, "androidx.viewpager.widget.ViewPager")) {
                if (matchesPager(view, model.currentIndex, model.listCount)) {
                    candidates.add(Target(activity, model, view, "pager"))
                }
            } else if (extendsClass(view, "androidx.recyclerview.widget.RecyclerView") &&
                matchesRecycler(view, model.currentIndex, model.listCount)) {
                candidates.add(Target(activity, model, view, "recycler"))
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) queue.addLast(view.getChildAt(i))
            }
        }
        // A matching ViewPager is authoritative; its internal RecyclerView
        // may also match. For an ambiguous set of independent page controls,
        // refuse to navigate rather than driving the wrong list.
        val pagers = candidates.filter { it.type == "pager" }
        return when {
            pagers.size == 1 -> pagers[0]
            pagers.size > 1 -> null
            candidates.size == 1 -> candidates[0]
            else -> null
        }
    }

    private fun resolve(activity: Activity?, engine: Any?): Target? {
        if (Looper.myLooper() != Looper.getMainLooper() ||
            activity?.javaClass?.name != SERIES_ACTIVITY || engine == null ||
            activity.isFinishing || activity.isDestroyed) return null
        val model = FullSeriesMediaInfo.navigationCandidate(activity, engine)
            ?: return null
        return locate(activity, model)
    }

    fun controls(activity: Activity?, engine: Any?): Controls {
        if (pending != null) return Controls()
        val target = resolve(activity, engine) ?: return Controls()
        return Controls(
            previous = target.model.previousVideoId != null,
            next = target.model.nextVideoId != null
        )
    }

    fun navigate(direction: Int, activity: Activity?, engine: Any?): Boolean {
        if (direction != 1 && direction != -1) return false
        if (pending != null) {
            LogUtil.info("系统媒体切集: 已有待确认指令，忽略重复按钮")
            return false
        }
        val target = resolve(activity, engine)
        if (target == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUnsupportedAt > 1500L) {
                lastUnsupportedAt = now
                LogUtil.warn("系统媒体切集: 未找到匹配当前原生剧集列表的导航组件")
                LogUtil.incr("systemMediaEpisodeNavigatorUnavailable")
            }
            return false
        }
        val destination = target.model.currentIndex + direction
        val destinationId = if (direction > 0) target.model.nextVideoId
            else target.model.previousVideoId
        if (destination !in 0 until target.model.listCount || destinationId == null) {
            LogUtil.info("系统媒体切集: 已到当前全集边界或目标视频未确认")
            return false
        }
        val p = Pending(WeakReference(target.activity), target.model.currentVideoId,
            destinationId, destination, SystemClock.uptimeMillis(), direction)
        pending = p
        val dispatched = try {
            when (target.type) {
                "pager" -> {
                    // Use the actual AndroidX pager's page-selected callback,
                    // not a synthetic touch event or direct engine call.
                    val method = target.view.javaClass.methods.firstOrNull {
                        it.name == "setCurrentItem" && it.parameterCount == 2 &&
                            it.parameterTypes[0] == Integer.TYPE &&
                            it.parameterTypes[1] == java.lang.Boolean.TYPE
                    }
                    if (method == null) false else {
                        method.invoke(target.view, destination, true)
                        true
                    }
                }
                "recycler" -> {
                    // This is only an exact, vertical, visible-position match
                    // against the SAME native episode list.
                    val method = target.view.javaClass.methods.firstOrNull {
                        it.name == "smoothScrollToPosition" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Integer.TYPE
                    }
                    if (method == null) false else {
                        method.invoke(target.view, destination)
                        true
                    }
                }
                else -> false
            }
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体切集: 原生导航抛出异常 " +
                e.javaClass.simpleName + ": " + e.message)
            false
        }
        if (!dispatched) {
            pending = null
            LogUtil.incr("systemMediaEpisodeDispatchFailed")
            return false
        }
        LogUtil.info("系统媒体切集: 已交给原生列表 " +
            "type=${target.type} direction=${if (direction > 0) "next" else "previous"}" +
            " sourceIndex=${target.model.currentIndex} targetIndex=$destination" +
            " seriesVerified=${target.model.seriesId != null}")
        LogUtil.incr("systemMediaEpisodeDispatched")
        // No auto-retry. Verify the target video is really playing before
        // announcing success or modifying the media card's episode metadata.
        for (delay in longArrayOf(650L, 1700L, 3600L, TIMEOUT_MS)) {
            main.postDelayed({ checkPending(p) }, delay)
        }
        return true
    }

    private fun checkPending(task: Pending) {
        if (pending !== task) return
        if (SystemClock.uptimeMillis() - task.started >= TIMEOUT_MS) {
            pending = null
            LogUtil.warn("系统媒体切集: 超时，未确认目标视频开始播放" +
                " targetIndex=${task.destinationIndex} nativeStart=${task.sawNativeStart}")
            LogUtil.incr("systemMediaEpisodeUnconfirmed")
            return
        }
        // While the native page is settling, the page index may advance
        // before its engine actually starts. Completion only happens in
        // onNativePlayback(true) with the exact target video ID.
    }

    /** Called by the existing native playback observer, not a new global Hook. */
    fun onNativePlayback(activity: Activity?, engine: Any?, playing: Boolean): Boolean {
        val task = pending ?: return false
        if (!playing || activity !== task.activity.get() || engine == null) return false
        val engineId = call(engine, "getVideoID")?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        if (engineId != task.destinationId || engineId == task.sourceId) return false
        task.sawNativeStart = true
        val candidate = FullSeriesMediaInfo.navigationCandidate(activity, engine)
        if (candidate == null || candidate.currentIndex != task.destinationIndex ||
            candidate.currentVideoId != task.destinationId) return false
        pending = null
        LogUtil.info("系统媒体切集: 原生播放已确认 " +
            "direction=${if (task.direction > 0) "next" else "previous"}" +
            " targetIndex=${task.destinationIndex}")
        LogUtil.incr("systemMediaEpisodeConfirmed")
        return true
    }

    fun reset() {
        pending = null
    }
}
