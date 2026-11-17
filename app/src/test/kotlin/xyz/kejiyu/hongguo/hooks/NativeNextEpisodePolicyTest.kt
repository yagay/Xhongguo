package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNextEpisodePolicyTest {
    @Test fun exactSameSeriesImmediateNextIsOnlyAcceptableCandidate() {
        assertEquals(NativeNextEpisodePolicy.Result.VERIFIED_NEXT,
            NativeNextEpisodePolicy.check("seriesA", "vidA", 1,
                "seriesA", "vidB", 2))
    }

    @Test fun sameVideoCannotBeMistakenForFollowingEpisode() {
        assertEquals(NativeNextEpisodePolicy.Result.SAME_VIDEO,
            NativeNextEpisodePolicy.check("seriesA", "vidA", 1,
                "seriesA", "vidA", 2))
    }

    @Test fun anotherSeriesCannotBeSilentlyChosenAsNextEpisode() {
        assertEquals(NativeNextEpisodePolicy.Result.DIFFERENT_SERIES,
            NativeNextEpisodePolicy.check("seriesA", "vidA", 1,
                "seriesB", "vidB", 2))
    }

    @Test fun skippingAnEpisodeIsNotAccepted() {
        assertEquals(NativeNextEpisodePolicy.Result.NON_ADJACENT_INDEX,
            NativeNextEpisodePolicy.check("seriesA", "vidA", 1,
                "seriesA", "vidC", 3))
    }

    @Test fun missingNativeIdentityIsNotAuthorizationToStartPlayer() {
        assertEquals(NativeNextEpisodePolicy.Result.MISSING_IDENTITY,
            NativeNextEpisodePolicy.check("seriesA", "vidA", 1,
                "seriesA", null, 2))
    }
}
