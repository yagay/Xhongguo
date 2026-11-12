package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTransitionStateTest {
    @Test fun homeAndDirectAppSwitchUseTheSamePrePausePath() {
        val activity = Any()
        val engine = Any()
        val state = BackgroundTransitionState()
        // No onUserLeaveHint is required: ActivityPrePaused alone arms it.
        assertTrue(state.begin(activity, engine, true, 2L, 1000L))
        assertTrue(state.canHold(activity, engine, 2L, false, false, 1050L))
        state.recordHeld()
        assertTrue(state.confirm(activity, engine, 2L, 2300L))
        assertTrue(state.heldFor(engine))
        assertEquals(1, state.heldCount())
        assertFalse(state.canHold(activity, engine, 2L, false, false, 2500L))
    }

    @Test fun explicitPauseAndDifferentEngineCannotBeSuppressed() {
        val activity = Any()
        val engine = Any()
        val state = BackgroundTransitionState()
        state.begin(activity, engine, true, 0L, 1000L)
        assertFalse(state.canHold(activity, engine, 1L, false, false, 1100L))
        assertFalse(state.canHold(activity, engine, 0L, true, false, 1100L))
        assertFalse(state.canHold(activity, engine, 0L, false, true, 1100L))
        assertFalse(state.canHold(activity, Any(), 0L, false, false, 1100L))
        assertFalse(state.canHold(Any(), engine, 0L, false, false, 1100L))
        assertFalse(state.confirm(activity, engine, 1L, 1300L))
    }

    @Test fun nativeCleanupGuardExpiresAndInAppNavigationCancelsIt() {
        val activity = Any()
        val engine = Any()
        val state = BackgroundTransitionState()
        state.begin(activity, engine, true, 0L, 1000L)
        assertFalse(state.canHold(activity, engine, 0L, false, false, 4800L))
        state.cancel() // another Hongguo Activity starts
        assertFalse(state.canHold(activity, engine, 0L, false, false, 1200L))
        assertFalse(state.confirm(activity, engine, 0L, 1400L))
        assertEquals(0, state.heldCount())
    }

    @Test fun preparedControllerGuardWorksBeforeAndAfterBackgroundConfirmation() {
        val activity = Any()
        val engine = Any()
        val state = BackgroundTransitionState()
        state.begin(activity, engine, true, 7L, 1000L)
        assertTrue(state.canKeepPreparedOnStop(
            activity, engine, 7L, false, false, 1350L))
        assertTrue(state.confirm(activity, engine, 7L, 1800L))
        // Some Android versions confirm process importance before the
        // host dispatches onStop() and its prepared-controller cleanup.
        assertTrue(state.canKeepPreparedOnStop(
            activity, engine, 7L, false, false, 2400L))
        assertFalse(state.canKeepPreparedOnStop(
            activity, engine, 7L, true, false, 2400L))
        assertFalse(state.canKeepPreparedOnStop(
            activity, engine, 8L, false, false, 2400L))
        assertFalse(state.canKeepPreparedOnStop(
            activity, engine, 7L, false, false, 8000L))
        state.cancel()
        assertFalse(state.canKeepPreparedOnStop(
            activity, engine, 7L, false, false, 2600L))
    }

    @Test fun stoppedOrUserPausedPlaybackDoesNotArmProvisionalProtection() {
        val state = BackgroundTransitionState()
        assertFalse(state.begin(Any(), Any(), false, 0L, 1000L))
        assertFalse(state.isProvisional())
        assertFalse(state.begin(Any(), Any(), true, 0L, 0L))
        assertFalse(state.isProvisional())
    }
}
