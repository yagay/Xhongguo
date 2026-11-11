package xyz.kejiyu.hongguo.hooks

/**
 * Select a cross-namespace native video DTO ONLY when it is independently
 * observed after the new TTVideoEngine starts, with one unambiguous series
 * whose official detail has been captured. Never match by recency alone.
 *
 * No Android dependencies: the safety policy is unit-testable.
 */
internal object NativeEvidencePolicy {
    data class Candidate(
        val videoId: String,
        val seriesId: String,
        val nativeObservedAtMs: Long,
        val detailAvailable: Boolean,
        val detailObservedAtMs: Long
    )

    fun select(
        candidates: List<Candidate>,
        transitionAtMs: Long,
        nowMs: Long,
        previousVideoId: String?,
        previousSeriesId: String?
    ): Candidate? {
        if (transitionAtMs <= 0L || nowMs < transitionAtMs ||
            nowMs - transitionAtMs > 15_000L) return null
        val eligible = candidates.filter { item ->
            item.videoId != previousVideoId &&
                item.nativeObservedAtMs >= transitionAtMs &&
                item.nativeObservedAtMs <= nowMs &&
                item.detailAvailable &&
                item.detailObservedAtMs <= nowMs &&
                // A new drama must have had a new official detail event
                // near the transition, not just a previous Activity cache.
                (item.seriesId == previousSeriesId ||
                    item.detailObservedAtMs >= transitionAtMs - 2_000L)
        }
        // Multiple newly consumed series can be autoplay recommendations
        // or preloaded neighbours. Refuse to guess which actually plays.
        if (eligible.map { it.seriesId }.distinct().size != 1) return null
        // When there are multiple videos from one series, only the series
        // title/poster is confirmed; never claim a specific episode.
        return eligible.maxByOrNull { it.nativeObservedAtMs }
    }
}
