package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesNextPlayerRecoveryPolicyTest {
    private fun valid() = SeriesNextPlayerRecoveryPolicy.Evidence(
        background = true,
        nativeEofObserved = true,
        sameCompletedEngine = true,
        sameSeriesActivity = true,
        explicitPauseUnchanged = true,
        noNewEnginePlay = true,
        newHolder = true,
        listenerAttached = true,
        selectionIndex = 1,
        pagerIndex = 1,
        targetIndex = 1,
        loadedCount = 12,
        completionAgeMs = 2100L,
        alreadyAttempted = false
    )

    @Test fun recoversOnlyNewNativeHolderForAlreadyAcceptedPage() {
        assertTrue(SeriesNextPlayerRecoveryPolicy.shouldResume(valid()))
    }

    @Test fun neverRetriesWrongEpisodeOrUnloadedPage() {
        val policy = SeriesNextPlayerRecoveryPolicy
        assertFalse(policy.shouldResume(valid().copy(selectionIndex = 0)))
        assertFalse(policy.shouldResume(valid().copy(pagerIndex = 2)))
        assertFalse(policy.shouldResume(valid().copy(targetIndex = 12)))
        assertFalse(policy.shouldResume(valid().copy(targetIndex = 0)))
        assertFalse(policy.shouldResume(valid().copy(loadedCount = -1)))
    }

    @Test fun neverResumesFinishedPreviousPlayerOrDetachedListener() {
        val policy = SeriesNextPlayerRecoveryPolicy
        assertFalse(policy.shouldResume(valid().copy(newHolder = false)))
        assertFalse(policy.shouldResume(valid().copy(listenerAttached = false)))
        assertFalse(policy.shouldResume(valid().copy(noNewEnginePlay = false)))
        assertFalse(policy.shouldResume(valid().copy(sameCompletedEngine = false)))
    }

    @Test fun neverOverridesPauseForegroundOrActivityNavigation() {
        val policy = SeriesNextPlayerRecoveryPolicy
        assertFalse(policy.shouldResume(valid().copy(background = false)))
        assertFalse(policy.shouldResume(valid().copy(sameSeriesActivity = false)))
        assertFalse(policy.shouldResume(valid().copy(explicitPauseUnchanged = false)))
        assertFalse(policy.shouldResume(valid().copy(nativeEofObserved = false)))
    }

    @Test fun onlyOneAttemptWithinConfirmedEofWindow() {
        val policy = SeriesNextPlayerRecoveryPolicy
        assertFalse(policy.shouldResume(valid().copy(alreadyAttempted = true)))
        assertFalse(policy.shouldResume(valid().copy(completionAgeMs = 1400L)))
        assertFalse(policy.shouldResume(valid().copy(completionAgeMs = 9100L)))
    }
}
