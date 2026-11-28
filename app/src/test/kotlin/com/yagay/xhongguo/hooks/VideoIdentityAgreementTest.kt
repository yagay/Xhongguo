package com.yagay.xhongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class VideoIdentityAgreementTest {
    @Test fun exactIdAcceptsImmediately() {
        assertTrue(VideoIdentityAgreement().accept("a", "v1", "v1", false, 0))
    }
    @Test fun mismatchRequiresIndependentStableSelection() {
        val verifier = VideoIdentityAgreement(800)
        assertFalse(verifier.accept("a", "engine", "page", false, 0))
        assertFalse(verifier.accept("a", "engine", "page", true, 100))
        assertFalse(verifier.accept("a", "engine", "page", true, 899))
        assertTrue(verifier.accept("a", "engine", "page", true, 900))
    }
    @Test fun changedPageRestartsStabilityWindow() {
        val verifier = VideoIdentityAgreement()
        assertFalse(verifier.accept("a", "e", "a", true, 10))
        assertFalse(verifier.accept("b", "e", "b", true, 1000))
        assertFalse(verifier.accept("b", "e", "b", true, 1799))
        assertTrue(verifier.accept("b", "e", "b", true, 1800))
        verifier.clear()
        assertFalse(verifier.accept("b", "e", "b", true, 1900))
    }
}
