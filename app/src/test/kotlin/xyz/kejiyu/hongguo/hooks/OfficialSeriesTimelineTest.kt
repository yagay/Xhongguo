package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSeriesTimelineTest {
    @Test fun realEofSelectionHolderAndProgressAreRequiredForPlaying() {
        val session = OfficialSeriesTimeline()
        val adapter = Any()
        val completedEngine = Any()
        val newEngine = Any()
        session.rememberAdapter(adapter)
        session.onCompletion(completedEngine)
        assertTrue(session.maySelectAfterCompletion(adapter, 2, 1))
        assertFalse(session.maySelectAfterCompletion(adapter, 3, 1))
        assertFalse(session.onSelected(adapter, 2, true, 1, 2))
        assertEquals(OfficialSeriesTimeline.Phase.COMPLETED,
            session.snapshot().phase)

        assertTrue(session.onSelected(adapter, 2, true, 2, 2))
        assertEquals(OfficialSeriesTimeline.Phase.SELECTED,
            session.snapshot().phase)
        assertFalse(session.canDispatchEpisodeCommand())
        assertFalse(session.onProgress(newEngine, 1500, 40000))
        assertFalse(session.onBound(adapter, Any(), completedEngine))

        val holder = Any()
        assertTrue(session.onBound(adapter, holder, newEngine))
        assertEquals(OfficialSeriesTimeline.Phase.BOUND,
            session.snapshot().phase)
        assertTrue(session.onPlayCommand(newEngine))
        assertEquals(OfficialSeriesTimeline.Phase.STARTING,
            session.snapshot().phase)
        assertNull(session.snapshot().playingIndex)
        assertFalse(session.onProgress(newEngine, 0, -1))
        assertFalse(session.onProgress(newEngine, 0, 40000))
        assertFalse(session.onProgress(newEngine, 200, 40000))
        assertTrue(session.onProgress(newEngine, 550, 40000))
        assertEquals(2, session.snapshot().playingIndex)
        assertEquals(OfficialSeriesTimeline.Phase.PLAYING,
            session.snapshot().phase)
        assertTrue(session.canDispatchEpisodeCommand())
        assertNull(session.pendingEngine())
    }

    @Test fun duplicateOrWrongNativeEventsCannotFakeCompletion() {
        val state = OfficialSeriesTimeline()
        val adapter = Any()
        val otherAdapter = Any()
        val old = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.onCompletion(old)
        assertFalse(state.maySelectAfterCompletion(otherAdapter, 3, 2))
        assertFalse(state.onSelected(adapter, 3, false, 3, 3))
        assertFalse(state.onSelected(adapter, 3, true, 3, 2))
        assertTrue(state.onSelected(adapter, 3, true, 3, 3))
        assertFalse(state.onBound(otherAdapter, Any(), next))
        assertFalse(state.onProgress(next, 1000, 4000))
        assertEquals(OfficialSeriesTimeline.Phase.SELECTED,
            state.snapshot().phase)
    }

    @Test fun userPauseRevokesNextEngineAndPreventsResurrection() {
        val state = OfficialSeriesTimeline()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.onCompletion(old)
        state.onSelected(adapter, 1, true, 1, 1)
        state.onBound(adapter, Any(), next)
        state.onPause(next, true)
        assertEquals(OfficialSeriesTimeline.Phase.PAUSED,
            state.snapshot().phase)
        assertNull(state.snapshot().pendingIndex)
        assertFalse(state.onPlayCommand(next))
        assertFalse(state.onProgress(next, 2000, 5000))
        state.reset()
        assertEquals(OfficialSeriesTimeline.Phase.IDLE, state.snapshot().phase)
    }

    @Test fun nativeErrorDoesNotReleaseOrSkipToAnotherPage() {
        val state = OfficialSeriesTimeline()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.onCompletion(old)
        state.onSelected(adapter, 4, true, 4, 4)
        state.onBound(adapter, Any(), next)
        state.onError(next, "source_unavailable")
        assertEquals(OfficialSeriesTimeline.Phase.FAILED,
            state.snapshot().phase)
        assertEquals(4, state.snapshot().pendingIndex)
        assertFalse(state.canDispatchEpisodeCommand())
        assertEquals("source_unavailable", state.snapshot().error)
    }
}
