package xyz.kejiyu.hongguo.hooks

/**
 * Recover ONLY the same native target page after the host already moved
 * ViewPager2 ahead but the original business selection was rejected.
 *
 * The recovery never advances the UI page or fabricates a new episode.
 * A completed native EOF or an explicit user skip is necessary.
 */
internal object NativeSeriesSelectionRecoveryPolicy {
    enum class Origin { VERIFIED_EOF, USER_SKIP }

    fun mayReconcile(
        origin: Origin,
        sameAdapter: Boolean,
        genuineBackground: Boolean,
        nativeEofConfirmed: Boolean,
        userPaused: Boolean,
        businessIndex: Int,
        pagerIndex: Int,
        requestedIndex: Int,
        loadedCount: Int,
        hostAlreadySelected: Boolean
    ): Boolean {
        if (!sameAdapter || !genuineBackground || userPaused ||
            hostAlreadySelected || loadedCount !in 2..5000 ||
            businessIndex < 0 || requestedIndex != businessIndex + 1 ||
            requestedIndex !in 0 until loadedCount ||
            pagerIndex != requestedIndex) return false
        return when (origin) {
            Origin.VERIFIED_EOF -> nativeEofConfirmed
            Origin.USER_SKIP -> true
        }
    }
}
