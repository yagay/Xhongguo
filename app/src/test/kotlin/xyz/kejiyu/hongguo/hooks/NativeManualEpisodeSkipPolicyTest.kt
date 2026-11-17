package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class NativeManualEpisodeSkipPolicyTest {
    private fun permitted(
        from: Int = 4, page: Int = 5, target: Int = 5,
        expected: Int = 5, count: Int = 15,
        same: Boolean = true, background: Boolean = true,
        idle: Boolean = true, pause: Boolean = false,
        generation: Boolean = true, age: Long = 100
    ) = NativeManualEpisodeSkipPolicy.mayScope(
        sameAdapter = same, background = background,
        nativeSelectionIdle = idle, explicitTransportPause = pause,
        originalIndex = from, pagerIndex = page,
        requestedIndex = target, mediaTargetIndex = expected,
        loadedCount = count, sameGeneration = generation, ageMs = age)

    @Test fun userNextAndPreviousAreBothAllowedOnlyAtTheirOwnTarget() {
        assertTrue(permitted())
        assertTrue(permitted(from = 5, page = 4, target = 4, expected = 4))
        assertTrue(permitted(from = 5, page = 5, target = 4, expected = 4))
        assertFalse(permitted(target = 6))
        assertFalse(permitted(expected = 6))
    }

    @Test fun noAccidentalPagerOrUnrelatedAdapterSelection() {
        assertFalse(permitted(same = false))
        assertFalse(permitted(page = 7))
        assertFalse(permitted(from = 0, target = -1, expected = -1))
        assertFalse(permitted(from = 14, target = 15, expected = 15))
        assertFalse(permitted(from = 5, target = 2, expected = 2))
    }

    @Test fun noGrantInForegroundForStaleOrPausedRequest() {
        assertFalse(permitted(background = false))
        assertFalse(permitted(idle = false))
        assertFalse(permitted(pause = true))
        assertFalse(permitted(generation = false))
        assertFalse(permitted(age = 2501))
        assertFalse(permitted(age = -1))
        assertTrue(permitted(age = 2500))
    }
}
