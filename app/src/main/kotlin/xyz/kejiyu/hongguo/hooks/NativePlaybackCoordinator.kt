package xyz.kejiyu.hongguo.hooks

import java.lang.ref.WeakReference

/**
 * One native playback identity/state per host process. There are no timers,
 * guessed URLs, synthetic page changes or media-session controls here.
 * Events come from real native business boundaries, and consumers read
 * the SAME confirmed episode and playing state.
 */
internal class NativePlaybackCoordinator {
    enum class Phase { IDLE, PLAYING, PAUSED, COMPLETED, SELECTED, BOUND }

    data class Snapshot(
        val generation: Long,
        val phase: Phase,
        val selectedIndex: Int?,
        val playingIndex: Int?,
        val pendingIndex: Int?,
        val explicitPauseEpoch: Long
    )

    private var generation = 0L
    private var phase = Phase.IDLE
    private var selectedIndex: Int? = null
    private var playingIndex: Int? = null
    private var pendingIndex: Int? = null
    private var explicitlyPaused = false
    private var pauseEpoch = 0L
    private var activeEngine = WeakReference<Any>(null)
    private var finishedEngine = WeakReference<Any>(null)
    private var selectedAdapter = WeakReference<Any>(null)
    private var boundNextEngine = WeakReference<Any>(null)
    private var boundNextHolder = WeakReference<Any>(null)

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        generation, phase, selectedIndex, playingIndex, pendingIndex,
        pauseEpoch
    )

    @Synchronized
    fun onNativeSelection(adapter: Any, index: Int, accepted: Boolean) {
        if (!accepted || index !in 0..4999) return
        if (selectedAdapter.get() !== adapter) {
            selectedAdapter = WeakReference(adapter)
            selectedIndex = null
            playingIndex = null
        }
        selectedIndex = index
        if (index != playingIndex && pendingIndex != index) {
            pendingIndex = index
            boundNextEngine.clear()
            boundNextHolder.clear()
            phase = Phase.SELECTED
        }
    }

    @Synchronized
    fun onNativeHolderBound(holder: Any, engine: Any?) {
        if (pendingIndex == null || engine == null || explicitlyPaused) return
        boundNextHolder = WeakReference(holder)
        boundNextEngine = WeakReference(engine)
        // TTVideoEngine.play() can run from within x05.w.L() BEFORE the
        // hook's after-callback reports the binding. Confirm that late
        // binding rather than losing the newly playing episode.
        if (activeEngine.get() === engine &&
            (phase == Phase.PLAYING ||
                // Initial playback may be observed before its native
                // adapter has reported the current position. This is NOT
                // valid after an EOF from a reused engine.
                (playingIndex == null && finishedEngine.get() == null))) {
            playingIndex = pendingIndex
            pendingIndex = null
            finishedEngine.clear()
            boundNextEngine.clear()
            boundNextHolder.clear()
        } else phase = Phase.BOUND
    }

    @Synchronized
    fun onNativeCompletion(engine: Any) {
        if (activeEngine.get() !== engine) return
        // The old engine remains identified as finished until a real play
        // event occurs. Never declare a selected next episode "playing".
        if (finishedEngine.get() === engine && phase == Phase.COMPLETED)
            return
        generation++
        finishedEngine = WeakReference(engine)
        phase = Phase.COMPLETED
    }

    @Synchronized
    fun onNativePlay(engine: Any) {
        val previous = activeEngine.get()
        val distinct = previous !== engine
        val pending = pendingIndex
        // The candidate is the chosen native holder's engine, not merely
        // an engine seen during preloading or from another screen.
        val nextConfirmed = pending != null &&
            boundNextEngine.get() === engine && !explicitlyPaused
        if (distinct) generation++
        activeEngine = WeakReference(engine)
        if (nextConfirmed) {
            playingIndex = pending
            pendingIndex = null
            finishedEngine.clear()
            boundNextEngine.clear()
            boundNextHolder.clear()
        } else if (distinct) {
            // The bind callback can arrive AFTER play() in the same
            // synchronous x05.w.L() stack. Keep only the pending target
            // until its exact holder is verified; never publish it yet.
            playingIndex = null
            finishedEngine.clear()
        }
        explicitlyPaused = false
        phase = Phase.PLAYING
    }

    @Synchronized
    fun onNativePause(engine: Any, userInitiated: Boolean) {
        if (activeEngine.get() !== engine) return
        if (userInitiated) {
            explicitlyPaused = true
            pauseEpoch++
            pendingIndex = null
            boundNextHolder.clear()
            boundNextEngine.clear()
        }
        if (phase != Phase.COMPLETED && phase != Phase.SELECTED &&
            phase != Phase.BOUND) phase = Phase.PAUSED
    }

    @Synchronized
    fun onExplicitPause() {
        explicitlyPaused = true
        pauseEpoch++
        pendingIndex = null
        boundNextHolder.clear()
        boundNextEngine.clear()
        if (phase != Phase.IDLE) phase = Phase.PAUSED
    }

    @Synchronized
    fun verifiedPlayingIndex(engine: Any?, adapter: Any?): Int? =
        if (engine != null && adapter != null &&
            activeEngine.get() === engine &&
            selectedAdapter.get() === adapter &&
            phase == Phase.PLAYING) playingIndex
        else null

    @Synchronized
    fun canDispatchEpisodeCommand(): Boolean =
        phase != Phase.COMPLETED && phase != Phase.IDLE

    /** Only the already accepted host selection can have a one-shot
     * continuation. Never permit a second transition after new play(). */
    @Synchronized
    fun canContinueAcceptedSelection(
        adapter: Any?, targetIndex: Int, completedEngine: Any?
    ): Boolean =
        !explicitlyPaused &&
        (phase == Phase.SELECTED || phase == Phase.BOUND) &&
        pendingIndex == targetIndex &&
        selectedAdapter.get() === adapter &&
        activeEngine.get() === completedEngine &&
        finishedEngine.get() === completedEngine

    @Synchronized
    fun reset() {
        generation++
        phase = Phase.IDLE
        selectedIndex = null
        playingIndex = null
        pendingIndex = null
        explicitlyPaused = false
        activeEngine.clear()
        finishedEngine.clear()
        selectedAdapter.clear()
        boundNextEngine.clear()
        boundNextHolder.clear()
    }
}
