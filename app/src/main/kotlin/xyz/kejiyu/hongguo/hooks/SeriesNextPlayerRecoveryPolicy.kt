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

    /** Explicit skip reasons make device diagnostics actionable. A slow
     * real host registration can be accepted for up to 15 seconds after EOF,
     * but only with an unchanged selected page, engine and pause epoch. */
    fun rejectionReason(e: Evidence): String? = when {
        !e.background -> "not_background"
        !e.nativeEofObserved -> "no_native_eof"
        !e.sameCompletedEngine -> "completed_engine_changed"
        !e.sameSeriesActivity -> "series_activity_changed"
        !e.explicitPauseUnchanged -> "explicit_pause_or_owner_transition"
        !e.noNewEnginePlay -> "new_engine_already_started"
        !e.newHolder -> "not_distinct_next_holder"
        !e.listenerAttached -> "next_holder_listener_missing"
        e.alreadyAttempted -> "already_attempted"
        e.completionAgeMs !in 1500L..15000L -> "outside_eof_window"
        e.targetIndex <= 0 || e.targetIndex >= e.loadedCount -> "invalid_next_index"
        e.selectionIndex != e.targetIndex -> "selection_not_committed"
        e.pagerIndex != e.targetIndex -> "pager_not_aligned"
        else -> null
    }

    fun shouldResume(e: Evidence): Boolean = rejectionReason(e) == null
}
