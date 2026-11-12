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

    fun nextIndex(e: Evidence): Int? {
        if (!e.background || !e.eofObserved || !e.sameNativeEngine ||
            !e.sameSeriesActivity || !e.explicitPauseUnchanged ||
            !e.noNewVideoStarted || !e.nearRealNativeEnd ||
            e.alreadyAttempted || e.completionAgeMs !in 5000L..18000L ||
            e.currentIndex < 0 || e.pagerIndex != e.currentIndex ||
            e.itemCount <= 1 || e.currentIndex >= e.itemCount - 1) {
            return null
        }
        return e.currentIndex + 1
    }
}
