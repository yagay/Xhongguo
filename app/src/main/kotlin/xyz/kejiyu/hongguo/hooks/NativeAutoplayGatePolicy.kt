package xyz.kejiyu.hongguo.hooks

/**
 * Only inside the original host's accepted EOF -> s4 -> G4 stack can a
 * background-only auto-start gate be relaxed. Outside this exact scope,
 * return the native answer unchanged (including a user-requested pause).
 */
internal object NativeAutoplayGatePolicy {
    fun shouldAllow(
        hostBlocked: Boolean,
        genuineBackground: Boolean,
        sameController: Boolean,
        verifiedEofSelection: Boolean,
        userCommandInProgress: Boolean
    ): Boolean =
        hostBlocked && genuineBackground && sameController &&
            verifiedEofSelection && !userCommandInProgress
}
