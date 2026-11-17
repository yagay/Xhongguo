package xyz.kejiyu.hongguo.hooks

import java.lang.ref.WeakReference

/**
 * One source of truth for a host-controlled short-series episode.
 *
 * The native adapter chooses the video, the native x05.w owns the engine,
 * and confirmed engine progress (NOT play()) establishes the new owner.
 * This reducer never calls a host method or writes a view.
 */
internal class NativeSeriesV3State {
    enum class Phase { IDLE, COMPLETED, SELECTED, BOUND, STARTING, PLAYING, PAUSED, FAILED }
    data class Snapshot(
        val generation: Long, val phase: Phase,
        val businessIndex: Int?, val playingIndex: Int?,
        val pendingIndex: Int?, val error: String?
    )

    private var generation = 0L
    private var phase = Phase.IDLE
    private var businessIndex: Int? = null
    private var playingIndex: Int? = null
    private var pendingIndex: Int? = null
    private var error: String? = null
    private var adapter = WeakReference<Any>(null)
    private var activeEngine = WeakReference<Any>(null)
    private var finishedEngine = WeakReference<Any>(null)
    private var nextHolder = WeakReference<Any>(null)
    private var nextEngine = WeakReference<Any>(null)
    private var baselineMs: Long? = null
    private var pauseGeneration = 0L

    @Synchronized fun snapshot() = Snapshot(
        generation, phase, businessIndex, playingIndex, pendingIndex, error)

    @Synchronized fun rememberAdapter(owner: Any) {
        if (adapter.get() !== owner) {
            adapter = WeakReference(owner)
            businessIndex = null
            pendingIndex = null
            nextHolder.clear()
            nextEngine.clear()
            baselineMs = null
            if (phase != Phase.COMPLETED) phase = Phase.IDLE
        }
    }

    @Synchronized fun complete(engine: Any): Long {
        // x05.w.onCompletion() is authoritative; never trust the resettable
        // legacy BackgroundEpisodeMonitor's transient completion flag.
        generation++
        finishedEngine = WeakReference(engine)
        activeEngine = WeakReference(engine)
        pendingIndex = null
        nextHolder.clear()
        nextEngine.clear()
        baselineMs = null
        error = null
        phase = Phase.COMPLETED
        return generation
    }

    /**
     * The host's ViewPager2 is allowed to be one item AHEAD of adapter.D.
     * This is the exact observed failing order at 20:00:49 (D=1, pager=2).
     * Neither case authorizes a second pager move.
     */
    @Synchronized fun mayScopeNext(
        owner: Any, target: Int, d: Int, pager: Int,
        count: Int, background: Boolean, explicitlyPaused: Boolean
    ): Boolean =
        !explicitlyPaused && background &&
        adapter.get() === owner &&
        phase == Phase.COMPLETED && finishedEngine.get() != null &&
        target == d + 1 && d >= 0 && target in 0 until count &&
        (pager == d || pager == target)

    @Synchronized fun select(
        owner: Any, target: Int, result: Boolean,
        d: Int, pager: Int
    ): Boolean {
        if (adapter.get() !== owner || !result || d != target ||
            pager != target || target !in 0..4999) return false
        businessIndex = target
        pendingIndex = target
        nextHolder.clear()
        nextEngine.clear()
        baselineMs = null
        error = null
        phase = Phase.SELECTED
        return true
    }

    @Synchronized fun bind(owner: Any, holder: Any, engine: Any): Boolean {
        if (adapter.get() !== owner || pendingIndex == null ||
            phase !in setOf(Phase.SELECTED, Phase.BOUND, Phase.STARTING))
            return false
        nextHolder = WeakReference(holder)
        nextEngine = WeakReference(engine)
        if (phase != Phase.STARTING) phase = Phase.BOUND
        return true
    }

    @Synchronized fun pendingHolder(): Any? = nextHolder.get()
    @Synchronized fun pendingEngine(): Any? = nextEngine.get()
    @Synchronized fun isPendingEngine(engine: Any?): Boolean =
        engine != null && nextEngine.get() === engine && pendingIndex != null

    @Synchronized fun playCommand(engine: Any): Boolean {
        if (!isPendingEngine(engine)) return false
        phase = Phase.STARTING
        baselineMs = null
        return true
    }

    @Synchronized fun confirmProgress(
        engine: Any, position: Long, duration: Long
    ): Boolean {
        if (!isPendingEngine(engine) ||
            phase !in setOf(Phase.BOUND, Phase.STARTING) ||
            duration !in 1000L..86_400_000L ||
            position < 0L || position > duration + 1000L) return false
        val baseline = baselineMs
        if (baseline == null || position < baseline) {
            baselineMs = position
            return false
        }
        if (position - baseline < 350L) return false
        playingIndex = pendingIndex
        pendingIndex = null
        activeEngine = WeakReference(engine)
        finishedEngine.clear()
        nextEngine.clear()
        nextHolder.clear()
        baselineMs = null
        phase = Phase.PLAYING
        return true
    }

    @Synchronized fun onError(engine: Any, cause: String) {
        if (isPendingEngine(engine)) {
            error = cause.take(180)
            phase = Phase.FAILED
        }
    }

    @Synchronized fun onPause(engine: Any?, user: Boolean) {
        if (user) {
            pauseGeneration++
            pendingIndex = null
            nextEngine.clear()
            nextHolder.clear()
            baselineMs = null
            phase = Phase.PAUSED
        } else if (engine != null && activeEngine.get() === engine &&
            phase == Phase.PLAYING) phase = Phase.PAUSED
    }

    @Synchronized fun isHandoff(): Boolean = phase in setOf(
        Phase.COMPLETED, Phase.SELECTED, Phase.BOUND, Phase.STARTING)

    @Synchronized fun canManualSkip(): Boolean =
        phase == Phase.IDLE || phase == Phase.PLAYING || phase == Phase.PAUSED

    @Synchronized fun reset() {
        generation++
        phase = Phase.IDLE
        businessIndex = null
        playingIndex = null
        pendingIndex = null
        error = null
        baselineMs = null
        adapter.clear()
        activeEngine.clear()
        finishedEngine.clear()
        nextHolder.clear()
        nextEngine.clear()
    }
}
