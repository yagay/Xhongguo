package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackCoordinatorTest {
    @Test fun confirmsNextEpisodeOnlyAfterActualBoundEnginePlays() {
        val state = NativePlaybackCoordinator()
        val old = Any()
        val next = Any()
        val holder = Any()
        val adapter = Any()

        state.onNativePlay(old)
        state.onNativeCompletion(old)
        assertEquals(NativePlaybackCoordinator.Phase.COMPLETED,
            state.snapshot().phase)
        state.onNativeSelection(adapter, 20, accepted = true)
        assertNull(state.verifiedPlayingIndex(old, adapter))

        state.onNativeHolderBound(holder, next)
        assertEquals(NativePlaybackCoordinator.Phase.BOUND, state.snapshot().phase)
        assertNull(state.verifiedPlayingIndex(old, adapter))

        state.onNativePlay(next)
        assertEquals(NativePlaybackCoordinator.Phase.PLAYING, state.snapshot().phase)
        assertEquals(20, state.verifiedPlayingIndex(next, adapter))
        assertNull(state.snapshot().pendingIndex)
    }

    @Test fun ignoresPreloadedEngineAndUnacceptedSelection() {
        val state = NativePlaybackCoordinator()
        val adapter = Any()
        val old = Any()
        state.onNativePlay(old)
        state.onNativeSelection(adapter, 5, accepted = false)
        assertNull(state.snapshot().selectedIndex)
        state.onNativeSelection(adapter, 5, accepted = true)
        state.onNativePlay(Any()) // no official x05.w holder binding
        assertNull(state.snapshot().playingIndex)
        assertNull(state.verifiedPlayingIndex(old, adapter))
    }

    @Test fun explicitPauseCancelsPendingNativeEpisode() {
        val state = NativePlaybackCoordinator()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.onNativePlay(old)
        state.onNativeCompletion(old)
        state.onNativeSelection(adapter, 7, accepted = true)
        state.onNativeHolderBound(Any(), next)
        state.onExplicitPause()
        assertEquals(NativePlaybackCoordinator.Phase.PAUSED, state.snapshot().phase)
        assertNull(state.snapshot().pendingIndex)
        state.onNativePlay(next)
        assertNull(state.snapshot().playingIndex)
    }

    @Test fun completionNeverInventsAnEpisodeOrRepeatsGeneration() {
        val state = NativePlaybackCoordinator()
        val engine = Any()
        state.onNativePlay(engine)
        val g = state.snapshot().generation
        state.onNativeCompletion(engine)
        assertTrue(state.snapshot().generation > g)
        val completed = state.snapshot().generation
        state.onNativeCompletion(engine)
        assertEquals(completed, state.snapshot().generation)
        assertFalse(state.canDispatchEpisodeCommand())
    }
}
