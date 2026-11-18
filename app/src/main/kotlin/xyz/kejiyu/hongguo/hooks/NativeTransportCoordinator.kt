package xyz.kejiyu.hongguo.hooks

/**
 * Routes the original TTVideoEngine.pause() through the early-main lifecycle
 * protection, without adding another TTVideoEngine hook or second engine.
 * Source, readiness, EOF and MediaSession are handled by their observers.
 */
internal object NativeTransportCoordinator {
    fun shouldHoldOriginalPause(
        engine: Any?,
        miniEnabled: Boolean,
        systemMediaEnabled: Boolean
    ): Boolean {
        if (engine == null || (!miniEnabled && !systemMediaEnabled)) return false
        val explicit = SystemMediaBridge.isExplicitMediaCommandInProgress()
        val manual = systemMediaEnabled &&
            NativeSeriesV3.isOriginalManualSkipPause(engine)
        val ownership = SinglePlaybackOwner.isOwnershipTransition()
        if (explicit || manual || ownership) return false

        val mini = miniEnabled &&
            InAppMiniPlayer.shouldKeepNativeEnginePlaying(engine)
        if (mini) return NativeTransportPolicy.holdLifecyclePause(
            explicit, manual, ownership, miniLifecycleHold = true,
            mediaLifecycleHold = false)

        // Reuse main's confirmed ActivityPrePaused / background active-engine
        // guard. It already validates owner identity and lifecycle timing.
        val media = systemMediaEnabled &&
            SystemMediaBridge.shouldSuppressBackgroundEnginePause(engine)
        return NativeTransportPolicy.holdLifecyclePause(
            explicit, manual, ownership, miniLifecycleHold = false,
            mediaLifecycleHold = media)
    }
}
