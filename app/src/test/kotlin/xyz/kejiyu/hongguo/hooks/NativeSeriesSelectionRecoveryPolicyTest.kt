package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSeriesSelectionRecoveryPolicyTest {
    private fun allowed(
        origin: NativeSeriesSelectionRecoveryPolicy.Origin,
        sameAdapter: Boolean = true,
        genuineBackground: Boolean = true,
        nativeEofConfirmed: Boolean = true,
        userPaused: Boolean = false,
        d: Int = 2,
        page: Int = 3,
        target: Int = 3,
        count: Int = 88,
        alreadySelected: Boolean = false
    ) = NativeSeriesSelectionRecoveryPolicy.mayReconcile(
        origin, sameAdapter, genuineBackground, nativeEofConfirmed,
        userPaused, d, page, target, count, alreadySelected)

    @Test fun reproducesFirstHandoffThenPageAheadAtSecondEof() {
        // Log 20:24:51: original s4(2) accepted, resulting D=2.
        // Log 20:25:11: media g() moved pager to 3 but left D=2.
        // Log 20:25:21: original U1 returned without s4(3).
        assertTrue(allowed(NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF))
        assertTrue(allowed(NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP,
            nativeEofConfirmed = false))
    }

    @Test fun neverMovesToAnotherVideoOrSkipsTwice() {
        val eof = NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF
        assertFalse(allowed(eof, page = 2)) // No page-ahead evidence.
        assertFalse(allowed(eof, page = 4)) // Never jump two episodes.
        assertFalse(allowed(eof, target = 4)) // Never skip requested episode.
        assertFalse(allowed(eof, d = 87, page = 88,
            target = 88)) // End of loaded series.
        assertFalse(allowed(eof, alreadySelected = true))
    }

    @Test fun neverResurrectsAfterUserPauseOrUnverifiedEof() {
        val eof = NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF
        assertFalse(allowed(eof, nativeEofConfirmed = false))
        assertFalse(allowed(eof, userPaused = true))
        assertFalse(allowed(eof, sameAdapter = false))
        assertFalse(allowed(eof, genuineBackground = false))
        assertFalse(allowed(eof, count = 1))
    }

    @Test fun manualRecoveryStillRequiresOneRealAlreadyMovedPage() {
        val manual = NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP
        assertTrue(allowed(manual, nativeEofConfirmed = false))
        assertFalse(allowed(manual, page = 2))
        assertFalse(allowed(manual, page = 4))
        assertFalse(allowed(manual, genuineBackground = false))
        assertFalse(allowed(manual, userPaused = true))
    }
}
