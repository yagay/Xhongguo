package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import xyz.kejiyu.hongguo.LogUtil

/**
 * Playback ownership, ported as a POLICY from hongguo-android's
 * rewrite-web-native BackgroundPlaybackService/PlaybackNavigation.
 *
 * The official host has multiple TTVideoEngine instances; unlike Media3
 * they cannot safely share one decoder or replace its source. We therefore
 * enforce exactly one PLAYING owner, keeping old engines intact for back
 * navigation but paused. A mini view is a surface for that owner, not a
 * second playback session.
 *
 * No obfuscated class or method names, no second player or media source.
 */
internal object SinglePlaybackOwner {
    private enum class Route { FEED, SERIES, DETAILS, OTHER }
    @Volatile private var owner = WeakReference<Any>(null)
    @Volatile private var route = Route.OTHER
    @Volatile private var ownerRoute = Route.OTHER
    private val routes = WeakHashMap<Any, Route>()
    private val transitioning = ThreadLocal<Boolean>()
    private var lastHandoffAt = 0L

    fun isOwnershipTransition(): Boolean = transitioning.get() == true

    private fun routeOf(activity: Activity?): Route = when (activity?.javaClass?.name) {
        "com.dragon.read.pages.main.MainFragmentActivity" -> Route.FEED
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity" -> Route.SERIES
        "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
        "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity" ->
            Route.DETAILS
        else -> Route.OTHER
    }

    private fun playing(engine: Any?): Boolean? {
        if (engine == null) return null
        return try {
            engine.javaClass.methods.firstOrNull {
                it.name == "isPlaying" && it.parameterCount == 0
            }?.invoke(engine) as? Boolean
        } catch (_: Throwable) { null }
    }

    /** Native Activity navigation is the presentation route, not a new media
     * owner. Detail pages keep the original video; full-series/feed switches
     * retire the previous owner before the new page can start another. */
    @Synchronized fun onActivityResuming(activity: Activity?) {
        val next = routeOf(activity)
        if (next == Route.OTHER || next == route) return
        val previous = owner.get()
        val oldRoute = ownerRoute
        route = next
        if (next == Route.DETAILS) {
            LogUtil.info("单播放器: 进入详情页，保留当前原生播放所有者")
            LogUtil.incr("singlePlaybackDetailRetained")
            return
        }
        if (previous != null && oldRoute != Route.OTHER && oldRoute != next) {
            pauseCurrent(previous, "navigate:$oldRoute->$next")
            LogUtil.incr("singlePlaybackRouteHandoff")
        }
        LogUtil.info("单播放器: 前台播放路由=$next old=$oldRoute")
    }

    /**
     * Called BEFORE a TTVideoEngine.play() invocation.
     * Reject a known engine from an inactive screen rather than allow its
     * lifecycle callback to steal audio back after another screen resumed.
     */
    fun shouldBlockPlay(engine: Any?): Boolean {
        if (engine == null || isOwnershipTransition()) return false
        val recorded = synchronized(routes) { routes[engine] }
        if (route == Route.DETAILS) {
            val active = owner.get()
            if (active != null && engine !== active) {
                LogUtil.info("单播放器: 阻止详情页非当前播放器自动播放")
                LogUtil.incr("singlePlaybackStalePlayBlocked")
                return true
            }
            return false
        }
        if (route == Route.OTHER) return false
        if (recorded != null && recorded != Route.OTHER &&
            recorded != route) {
            LogUtil.info("单播放器: 阻止旧页面播放器重新抢占" +
                " from=$recorded current=$route")
            LogUtil.incr("singlePlaybackStalePlayBlocked")
            return true
        }
        return false
    }

    /** Called AFTER successful native play. One owner at a time. */
    @Synchronized fun onEnginePlayed(engine: Any?) {
        if (engine?.javaClass?.name != "com.ss.ttvideoengine.TTVideoEngine") return
        val old = owner.get()
        if (old === engine) return
        // A details screen is a surface-only route, not a new owner.
        if (route == Route.DETAILS && old != null) return
        val nextRoute = if (route == Route.DETAILS) ownerRoute else route
        val withinSamePage = ownerRoute == nextRoute &&
            nextRoute != Route.OTHER && nextRoute != Route.DETAILS
        owner = WeakReference(engine)
        ownerRoute = nextRoute
        synchronized(routes) { routes[engine] = nextRoute }
        if (old != null && old !== engine) {
            // Same-page episode advancement keeps one mini surface; only
            // a route change must restore the old screen's mini overlay.
            InAppMiniPlayer.onPlaybackOwnerChanged(old, engine, withinSamePage)
            pauseCurrent(old, "engine-handoff")
            LogUtil.incr("singlePlaybackEngineHandoff")
        }
        lastHandoffAt = SystemClock.uptimeMillis()
        LogUtil.info("单播放器: 已接管播放权" +
            " route=$ownerRoute replaced=" + (old != null) +
            " playing=" + (playing(engine)?.toString() ?: "unknown"))
    }

    private fun pauseCurrent(engine: Any, reason: String) {
        if (isOwnershipTransition()) return
        try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == "pause" && it.parameterCount == 0 &&
                    it.returnType == Void.TYPE
            } ?: run {
                LogUtil.warn("单播放器: 旧引擎缺少安全的 pause()")
                return
            }
            transitioning.set(true)
            try { method.invoke(engine) }
            finally { transitioning.remove() }
            LogUtil.info("单播放器: 旧引擎已暂停 reason=$reason")
            LogUtil.incr("singlePlaybackOldPaused")
        } catch (e: Throwable) {
            LogUtil.warn("单播放器: 暂停旧引擎失败 " + e.javaClass.simpleName)
        }
    }

    fun isOwner(engine: Any?): Boolean = engine != null && owner.get() === engine

    /** Called at release, never drop the current owner when an older engine
     * is released asynchronously. */
    @Synchronized fun onEngineReleased(engine: Any?) {
        if (engine == null) return
        if (owner.get() === engine) {
            owner.clear()
            ownerRoute = Route.OTHER
        }
        synchronized(routes) { routes.remove(engine) }
    }
}
