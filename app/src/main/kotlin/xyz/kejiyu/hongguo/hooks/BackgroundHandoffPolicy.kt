package xyz.kejiyu.hongguo.hooks

/**
 * Narrow opt-in policy for preserving the HOST video lifecycle during
 * an explicit Android onUserLeaveHint.
 *
 * Unlike the old background recovery path, this holds the host's VideoShop
 * callback owner and TTVideoEngine together. Android lifecycle callbacks
 * for unrelated pages or a user-requested pause must always proceed.
 */
internal object BackgroundHandoffPolicy {
    private const val HINT_WINDOW_MS = 2500L

    fun protectOnUserLeave(
        mediaEnabled: Boolean,
        wasPlaying: Boolean,
        userPauseInProgress: Boolean,
        ownershipTransition: Boolean,
        samePinnedEngine: Boolean,
        sameVideoActivity: Boolean,
        elapsedMs: Long
    ): Boolean =
        mediaEnabled && wasPlaying && !userPauseInProgress &&
            !ownershipTransition && samePinnedEngine && sameVideoActivity &&
            elapsedMs in 0L..HINT_WINDOW_MS

    fun shouldTreatUnknownNativeStateAsRunning(
        confirmedBackground: Boolean,
        heldNativePauseOrLifecycle: Boolean,
        sameEngine: Boolean,
        explicitPauseUnchanged: Boolean,
        hasVerifiedNativePause: Boolean
    ): Boolean =
        confirmedBackground && heldNativePauseOrLifecycle && sameEngine &&
            explicitPauseUnchanged && !hasVerifiedNativePause
}
