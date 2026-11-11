package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiScanPolicyTest {
    @Test fun noExpensiveScanForDisabledOrPausedControls() {
        assertFalse(UiScanPolicy.needsViewTree(false, true, true, true, true, false))
        assertFalse(UiScanPolicy.needsViewTree(true, true, true, false, false, true))
        assertTrue(UiScanPolicy.needsViewTree(true, false, true, false, false, false))
    }

    @Test fun adaptivePollingIsNeverTheOld150msLoop() {
        assertEquals(500L, UiScanPolicy.nextDelayMs(true, true, true))
        assertEquals(1_000L, UiScanPolicy.nextDelayMs(true, true, false))
        assertEquals(1_500L, UiScanPolicy.nextDelayMs(true, false, true))
        assertEquals(2_000L, UiScanPolicy.nextDelayMs(false, true, true))
    }
}
