package xyz.kejiyu.hongguo.hooks

/**
 * Guard for the native ViewPager2 fallback after a confirmed background EOF.
 *
 * Never guess the next drama, request a URL, or replay the current holder.
 * Only move the OFFICIAL pager to an already loaded next episode, once, when
 * the original host completion callback has fired but autoplay has stalled.
 *
 * Kept Android-free for JVM unit tests.
 */
internal object SeriesAutoplayRecoveryPolicy {
    data class Evidence(
        val background: Boolean,
        val eofObserved: Boolean,
        val sameNativeEngine: Boolean,
        val sameSeriesActivity: Boolean,
        val explicitPauseUnchanged: Boolean,
        val noNewVideoStarted: Boolean,
        val completionAgeMs: Long,
        val currentIndex: Int,
        val pagerIndex: Int,
        val itemCount: Int,
        val nearRealNativeEnd: Boolean,
        val alreadyAttempted: Boolean
    )

    enum class Action { ADVANCE_PAGER, RECONCILE_SELECTION }

    data class Decision(val action: Action, val targetIndex: Int)

    /**
     * The APK has two independent indexes:
     * adapter.a.D = business selection; ViewPager2.currentItem = UI page.
     *
     * When pager == D + 1, Android already selected the NEXT page, but
     * adapter.r4(position) did not run (often due to a stopped Fragment).
     * Moving ViewPager2 another step would skip a real episode. Instead
     * reconcile the existing page through the host's r4(position).
     *
     * For pager == D, only request one normal next-page selection.
     * Unknown differences are not recoverable without guessing.
     */
    fun decision(e: Evidence): Decision? {
        if (!e.background || !e.eofObserved || !e.sameNativeEngine ||
            !e.sameSeriesActivity || !e.explicitPauseUnchanged ||
            !e.noNewVideoStarted || !e.nearRealNativeEnd ||
            e.alreadyAttempted || e.completionAgeMs !in 5000L..18000L ||
            e.currentIndex < 0 || e.pagerIndex < 0 ||
            e.itemCount <= 1 || e.currentIndex >= e.itemCount - 1 ||
            e.pagerIndex >= e.itemCount) return null

        return when {
            e.pagerIndex == e.currentIndex ->
                Decision(Action.ADVANCE_PAGER, e.currentIndex + 1)
            e.pagerIndex == e.currentIndex + 1 ->
                Decision(Action.RECONCILE_SELECTION, e.pagerIndex)
            else -> null
        }
    }

    /** Backwards-compatible helper: only returns a physical pager movement.
     * Never reports an already-selected page as "next".
     */
    fun nextIndex(e: Evidence): Int? =
        decision(e)?.takeIf { it.action == Action.ADVANCE_PAGER }?.targetIndex
}
