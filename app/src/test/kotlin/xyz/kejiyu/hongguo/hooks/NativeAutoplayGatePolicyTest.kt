package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAutoplayGatePolicyTest {
    @Test fun onlyAllowsHostG4InsideVerifiedNativeEofScope() {
        assertTrue(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = true, genuineBackground = true,
            sameController = true, verifiedEofSelection = true,
            userCommandInProgress = false))
        assertFalse(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = false, genuineBackground = true,
            sameController = true, verifiedEofSelection = true,
            userCommandInProgress = false))
        assertFalse(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = true, genuineBackground = false,
            sameController = true, verifiedEofSelection = true,
            userCommandInProgress = false))
        assertFalse(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = true, genuineBackground = true,
            sameController = false, verifiedEofSelection = true,
            userCommandInProgress = false))
        assertFalse(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = true, genuineBackground = true,
            sameController = true, verifiedEofSelection = false,
            userCommandInProgress = false))
        assertFalse(NativeAutoplayGatePolicy.shouldAllow(
            hostBlocked = true, genuineBackground = true,
            sameController = true, verifiedEofSelection = true,
            userCommandInProgress = true))
    }
}
