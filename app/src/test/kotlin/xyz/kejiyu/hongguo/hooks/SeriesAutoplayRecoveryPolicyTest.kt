package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesAutoplayRecoveryPolicyTest {
    private fun eligible() = SeriesAutoplayRecoveryPolicy.Evidence(
        background = true,
        eofObserved = true,
        sameNativeEngine = true,
        sameSeriesActivity = true,
        explicitPauseUnchanged = true,
        noNewVideoStarted = true,
        completionAgeMs = 5500L,
        currentIndex = 2,
        pagerIndex = 2,
        itemCount = 10,
        nearRealNativeEnd = true,
        alreadyAttempted = false
    )

    @Test fun onlyAdvancesOneOfficialNextItemAfterRealBackgroundStall() {
        assertEquals(3, SeriesAutoplayRecoveryPolicy.nextIndex(eligible()))
    }

    @Test fun neverInventsNextEpisodeAtActualLastItem() {
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(currentIndex = 9, pagerIndex = 9)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(itemCount = 0)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(itemCount = 1)))
    }

    @Test fun neverMovesAPagerWhenIndexHasAlreadyAdvanced() {
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(pagerIndex = 3)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(currentIndex = -1)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(noNewVideoStarted = false)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(sameNativeEngine = false)))
    }

    @Test fun userPauseOrForegroundIsNeverOverridden() {
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(background = false)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(explicitPauseUnchanged = false)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(sameSeriesActivity = false)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(eofObserved = false)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(nearRealNativeEnd = false)))
    }

    @Test fun recoveryIsBoundedToOneAttemptAndAValidEOFWindow() {
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(alreadyAttempted = true)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(completionAgeMs = 2400L)))
        assertNull(SeriesAutoplayRecoveryPolicy.nextIndex(
            eligible().copy(completionAgeMs = 19_000L)))
    }
}
