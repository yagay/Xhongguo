package xyz.kejiyu.hongguo.hooks

import java.lang.ref.WeakReference

/**
 * One event-driven native-series lifecycle. No ViewPager mutation, URL
 * fabrication, timer-based re-selection or TTVideoEngine.play() invocation.
 * Kotlin-only to allow deterministic regression tests.
 */
internal class OfficialSeriesTimeline {
    enum class Phase { IDLE, COMPLETED, SELECTED, BOUND, STARTING, PLAYING, PAUSED, FAILED }

    data class Snapshot(
        val generation: Long,
        val phase: Phase,
        val selectedIndex: Int?,
        val playingIndex: Int?,
        val pendingIndex: Int?,
        val error: String?
    )

    private var generation = 0L
    private var phase = Phase.IDLE
    private var selectedIndex: Int? = null
    private var playingIndex: Int? = null
    private var pendingIndex: Int? = null
    private var lastError: String? = null
    private var selectedAdapter = WeakReference<Any>(null)
    private var playingEngine = WeakReference<Any>(null)
    private var finishedEngine = WeakReference<Any>(null)
    private var nextHolder = WeakReference<Any>(null)
    private var nextEngine = WeakReference<Any>(null)
    private var positionBaseline: Long? = null
    private var firstPlayObserved = false

    @Synchronized fun snapshot(): Snapshot = Snapshot(
        generation, phase, selectedIndex, playingIndex, pendingIndex, lastError)

    @Synchronized fun rememberAdapter(adapter: Any) {
        if (selectedAdapter.get() !== adapter) {
            selectedAdapter = WeakReference(adapter)
            // Changing the native adapter invalidates the previous selection,
            // but does not stop or release the host's media engine.
            if (phase != Phase.COMPLETED) {
                selectedIndex = null
                pendingIndex = null
            }
        }
    }

    @Synchronized fun onCompletion(engine: Any) {
        // x05.w.onCompletion is the original series player's own callback;
        // do not rely on a resettable progress-stall monitor for EOF.
        generation++
        finishedEngine = WeakReference(engine)
        playingEngine = WeakReference(engine)
        firstPlayObserved = false
        pendingIndex = null
        positionBaseline = null
        lastError = null
        phase = Phase.COMPLETED
    }

    @Synchronized fun maySelectAfterCompletion(adapter: Any, target: Int, current: Int): Boolean =
        phase == Phase.COMPLETED && finishedEngine.get() != null &&
            selectedAdapter.get() === adapter &&
            target == current + 1 && current >= 0

    @Synchronized fun onSelected(
        adapter: Any, target: Int, accepted: Boolean,
        businessIndex: Int, pagerIndex: Int
    ): Boolean {
        if (!accepted || businessIndex != target || pagerIndex != target ||
            target !in 0..4999 || selectedAdapter.get() !== adapter) return false
        selectedIndex = target
        pendingIndex = target
        nextHolder.clear()
        nextEngine.clear()
        positionBaseline = null
        lastError = null
        phase = Phase.SELECTED
        return true
    }

    @Synchronized fun onBound(adapter: Any, holder: Any, engine: Any): Boolean {
        if (selectedAdapter.get() !== adapter || pendingIndex == null ||
            engine === finishedEngine.get() || phase !in setOf(
                Phase.SELECTED, Phase.BOUND, Phase.STARTING)) return false
        nextHolder = WeakReference(holder)
        nextEngine = WeakReference(engine)
        if (phase != Phase.STARTING) phase = Phase.BOUND
        return true
    }

    @Synchronized fun pendingHolder(): Any? = nextHolder.get()
    @Synchronized fun pendingEngine(): Any? = nextEngine.get()
    @Synchronized fun isPendingEngine(engine: Any?): Boolean =
        engine != null && pendingIndex != null && nextEngine.get() === engine

    @Synchronized fun isPendingHandoff(): Boolean =
        phase == Phase.COMPLETED || phase == Phase.SELECTED ||
            phase == Phase.BOUND || phase == Phase.STARTING

    @Synchronized fun onPlayCommand(engine: Any): Boolean {
        // Only an already selected AND bound holder may be marked starting.
        // play() returned with playbackState=0 is not audible playback.
        if (!isPendingEngine(engine)) return false
        phase = Phase.STARTING
        firstPlayObserved = true
        positionBaseline = null
        return true
    }

    @Synchronized fun onProgress(engine: Any, positionMs: Long, durationMs: Long): Boolean {
        if (!isPendingEngine(engine) ||
            phase !in setOf(Phase.BOUND, Phase.STARTING) ||
            durationMs !in 1000L..86_400_000L ||
            positionMs < 0 || positionMs > durationMs + 1000L) return false
        val previous = positionBaseline
        if (previous == null || positionMs < previous) {
            positionBaseline = positionMs
            return false
        }
        if (positionMs - previous < 350L) return false
        playingEngine = WeakReference(engine)
        finishedEngine.clear()
        playingIndex = pendingIndex
        pendingIndex = null
        nextHolder.clear()
        nextEngine.clear()
        positionBaseline = null
        phase = Phase.PLAYING
        return true
    }

    @Synchronized fun onPause(engine: Any, userInitiated: Boolean) {
        if (userInitiated) {
            pendingIndex = null
            nextHolder.clear()
            nextEngine.clear()
            positionBaseline = null
            phase = Phase.PAUSED
        } else if (playingEngine.get() === engine && phase == Phase.PLAYING) {
            phase = Phase.PAUSED
        }
    }

    @Synchronized fun onError(engine: Any, reason: String) {
        if (isPendingEngine(engine)) {
            lastError = reason.take(150)
            phase = Phase.FAILED
        }
    }

    @Synchronized fun canDispatchEpisodeCommand(): Boolean =
        phase == Phase.PLAYING || phase == Phase.PAUSED || phase == Phase.IDLE

    @Synchronized fun reset() {
        generation++
        phase = Phase.IDLE
        selectedIndex = null
        playingIndex = null
        pendingIndex = null
        lastError = null
        firstPlayObserved = false
        positionBaseline = null
        selectedAdapter.clear()
        playingEngine.clear()
        finishedEngine.clear()
        nextHolder.clear()
        nextEngine.clear()
    }
}
