package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class NativeSeriesV3StateTest {
    @Test fun pagerAlreadyMovedAheadStillAllowsRealHostS4() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        val old = Any()
        state.rememberAdapter(adapter)
        state.complete(old)
        assertTrue(state.mayScopeNext(adapter, 2, 1, 2, 60, true, false))
        assertTrue(state.mayScopeNext(adapter, 2, 1, 1, 60, true, false))
        assertFalse(state.mayScopeNext(adapter, 3, 1, 3, 60, true, false))
        assertFalse(state.mayScopeNext(adapter, 2, 1, 3, 60, true, false))
        assertFalse(state.mayScopeNext(adapter, 2, 1, 2, 60, false, false))
        assertFalse(state.mayScopeNext(adapter, 2, 1, 2, 60, true, true))
    }

    @Test fun consecutiveNativeEofsKeepAdvancingAfterFirstConfirmedVideo() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        val first = Any()
        val second = Any()
        val third = Any()
        state.rememberAdapter(adapter)
        state.complete(first)
        assertTrue(state.mayScopeNext(adapter, 2, 1, 2, 88, true, false))
        assertTrue(state.select(adapter, 2, true, 2, 2))
        assertTrue(state.bind(adapter, Any(), second))
        assertTrue(state.playCommand(second))
        assertFalse(state.confirmProgress(second, 0, 130000))
        assertTrue(state.confirmProgress(second, 500, 130000))
        assertEquals(2, state.snapshot().playingIndex)
        assertEquals(NativeSeriesV3State.Phase.PLAYING, state.snapshot().phase)

        // The second real onCompletion must start a new handoff rather
        // than staying bound to the first accepted next holder/engine.
        state.complete(second)
        assertEquals(NativeSeriesV3State.Phase.COMPLETED, state.snapshot().phase)
        assertTrue(state.mayScopeNext(adapter, 3, 2, 3, 88, true, false))
        assertTrue(state.select(adapter, 3, true, 3, 3))
        assertTrue(state.bind(adapter, Any(), third))
        assertFalse(state.confirmProgress(third, 0, 126000))
        assertTrue(state.confirmProgress(third, 650, 126000))
        assertEquals(3, state.snapshot().playingIndex)
        assertEquals(NativeSeriesV3State.Phase.PLAYING, state.snapshot().phase)
    }

    @Test fun noTrueCompletionDoesNotAuthorizePageSpoof() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        state.rememberAdapter(adapter)
        assertFalse(state.mayScopeNext(adapter, 2, 1, 2, 60, true, false))
        state.complete(Any())
        assertFalse(state.mayScopeNext(Any(), 2, 1, 2, 60, true, false))
    }

    @Test fun onlyHostAcceptedSelectionAndRealPositionProgressCanPlay() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.complete(old)
        assertFalse(state.select(adapter, 2, false, 1, 2))
        assertEquals(NativeSeriesV3State.Phase.COMPLETED, state.snapshot().phase)
        assertTrue(state.select(adapter, 2, true, 2, 2))
        assertFalse(state.canManualSkip())
        val holder = Any()
        assertTrue(state.bind(adapter, holder, next))
        assertTrue(state.playCommand(next))
        assertEquals(NativeSeriesV3State.Phase.STARTING, state.snapshot().phase)
        assertFalse(state.confirmProgress(next, 0L, -1L))
        assertFalse(state.confirmProgress(next, 0L, 60000L))
        assertFalse(state.confirmProgress(next, 100L, 60000L))
        assertFalse(state.confirmProgress(Any(), 800L, 60000L))
        assertTrue(state.confirmProgress(next, 550L, 60000L))
        assertEquals(2, state.snapshot().playingIndex)
        assertNull(state.snapshot().pendingIndex)
        assertTrue(state.canManualSkip())
    }

    @Test fun fakePageAdvanceDoesNotSynthesizeSelection() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        state.rememberAdapter(adapter)
        state.complete(Any())
        assertFalse(state.select(adapter, 2, false, 1, 2))
        assertEquals(NativeSeriesV3State.Phase.COMPLETED, state.snapshot().phase)
    }

    @Test fun explicitPauseCancelsPendingStartAndUserActionWins() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.complete(Any())
        state.select(adapter, 2, true, 2, 2)
        state.bind(adapter, Any(), next)
        state.onPause(next, true)
        assertEquals(NativeSeriesV3State.Phase.PAUSED, state.snapshot().phase)
        assertNull(state.pendingEngine())
        assertFalse(state.confirmProgress(next, 2000L, 10000L))
        assertFalse(state.playCommand(next))
    }

    @Test fun sourceFailureDoesNotRetryPagerOrClaimPlayback() {
        val state = NativeSeriesV3State()
        val adapter = Any()
        val next = Any()
        state.rememberAdapter(adapter)
        state.complete(Any())
        state.select(adapter, 2, true, 2, 2)
        state.bind(adapter, Any(), next)
        state.onError(next, "video_model_failed")
        assertEquals(NativeSeriesV3State.Phase.FAILED, state.snapshot().phase)
        assertEquals(2, state.snapshot().pendingIndex)
        assertEquals("video_model_failed", state.snapshot().error)
        assertFalse(state.canManualSkip())
        state.reset()
        assertEquals(NativeSeriesV3State.Phase.IDLE, state.snapshot().phase)
    }
}
