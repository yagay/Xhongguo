package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeG4GatePolicyTest {
    private fun permit(
        blocked: Boolean = true, bg: Boolean = true,
        cmd: Boolean = false, controller: Boolean = true,
        gen: Boolean = true, accepted: Boolean = true,
        elapsed: Long = 700L
    ) = NativeG4GatePolicy.mayRelax(
        blocked, bg, cmd, controller, gen, accepted, elapsed)

    @Test fun permitsOneVerifiedBackgroundHostG4AfterS4() {
        assertTrue(permit())
        assertTrue(permit(elapsed = 2500L))
    }

    @Test fun rejectsEverythingOutsideTheOriginalHostHandoff() {
        assertFalse(permit(blocked = false))
        assertFalse(permit(bg = false))
        assertFalse(permit(cmd = true))
        assertFalse(permit(controller = false))
        assertFalse(permit(gen = false))
        assertFalse(permit(accepted = false))
        assertFalse(permit(elapsed = -1L))
        assertFalse(permit(elapsed = 2501L))
    }
}
