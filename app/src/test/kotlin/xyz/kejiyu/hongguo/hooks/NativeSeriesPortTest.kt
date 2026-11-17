package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSeriesPortTest {
    private class Pager(var page: Int) {
        fun getCurrentItem(): Int = page
    }

    // The real APK names D/k/h in different superclasses, so verify
    // inherited private fields and business method dispatch.
    private open class BaseAdapter {
        @JvmField var D = 2
        @JvmField val h = listOf(Any(), Any(), Any(), Any(), Any())
        @JvmField val k = Pager(2)
        fun A1(): Boolean {
            if (D <= 0) return false
            D--
            k.page = D
            return true
        }
    }
    private class FullAdapter : BaseAdapter() {
        fun g(): Boolean {
            if (D >= h.lastIndex) return false
            D++
            k.page = D
            return true
        }
    }

    @Test fun usesOfficialNativeControlsOnceWithoutPagerPatch() {
        val adapter = FullAdapter()
        assertTrue(NativeSeriesPort.skip(adapter, 1).accepted)
        assertEquals(3, adapter.D)
        assertTrue(NativeSeriesPort.skip(adapter, -1).accepted)
        assertEquals(2, adapter.D)
    }

    @Test fun checksBoundariesBeforeInvokingHost() {
        val adapter = FullAdapter()
        adapter.D = 0
        adapter.k.page = 0
        val outcome = NativeSeriesPort.skip(adapter, -1)
        assertFalse(outcome.invoked)
        assertEquals("episode_boundary", outcome.reason)
        assertEquals(0, adapter.D)
    }

    @Test fun rejectsStalePagerBusinessMismatch() {
        val adapter = FullAdapter()
        adapter.k.page = 1
        val outcome = NativeSeriesPort.skip(adapter, 1)
        assertFalse(outcome.invoked)
        assertEquals("pager_not_aligned", outcome.reason)
        assertEquals(2, adapter.D)
    }
}
