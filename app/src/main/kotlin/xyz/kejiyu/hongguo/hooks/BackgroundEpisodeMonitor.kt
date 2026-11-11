package xyz.kejiyu.hongguo.hooks

/**
 * Diagnoses a stuck end-of-episode in the original native player.
 * No synthetic seek, replay or speculative next-video command is issued.
 */
internal class BackgroundEpisodeMonitor {
    private var endSinceMs = 0L
    private var stalledReported = false
    private var sawCompletion = false

    fun onNativeCompletion() { sawCompletion = true }

    fun reset() {
        endSinceMs = 0L
        stalledReported = false
        sawCompletion = false
    }

    /** Only report after a real native duration has remained at EOF for 3s.
     * One event per end: repeated 1.4s media polls must not flood logs.
     */
    fun onSample(
        positionMs: Long,
        durationMs: Long,
        backgroundActive: Boolean,
        playing: Boolean,
        nowMs: Long
    ): String? {
        if (!backgroundActive || !playing || durationMs < 5000L ||
            positionMs < 0L || durationMs - positionMs > 350L) {
            reset()
            return null
        }
        if (endSinceMs <= 0L || nowMs < endSinceMs) {
            endSinceMs = nowMs
            return null
        }
        if (!stalledReported && nowMs - endSinceMs >= 3000L) {
            stalledReported = true
            return if (sawCompletion) "completion_but_no_next"
                else "no_completion_callback"
        }
        return null
    }
}
