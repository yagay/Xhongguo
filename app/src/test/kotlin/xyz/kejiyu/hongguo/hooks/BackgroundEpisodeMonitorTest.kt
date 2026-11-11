package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundEpisodeMonitorTest {
    @Test fun exactEndNeedsStableThreeSecondProof() {
        val monitor = BackgroundEpisodeMonitor()
        assertNull(monitor.onSample(100_000, 100_000, true, true, 1000L))
        assertNull(monitor.onSample(100_000, 100_000, true, true, 3200L))
        assertEquals("no_completion_callback",
            monitor.onSample(100_000, 100_000, true, true, 4200L))
        assertNull(monitor.onSample(100_000, 100_000, true, true, 7200L))
    }

    @Test fun completionObservedButNoNextIsDiagnosedSeparately() {
        val monitor = BackgroundEpisodeMonitor()
        monitor.onNativeCompletion()
        assertNull(monitor.onSample(120_000, 120_000, true, true, 1000L))
        assertEquals("completion_but_no_next",
            monitor.onSample(120_000, 120_000, true, true, 4100L))
    }

    @Test fun userPauseAndNewEpisodeResetStall() {
        val monitor = BackgroundEpisodeMonitor()
        assertNull(monitor.onSample(120_000, 120_000, true, true, 1000L))
        assertNull(monitor.onSample(120_000, 120_000, true, false, 4200L))
        assertNull(monitor.onSample(1000L, 140_000, true, true, 4900L))
        assertNull(monitor.onSample(140_000, 140_000, true, true, 5000L))
    }
}
