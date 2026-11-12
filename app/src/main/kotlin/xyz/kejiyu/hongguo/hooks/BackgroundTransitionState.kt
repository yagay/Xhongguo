package xyz.kejiyu.hongguo.hooks

import java.lang.ref.WeakReference

/**
 * One transition state for Home, recents and direct app switching.
 *
 * Android may omit onUserLeaveHint. The pre-pause callback creates a short
 * provisional protection window, while only a later process-importance +
 * onStop check is allowed to confirm background mode.
 *
 * This class never initiates playback or owns a decoder. It keeps weak host
 * references and can be tested on the JVM without Android dependencies.
 */
internal class BackgroundTransitionState {
    private enum class Phase { IDLE, PRE_PAUSE, BACKGROUND }
    private var phase = Phase.IDLE
    private var activity = WeakReference<Any>(null)
    private var engine = WeakReference<Any>(null)
    private var since = 0L
    private var pauseEpoch = 0L
    private var heldCallbacks = 0
    private var transitionId = 0L

    @Synchronized fun begin(
        ownerActivity: Any, ownerEngine: Any, wasPlaying: Boolean,
        explicitPauseEpoch: Long, nowMs: Long
    ): Boolean {
        cancel()
        if (!wasPlaying || nowMs <= 0L) return false
        transitionId++
        activity = WeakReference(ownerActivity)
        engine = WeakReference(ownerEngine)
        since = nowMs
        pauseEpoch = explicitPauseEpoch
        phase = Phase.PRE_PAUSE
        return true
    }

    @Synchronized fun canHold(
        ownerActivity: Any?, ownerEngine: Any?,
        explicitPauseEpoch: Long, explicitCommand: Boolean,
        ownershipSwitch: Boolean, nowMs: Long
    ): Boolean {
        if (explicitCommand || ownershipSwitch ||
            explicitPauseEpoch != pauseEpoch ||
            ownerActivity == null || ownerActivity !== activity.get() ||
            ownerEngine == null || ownerEngine !== engine.get()) return false
        return when (phase) {
            Phase.PRE_PAUSE -> nowMs - since in 0L..3500L
            Phase.BACKGROUND -> false // separate confirmed-background guard
            Phase.IDLE -> false
        }
    }

    /** The native VideoContext release may happen inside Activity.onStop
     * AFTER process-state confirmation, depending on Android dispatch order.
     * Allow this one narrow cleanup guard in either transition phase.
     */
    @Synchronized fun canKeepPreparedOnStop(
        ownerActivity: Any?, ownerEngine: Any?,
        explicitPauseEpoch: Long, explicitCommand: Boolean,
        ownershipSwitch: Boolean, nowMs: Long
    ): Boolean {
        if (explicitCommand || ownershipSwitch ||
            explicitPauseEpoch != pauseEpoch ||
            ownerActivity == null || ownerActivity !== activity.get() ||
            ownerEngine == null || ownerEngine !== engine.get()) return false
        return when (phase) {
            Phase.PRE_PAUSE -> nowMs - since in 0L..3500L
            Phase.BACKGROUND -> nowMs - since in 0L..6000L
            Phase.IDLE -> false
        }
    }

    @Synchronized fun recordHeld() {
        if (phase == Phase.PRE_PAUSE || phase == Phase.BACKGROUND)
            heldCallbacks++
    }

    @Synchronized fun confirm(
        ownerActivity: Any?, ownerEngine: Any?,
        explicitPauseEpoch: Long, nowMs: Long
    ): Boolean {
        if (phase != Phase.PRE_PAUSE ||
            ownerActivity !== activity.get() || ownerEngine !== engine.get() ||
            explicitPauseEpoch != pauseEpoch ||
            nowMs - since !in 0L..4800L) return false
        phase = Phase.BACKGROUND
        return true
    }

    @Synchronized fun heldFor(ownerEngine: Any?): Boolean =
        phase == Phase.BACKGROUND && ownerEngine != null &&
            engine.get() === ownerEngine && heldCallbacks > 0

    @Synchronized fun isProvisional(): Boolean = phase == Phase.PRE_PAUSE
    @Synchronized fun heldCount(): Int = heldCallbacks
    @Synchronized fun currentId(): Long = transitionId

    @Synchronized fun cancel() {
        phase = Phase.IDLE
        activity.clear()
        engine.clear()
        since = 0L
        pauseEpoch = 0L
        heldCallbacks = 0
    }
}
