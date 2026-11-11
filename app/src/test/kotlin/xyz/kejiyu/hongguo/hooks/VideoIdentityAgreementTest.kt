package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoIdentityAgreementTest {
    @Test fun exactOrMissingEngineIdCanUseVisiblePage() {
        val check = VideoIdentityAgreement()
        assertTrue(check.accept("page-1", null, "vid1", false, 1000L))
        assertTrue(check.accept("page-1", "vid1", "vid1", false, 1100L))
    }

    @Test fun mismatchedIdMustBeIndependentAndStable() {
        val check = VideoIdentityAgreement()
        assertFalse(check.accept("page-1", "engine-route-A", "saas1",
            true, 1000L))
        assertFalse(check.accept("page-1", "engine-route-A", "saas1",
            true, 1700L))
        assertTrue(check.accept("page-1", "engine-route-A", "saas1",
            true, 1900L))
    }

    @Test fun aSwipeOrEngineChangeInvalidatesMismatchFallback() {
        val check = VideoIdentityAgreement()
        assertFalse(check.accept("page-1", "different", "vid1", true, 1000L))
        assertFalse(check.accept("page-2", "different", "vid2", true, 2500L))
        assertFalse(check.accept("page-2", "different", "vid2", false, 2700L))
        assertFalse(check.accept("page-2", "different", "vid2", true, 2800L))
        assertTrue(check.accept("page-2", "different", "vid2", true, 3600L))
        assertFalse(check.accept(null, "different", null, true, 3700L))
    }
}
