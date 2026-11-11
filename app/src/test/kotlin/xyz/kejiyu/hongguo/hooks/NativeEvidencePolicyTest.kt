package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEvidencePolicyTest {
    private fun evidence(
        video: String, series: String, at: Long, detailAt: Long,
        detail: Boolean = true
    ) = NativeEvidencePolicy.Candidate(video, series, at, detail, detailAt)

    @Test fun newConsumedModelWithMatchingOfficialDetailRecoversMedia() {
        val current = evidence("native-video-new", "drama-A", 10_600L, 10_740L)
        assertEquals(current, NativeEvidencePolicy.select(listOf(current),
            10_000L, 11_000L, "native-video-previous", "drama-A"))
    }

    @Test fun oldPresenterAndPreloadMustNotBeTrusted() {
        val old = evidence("previous-video", "drama-A", 10_050L, 10_080L)
        val preloaded = evidence("unrelated", "drama-B", 9_000L, 9_500L)
        assertNull(NativeEvidencePolicy.select(listOf(old, preloaded),
            10_000L, 11_000L, "previous-video", "drama-A"))
    }

    @Test fun aNewDramaNeedsFreshOfficialDetail() {
        val recent = evidence("new-video", "drama-B", 10_050L, 3_000L)
        assertNull(NativeEvidencePolicy.select(listOf(recent),
            10_000L, 11_000L, "old-video", "drama-A"))
    }

    @Test fun multipleDifferentNewSeriesStayUnverified() {
        val a = evidence("new-video-1", "drama-B", 10_100L, 10_150L)
        val b = evidence("new-video-2", "drama-C", 10_200L, 10_250L)
        assertNull(NativeEvidencePolicy.select(listOf(a, b),
            10_000L, 11_000L, "old-video", "drama-A"))
    }

    @Test fun staleTransitionDoesNotGuessAfterFifteenSeconds() {
        val current = evidence("new-video", "drama-A", 10_050L, 10_080L)
        assertNull(NativeEvidencePolicy.select(listOf(current),
            10_000L, 30_000L, "old-video", "drama-A"))
    }

    @Test fun detailNotAvailableNeverCountsAsMatch() {
        val current = evidence("new-video", "drama-A", 10_050L, 0L, false)
        assertNull(NativeEvidencePolicy.select(listOf(current),
            10_000L, 11_000L, "old-video", "drama-A"))
    }
}
