package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSelectionVisibilityPolicyTest {
    private fun eof() = NativeSelectionVisibilityPolicy.Evidence(
        backgroundConfirmed = true,
        nativeCompletionObserved = true,
        originalEngineStillActive = true,
        sameSeriesActivity = true,
        userPauseUnchanged = true,
        noNewVideoStarted = true,
        completionAgeMs = 6L,
        businessIndex = 8,
        pagerIndex = 9,
        requestedIndex = 9,
        loadedEpisodeCount = 11
    )

    @Test fun originalHostCanSelectLoadedNextEpisodeWhileBackgroundInvisible() {
        assertTrue(NativeSelectionVisibilityPolicy.mayOverride(eof()))
    }

    @Test fun foregroundAndExplicitPauseNeverPretendVisible() {
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(backgroundConfirmed = false)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(userPauseUnchanged = false)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(sameSeriesActivity = false)))
    }

    @Test fun onlyTheActualCompletedNativeEngineIsEligible() {
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(nativeCompletionObserved = false)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(originalEngineStillActive = false)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(noNewVideoStarted = false)))
    }

    @Test fun indexMustBeExactlyAlreadySelectedNextEpisode() {
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(pagerIndex = 8)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(pagerIndex = 10)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(requestedIndex = 10)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(businessIndex = -1)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(loadedEpisodeCount = 9)))
    }

    @Test fun neverOverrideForStaleOrFutureCompletion() {
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(completionAgeMs = -1L)))
        assertFalse(NativeSelectionVisibilityPolicy.mayOverride(
            eof().copy(completionAgeMs = 20_000L)))
    }
}
