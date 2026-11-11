package xyz.kejiyu.hongguo.hooks

/**
 * Presentation-only state. The real series identity is decided by the native
 * model resolver, never by the text visible in a stale Activity.
 *
 * Do not publish the previous drama's title/artwork for a new, unverified
 * engine. Equally, do not publish a completely empty MediaSession card while
 * waiting for the new native DTO to arrive.
 */
internal class MediaPresentationState {
    @Volatile private var awaitingNewSeries = false

    fun onVideoTransition(sameSeries: Boolean) {
        awaitingNewSeries = !sameSeries
    }

    fun onResolved(verified: Boolean, title: String?) {
        if (verified && !title.isNullOrBlank()) awaitingNewSeries = false
    }

    fun title(value: String?): String = value?.takeIf { it.isNotBlank() }
        ?: if (awaitingNewSeries) "正在识别当前短剧" else "红果短剧"

    fun subtitle(value: String?, playing: Boolean): String =
        value?.takeIf { it.isNotBlank() }
            ?: if (awaitingNewSeries) "正在获取剧集信息"
            else if (playing) "正在播放的视频" else "已暂停"

    fun reset() {
        awaitingNewSeries = false
    }
}
