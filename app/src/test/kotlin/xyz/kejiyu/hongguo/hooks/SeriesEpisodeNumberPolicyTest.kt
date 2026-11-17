package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesEpisodeNumberPolicyTest {
    private val policy = SeriesEpisodeNumberPolicy

    @Test fun usesOfficialOrdinalRatherThanRecycledFirstPosition() {
        assertEquals(21L, policy.resolve(
            indexInList = 0L, vidIndex = 21L,
            total = 80L, fullListVerified = false))
        assertEquals("第21集 · 全80集", policy.label(21L, 80L))
    }

    @Test fun doesNotInventFirstEpisodeFromUnverifiedLocalIndex() {
        assertNull(policy.resolve(
            indexInList = 0L, vidIndex = null,
            total = 80L, fullListVerified = false))
        assertNull(policy.resolve(
            indexInList = 0L, vidIndex = 0L,
            total = 80L, fullListVerified = false))
    }

    @Test fun acceptsFullPlaylistNativePosition() {
        assertEquals(11L, policy.resolve(
            indexInList = 10L, vidIndex = null,
            total = 80L, fullListVerified = true))
        assertEquals(11L, policy.resolve(
            indexInList = 10L, vidIndex = 10L,
            total = 80L, fullListVerified = true))
        assertEquals(11L, policy.resolve(
            indexInList = 10L, vidIndex = 11L,
            total = 80L, fullListVerified = true))
    }

    @Test fun rejectsOutOfBoundsOrStaleEpisode() {
        assertNull(policy.resolve(
            indexInList = 0L, vidIndex = 81L,
            total = 80L, fullListVerified = false))
        assertNull(policy.label(81L, 80L))
        assertNull(policy.label(null, 80L))
    }

    @Test fun unknownTotalCanStillUseExplicitOrdinal() {
        assertEquals(27L, policy.resolve(
            indexInList = 0L, vidIndex = 27L,
            total = null, fullListVerified = false))
        assertEquals("第27集", policy.label(27L, null))
    }
}
