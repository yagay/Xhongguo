package xyz.kejiyu.hongguo.hooks

/**
 * Narrow, single-use permission to let an ORIGINAL host media-button skip
 * cross the offscreen page gate. Never moves ViewPager2, calls s4(), or
 * creates a player. Invalid/stale requests always follow original host logic.
 */
internal object NativeManualEpisodeSkipPolicy {
    fun mayScope(
        sameAdapter: Boolean,
        background: Boolean,
        nativeSelectionIdle: Boolean,
        explicitTransportPause: Boolean,
        originalIndex: Int,
        pagerIndex: Int,
        requestedIndex: Int,
        mediaTargetIndex: Int,
        loadedCount: Int,
        sameGeneration: Boolean,
        ageMs: Long
    ): Boolean =
        sameAdapter && background && nativeSelectionIdle &&
            !explicitTransportPause && sameGeneration &&
            ageMs in 0L..2500L && loadedCount in 2..5000 &&
            originalIndex in 0 until loadedCount &&
            requestedIndex in 0 until loadedCount &&
            mediaTargetIndex == requestedIndex &&
            (requestedIndex == originalIndex + 1 ||
                requestedIndex == originalIndex - 1) &&
            (pagerIndex == originalIndex || pagerIndex == requestedIndex)
}
