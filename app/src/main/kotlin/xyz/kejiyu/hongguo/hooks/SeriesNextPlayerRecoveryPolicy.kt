package xyz.kejiyu.hongguo.hooks

/**
 * One-shot continuation for a native full-series episode already selected by
 * the host after EOF. This never changes the pager, requests an episode, or
 * starts a raw decoder. It only permits resume() on the newly registered
 * official x05.w player when no native play has followed the host selection.
 */
internal object SeriesNextPlayerRecoveryPolicy {
    data class Evidence(
        val background: Boolean,
        val nativeEofObserved: Boolean,
        val sameCompletedEngine: Boolean,
        val sameSeriesActivity: Boolean,
        val explicitPauseUnchanged: Boolean,
        val noNewEnginePlay: Boolean,
        val newHolder: Boolean,
        val listenerAttached: Boolean,
        val selectionIndex: Int,
        val pagerIndex: Int,
        val targetIndex: Int,
        val loadedCount: Int,
        val completionAgeMs: Long,
        val alreadyAttempted: Boolean
    )

    fun shouldResume(e: Evidence): Boolean =
        e.background && e.nativeEofObserved && e.sameCompletedEngine &&
            e.sameSeriesActivity && e.explicitPauseUnchanged &&
            e.noNewEnginePlay && e.newHolder && e.listenerAttached &&
            !e.alreadyAttempted && e.completionAgeMs in 1500L..9000L &&
            e.targetIndex > 0 && e.targetIndex < e.loadedCount &&
            e.selectionIndex == e.targetIndex &&
            e.pagerIndex == e.targetIndex
}
