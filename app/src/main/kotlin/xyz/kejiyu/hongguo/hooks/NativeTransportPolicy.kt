package xyz.kejiyu.hongguo.hooks

/**
 * Host-first transport arbitration. The universal TTVideoEngine hook is the
 * ONLY layer that can suppress a *lifecycle* pause. Official EOF, explicit
 * media transport, native episode selection, and owner handoffs always retain
 * the host's original engine call/return value.
 *
 * Intentionally independent of obfuscated adapter class names.
 */
internal object NativeTransportPolicy {
    fun holdLifecyclePause(
        explicitTransport: Boolean,
        nativeUserSkip: Boolean,
        ownershipTransition: Boolean,
        miniLifecycleHold: Boolean,
        mediaLifecycleHold: Boolean
    ): Boolean =
        !explicitTransport && !nativeUserSkip && !ownershipTransition &&
            (miniLifecycleHold || mediaLifecycleHold)

    /** No automatic-EOF page/gate changes; only a matching explicit
     * user request may enter a host-native manual selection scope. */
    fun allowScopedPageOverride(
        manualUserSkip: Boolean,
        matchingOriginalSelection: Boolean
    ): Boolean = manualUserSkip && matchingOriginalSelection
}
