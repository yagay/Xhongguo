package xyz.kejiyu.hongguo.hooks

/**
 * Presentation-only state. Native identity belongs to the resolver, never
 * to recycled Activity text or a previous drama's cached screenshot.
 *
 * A missing new series DTO is a valid temporary state, but an indefinitely
 * "正在获取" card falsely suggests a network request is still in progress.
 */
internal class MediaPresentationState(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private companion object {
        const val RESOLVING_WINDOW_MS = 12_000L
    }

    @Volatile private var awaitingNewSeries = false
    @Volatile private var unresolvedSince = 0L

    fun onVideoTransition(sameSeries: Boolean) {
        awaitingNewSeries = !sameSeries
        unresolvedSince = if (awaitingNewSeries) nowMs() else 0L
    }

    fun onResolved(verified: Boolean, title: String?) {
        if (verified && !title.isNullOrBlank()) {
            awaitingNewSeries = false
            unresolvedSince = 0L
        }
    }

    private fun stillResolving(): Boolean {
        if (!awaitingNewSeries) return false
        val elapsed = nowMs() - unresolvedSince
        return elapsed >= 0L && elapsed < RESOLVING_WINDOW_MS
    }

    fun title(value: String?): String = value?.takeIf { it.isNotBlank() }
        ?: if (stillResolving()) "正在识别当前短剧" else "红果短剧"

    fun subtitle(value: String?, playing: Boolean): String =
        value?.takeIf { it.isNotBlank() }
            ?: when {
                stillResolving() -> "正在获取剧集信息"
                awaitingNewSeries && playing -> "正在播放 · 剧集信息暂不可用"
                awaitingNewSeries -> "剧集信息暂不可用"
                playing -> "正在播放的视频"
                else -> "已暂停"
            }

    fun reset() {
        awaitingNewSeries = false
        unresolvedSince = 0L
    }
}
