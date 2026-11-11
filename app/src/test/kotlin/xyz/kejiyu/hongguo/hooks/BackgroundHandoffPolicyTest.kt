package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHandoffPolicyTest {
    @Test fun explicitUserLeavePreservesExactlyTheCurrentPlayingEngine() {
        assertTrue(BackgroundHandoffPolicy.protectOnUserLeave(
            mediaEnabled = true, wasPlaying = true,
            userPauseInProgress = false, ownershipTransition = false,
            samePinnedEngine = true, sameVideoActivity = true, elapsedMs = 900L))
    }

    @Test fun unrelatedNavigationOrUserPauseNeverGetsProtected() {
        for (wrongEngine in listOf(false, true)) {
            assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
                true, true, false, false, !wrongEngine, true, 4000L))
        }
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            true, true, true, false, true, true, 100L))
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            true, true, false, true, true, true, 100L))
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            true, false, false, false, true, true, 100L))
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            false, true, false, false, true, true, 100L))
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            true, true, false, false, true, false, 100L))
        assertFalse(BackgroundHandoffPolicy.protectOnUserLeave(
            true, true, false, false, true, true, 2700L))
    }

    @Test fun onlyPreservedEngineWithoutUserPauseCanAvoidLowLevelReplay() {
        assertTrue(BackgroundHandoffPolicy.shouldTreatUnknownNativeStateAsRunning(
            true, true, true, true, false))
        assertFalse(BackgroundHandoffPolicy.shouldTreatUnknownNativeStateAsRunning(
            true, true, true, true, true))
        assertFalse(BackgroundHandoffPolicy.shouldTreatUnknownNativeStateAsRunning(
            true, true, true, false, false))
        assertFalse(BackgroundHandoffPolicy.shouldTreatUnknownNativeStateAsRunning(
            true, true, false, true, false))
    }
}
