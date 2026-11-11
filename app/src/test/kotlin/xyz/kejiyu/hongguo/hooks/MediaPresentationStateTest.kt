package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for transitions to the next series and the next episode. */
class MediaPresentationStateTest {
    @Test fun newSeriesDoesNotDisplayPreviousSeries() {
        val state = MediaPresentationState()
        state.onVideoTransition(false)
        assertEquals("正在识别当前短剧", state.title(null))
        assertEquals("正在获取剧集信息", state.subtitle(null, true))
        assertEquals("下一部作品", state.title("下一部作品"))
        state.onResolved(true, "下一部作品")
        assertEquals("第1集", state.subtitle("第1集", true))
    }

    @Test fun sameSeriesEpisodeKeepsNeutralFallback() {
        val state = MediaPresentationState()
        state.onVideoTransition(true)
        assertEquals("红果短剧", state.title(null))
        assertEquals("第2集 · 全80集", state.subtitle("第2集 · 全80集", true))
    }

    @Test fun unknownSeriesNeverStaysInLoadingStateForever() {
        var time = 1_000L
        val state = MediaPresentationState { time }
        state.onVideoTransition(false)
        assertEquals("正在识别当前短剧", state.title(null))
        time += 12_500L
        assertEquals("红果短剧", state.title(null))
        assertEquals("正在播放 · 剧集信息暂不可用",
            state.subtitle(null, true))
        state.onResolved(true, "恢复成功的剧名")
        assertEquals("恢复成功的剧名", state.title("恢复成功的剧名"))
        assertEquals("正在播放的视频", state.subtitle(null, true))
    }

    @Test fun unverifiedTitleDoesNotMarkTheNewSeriesResolved() {
        val state = MediaPresentationState()
        state.onVideoTransition(false)
        state.onResolved(false, "可能属于旧页面的标题")
        assertEquals("正在识别当前短剧", state.title(null))
        state.reset()
        assertEquals("红果短剧", state.title(null))
    }
}
