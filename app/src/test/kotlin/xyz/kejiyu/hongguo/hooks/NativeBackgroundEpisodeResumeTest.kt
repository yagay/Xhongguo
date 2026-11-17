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
        assertFalse(task.i) // Native field restored after synchronous resume.
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
