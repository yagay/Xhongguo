package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBackgroundEpisodeResumeTest {
    @Test fun usesHostNoSurfaceBranchOnlyDuringAcceptedResume() {
        val engine = Any()
        val task = c15.d(engine, c15.FakeSurface(false))
        val player = x05.w(task)
        val result = NativeSeriesPort.resumeAcceptedBackgroundEpisode(player, engine)
        assertTrue(result.invoked)
        assertTrue(result.withoutSurface)
        assertEquals(false, result.surfaceAvailable)
        assertEquals(1, player.playRequests)
        assertEquals(0, player.queuedForSurface)
        // The host loads its VideoModel asynchronously after resume().
        // It reads c15.d.i AGAIN in the deferred PlayCommand, so the
        // flag must survive the synchronous resume call.
        assertTrue(task.i)
        assertTrue(NativeSeriesPort.hasActiveNoSurfaceMode(engine))
        assertTrue(NativeSeriesPort.finishAcceptedBackgroundEpisode(
            engine, "real_progress"))
        assertFalse(task.i)
        assertFalse(NativeSeriesPort.hasActiveNoSurfaceMode(engine))
    }

    @Test fun nativeFlagPersistsForAsyncModelThenRestoresOnAbort() {
        val engine = Any()
        val task = c15.d(engine, c15.FakeSurface(false))
        val player = x05.w(task)
        NativeSeriesPort.resumeAcceptedBackgroundEpisode(player, engine)
        assertTrue(task.i)
        // Simulate the host's later c15.d.c() reading playWithoutSurface.
        assertTrue(task.i)
        assertFalse(NativeSeriesPort.finishAcceptedBackgroundEpisode(
            Any(), "unrelated_player"))
        assertTrue(task.i)
        assertTrue(NativeSeriesPort.finishAcceptedBackgroundEpisode(
            null, "explicit_pause"))
        assertFalse(task.i)
    }

    @Test fun existingSurfaceUsesNormalNativeResume() {
        val engine = Any()
        val task = c15.d(engine, c15.FakeSurface(true))
        val player = x05.w(task)
        val result = NativeSeriesPort.resumeAcceptedBackgroundEpisode(player, engine)
        assertTrue(result.invoked)
        assertFalse(result.withoutSurface)
        assertEquals(1, player.playRequests)
        assertFalse(task.i)
    }

    @Test fun differentEngineNeverGetsNoSurfacePermission() {
        val task = c15.d(Any(), c15.FakeSurface(false))
        val player = x05.w(task)
        val result = NativeSeriesPort.resumeAcceptedBackgroundEpisode(
            player, Any())
        assertTrue(result.invoked)
        assertFalse(result.withoutSurface)
        assertEquals(0, player.playRequests)
        assertEquals(1, player.queuedForSurface)
        assertFalse(task.i)
    }

    @Test fun nullTargetCannotStartNativePlayback() {
        val engine = Any()
        val player = x05.w(c15.d(engine, c15.FakeSurface(false)))
        val result = NativeSeriesPort.resumeAcceptedBackgroundEpisode(player, null)
        assertFalse(result.invoked)
        assertEquals(0, player.playRequests)
    }
}
