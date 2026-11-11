package xyz.kejiyu.hongguo.hooks

/**
 * Budget for expensive, view-tree based compatibility fallbacks.
 * Immediate event-driven UI hooks are unaffected.
 */
internal object UiScanPolicy {
    fun needsViewTree(
        master: Boolean, control: Boolean, player: Boolean,
        progress: Boolean, refresh: Boolean, pausedWithRestore: Boolean
    ): Boolean = master && !pausedWithRestore &&
        (control || player || progress || refresh)

    fun nextDelayMs(
        master: Boolean, focused: Boolean, needsViewTree: Boolean
    ): Long = when {
        !master -> 2_000L
        !focused -> 1_500L
        needsViewTree -> 500L
        else -> 1_000L
    }
}
