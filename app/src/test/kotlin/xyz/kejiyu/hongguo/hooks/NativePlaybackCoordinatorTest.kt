package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackCoordinatorTest {
    @Test fun eofSurvivesTimelineMonitorResetUntilNewHolderPlays() {
        val state = NativePlaybackCoordinator()
        val monitor = BackgroundEpisodeMonitor()
        val oldEngine = Any()
        val nextEngine = Any()
        val adapter = Any()
        state.onNativePlay(oldEngine)
        monitor.onNativeCompletion()
        state.onNativeCompletion(oldEngine)

        // A stale/invalid duration is common while the next video loads.
        // This legitimately resets the stall diagnostic, NOT native EOF.
        monitor.onSample(
            positionMs = -1L, durationMs = -1L,
            backgroundActive = true, playing = true, nowMs = 2000L)
        assertFalse(monitor.hasNativeCompletion())
        assertTrue(state.hasPendingNativeCompletion(oldEngine))

        state.onNativeSelection(adapter, 1, accepted = true)
        state.onNativeHolderBound(Any(), nextEngine)
        assertTrue(state.canContinueAcceptedSelection(adapter, 1, oldEngine))
        assertTrue(state.hasPendingNativeCompletion(oldEngine))
        state.onNativePlay(nextEngine)
        assertFalse(state.hasPendingNativeCompletion(oldEngine))
        assertFalse(state.canContinueAcceptedSelection(adapter, 1, oldEngine))
    }

    @Test fun playbackWithoutCompletionNeverHasEofPermission() {
        val state = NativePlaybackCoordinator()
        val oldEngine = Any()
        val adapter = Any()
        state.onNativePlay(oldEngine)
        state.onNativeSelection(adapter, 1, accepted = true)
        assertFalse(state.hasPendingNativeCompletion(oldEngine))
        assertFalse(state.canContinueAcceptedSelection(adapter, 1, oldEngine))
    }

    @Test fun earlyPlayCommandDoesNotClaimBackgroundEpisodeIsAudible() {
        val state = NativePlaybackCoordinator()
        val old = Any()
        val next = Any()
        val adapter = Any()
        state.onNativePlay(old)
        state.onNativeCompletion(old)
        state.onNativeSelection(adapter, 3, accepted = true)
        state.onNativeHolderBound(Any(), next)
        state.onNativePlay(next, awaitingProgress = true)
        assertEquals(NativePlaybackCoordinator.Phase.STARTING,
            state.snapshot().phase)
        assertNull(state.verifiedPlayingIndex(next, adapter))
        assertFalse(state.canDispatchEpisodeCommand())
        assertFalse(state.onNativeProgress(next, 0L, -1L))
        assertFalse(state.onNativeProgress(next, 0L, 90000L))
        assertFalse(state.onNativeProgress(next, 100L, 90000L))
        assertTrue(state.onNativeProgress(next, 650L, 90000L))
        assertEquals(NativePlaybackCoordinator.Phase.PLAYING,
            state.snapshot().phase)
        assertEquals(3, state.verifiedPlayingIndex(next, adapter))
        assertTrue(state.canDispatchEpisodeCommand())
    }

    @Test fun cannotConfirmProgressForWrongEngineOrStalledPosition() {
        val state = NativePlaybackCoordinator()
        val engine = Any()
        state.onNativePlay(engine, awaitingProgress = true)
        assertFalse(state.onNativeProgress(Any(), 900L, 8000L))
        assertFalse(state.onNativeProgress(engine, -1L, 8000L))
        assertFalse(state.onNativeProgress(engine, 1200L, 8000L))
        assertFalse(state.onNativeProgress(engine, 1200L, 8000L))
        assertEquals(NativePlaybackCoordinator.Phase.STARTING,
            state.snapshot().phase)
        assertTrue(state.onNativeProgress(engine, 1650L, 8000L))
    }

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

    @Test fun duplicateS4DoesNotClearAlreadyBoundNextHolder() {
        val state = NativePlaybackCoordinator()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.onNativePlay(old)
        state.onNativeCompletion(old)
        state.onNativeSelection(adapter, 4, accepted = true)
        state.onNativeHolderBound(Any(), next)
        state.onNativeSelection(adapter, 4, accepted = true)
        assertEquals(NativePlaybackCoordinator.Phase.BOUND, state.snapshot().phase)
        state.onNativePlay(next)
        assertEquals(4, state.verifiedPlayingIndex(next, adapter))
    }

    @Test fun pendingAutoplayCannotBeSkippedAgainBeforePlaybackBegins() {
        val state = NativePlaybackCoordinator()
        val old = Any()
        val next = Any()
        val adapter = Any()
        state.onNativePlay(old)
        assertTrue(state.canDispatchEpisodeCommand())
        state.onNativeCompletion(old)
        assertFalse(state.canDispatchEpisodeCommand())
        state.onNativeSelection(adapter, 2, accepted = true)
        assertFalse(state.canDispatchEpisodeCommand())
        state.onNativeHolderBound(Any(), next)
        assertFalse(state.canDispatchEpisodeCommand())
        state.onNativePlay(next)
        assertTrue(state.canDispatchEpisodeCommand())
    }

    @Test fun initialNativeHolderCanConfirmAlreadyPlayingEngine() {
        val state = NativePlaybackCoordinator()
        val adapter = Any()
        val engine = Any()
        state.onNativePlay(engine)
        state.onNativeSelection(adapter, 0, accepted = true)
        state.onNativeHolderBound(Any(), engine)
        assertEquals(0, state.verifiedPlayingIndex(engine, adapter))
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

    @Test fun handlesEnginePlayBeforeAfterHookReportsHolderBinding() {
        val state = NativePlaybackCoordinator()
        val adapter = Any()
        val old = Any()
        val next = Any()
        state.onNativePlay(old)
        state.onNativeCompletion(old)
        state.onNativeSelection(adapter, 3, accepted = true)
        state.onNativePlay(next)
        assertNull(state.verifiedPlayingIndex(next, adapter))
        state.onNativeHolderBound(Any(), next)
        assertEquals(3, state.verifiedPlayingIndex(next, adapter))
        assertNull(state.snapshot().pendingIndex)
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
