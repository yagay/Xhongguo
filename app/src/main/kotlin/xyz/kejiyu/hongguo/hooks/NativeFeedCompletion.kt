package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.ActivityManager
import android.os.SystemClock
import xyz.kejiyu.hongguo.FeatureTrace

/**
 * Observation of the actual Hongguo 7.3.5.32 short-video completion strategy.
 *
 * The supplied APK contains two distinct subclasses of adapter.a:
 * adapter.e.U1 -> e.p5 -> m15.l.U4 (next in native series) OR a.M0
 * (enter inner series), and adapter.f.U1 -> f.r5 / official listen mode.
 * They cannot safely be substituted for one another.
 *
 * This observer never changes return values, ActivityOptions, pager state,
 * playing engine, native audio-mode flags or host completion callbacks.
 * Its synchronous scopes correlate original host business calls and make
 * the correct native control path visible in an exported diagnostic.
 */
internal object NativeFeedCompletion {
    private data class Scope(
        val owner: Any,
        val route: String,
        val atMs: Long,
        var decision: String = "unresolved"
    )
    private val scope = ThreadLocal<Scope?>()

    private fun importance(): Int = try {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance
    } catch (_: Throwable) { -1 }

    private fun readIndex(owner: Any): Int? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val field = cls.getDeclaredField("D")
                field.isAccessible = true
                return (field.get(owner) as? Number)?.toInt()
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    private fun isBackground(): Boolean =
        importance() >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE

    fun begin(owner: Any?, route: String) {
        if (owner == null) return
        scope.set(Scope(owner, route, SystemClock.elapsedRealtime()))
        FeatureTrace.event("feed_native", "eof_policy", "observed",
            "phase=begin,route=$route,background=" + isBackground() +
                ",importance=" + importance() +
                ",businessIndex=" + (readIndex(owner) ?: -1))
    }

    fun decision(owner: Any?, action: String) {
        val active = scope.get() ?: return
        // The same adapter invokes e.p5, a.M0 and f.r5 on this call chain.
        // No state is adopted from a preloaded or unrelated adapter.
        if (active.owner !== owner) return
        active.decision = action
        FeatureTrace.event("feed_native", "original_decision", "observed",
            "route=" + active.route + ",action=$action" +
                ",background=" + isBackground() +
                ",businessIndex=" + (owner?.let(::readIndex) ?: -1))
    }

    fun staticNextEpisode() {
        val active = scope.get() ?: return
        active.decision = "official_episode_advance"
        FeatureTrace.event("feed_native", "original_decision", "observed",
            "route=" + active.route +
                ",action=official_episode_advance,background=" + isBackground())
    }

    fun listeningGate(original: Any?, activity: Activity?) {
        val active = scope.get() ?: return
        FeatureTrace.event("feed_native", "listening_gate", "observed",
            "route=" + active.route +
                ",listenMode=" + (original == true) +
                ",activityPresent=" + (activity != null) +
                ",background=" + isBackground())
    }

    fun end(owner: Any?, route: String) {
        val active = scope.get() ?: return
        if (owner !== active.owner || route != active.route) return
        scope.remove()
        FeatureTrace.event("feed_native", "eof_policy", "observed",
            "phase=end,route=$route,decision=" + active.decision +
                ",background=" + isBackground() +
                ",elapsedMs=" +
                (SystemClock.elapsedRealtime() - active.atMs).coerceAtLeast(0L))
    }
}
