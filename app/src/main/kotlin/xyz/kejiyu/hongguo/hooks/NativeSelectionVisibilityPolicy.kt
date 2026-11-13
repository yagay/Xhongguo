package xyz.kejiyu.hongguo.hooks

/**
 * 7.3.5.32 adapter.a.s4() calls j15.d.y4() =
 * isPageInVisible(), not isPageVisible().
 *
 * Only while the original native controller advances a real loaded next
 * episode after an actual background EOF, allow that synchronous
 * s4() query to treat the selected page as usable. No host fields or
 * ViewPager2 indices are written and no new episode is synthesized.
 */
internal object NativeSelectionVisibilityPolicy {
    data class Evidence(
        val backgroundConfirmed: Boolean,
        val nativeCompletionObserved: Boolean,
        val originalEngineStillActive: Boolean,
        val sameSeriesActivity: Boolean,
        val userPauseUnchanged: Boolean,
        val noNewVideoStarted: Boolean,
        val completionAgeMs: Long,
        val businessIndex: Int,
        val pagerIndex: Int,
        val requestedIndex: Int,
        val loadedEpisodeCount: Int
    )

    fun mayOverride(e: Evidence): Boolean =
        e.backgroundConfirmed && e.nativeCompletionObserved &&
            e.originalEngineStillActive && e.sameSeriesActivity &&
            e.userPauseUnchanged && e.noNewVideoStarted &&
            e.completionAgeMs in 0L..9000L &&
            e.businessIndex >= 0 &&
            e.requestedIndex == e.businessIndex + 1 &&
            e.requestedIndex == e.pagerIndex &&
            e.loadedEpisodeCount > e.requestedIndex
}
