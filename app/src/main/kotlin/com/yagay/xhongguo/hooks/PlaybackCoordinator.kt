package com.yagay.xhongguo.hooks

/**
 * Thread-safe, pure policy for all module-owned playback recovery.
 * Native playback remains owned by TTVideoEngine. No Android or Hook calls here.
 */
internal class PlaybackGate {
    enum class Blocker { FOCUS, CALL, EXTERNAL, HOLD }
    private val blockers = java.util.EnumSet.noneOf(Blocker::class.java)
    private var userEpoch = 0L
    @Synchronized fun set(reason: Blocker, active: Boolean) {
        if (active) blockers.add(reason) else blockers.remove(reason)
    }
    @Synchronized fun blocked(): Boolean = blockers.isNotEmpty()
    @Synchronized fun hardBlocked(): Boolean = blockers.any { it != Blocker.HOLD }
    @Synchronized fun userAction(): Long = ++userEpoch
    @Synchronized fun epoch(): Long = userEpoch
    @Synchronized fun reset() { blockers.clear(); ++userEpoch }
    @Synchronized fun description(): String = blockers.joinToString(",")
}
internal object PlaybackCoordinator {
    val gate = PlaybackGate()
    fun permitsAutomaticPlayback(): Boolean = !gate.blocked()
    fun sameSession(engine: Any?, expected: Any?, video: String?, epoch: Long): Boolean =
        engine != null && engine === expected && epoch >= 0 &&
            NativePlaybackSession.generation() == epoch &&
            NativePlaybackSession.videoId(engine) == video &&
            NativeMetadataAccess.call(engine, "isReleased") != true
}
