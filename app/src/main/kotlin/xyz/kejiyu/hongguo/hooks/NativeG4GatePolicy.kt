package xyz.kejiyu.hongguo.hooks

/**
 * A background-only native EOF grant for the FIRST host G4 after s4.
 * It is not a sticky preference and never applies to manual selections,
 * user pauses, unrelated controllers or stale generations.
 */
internal object NativeG4GatePolicy {
    fun mayRelax(
        hostBlocked: Boolean,
        background: Boolean,
        explicitCommand: Boolean,
        controllerMatches: Boolean,
        generationMatches: Boolean,
        acceptedEofSelection: Boolean,
        elapsedMs: Long
    ): Boolean =
        hostBlocked && background && !explicitCommand &&
            controllerMatches && generationMatches &&
            acceptedEofSelection && elapsedMs in 0L..2500L
}
