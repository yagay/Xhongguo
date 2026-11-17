package xyz.kejiyu.hongguo.hooks

/**
 * Distinguish the official episode ordinal (vidIndex) from a position in a
 * recycled local list (indexInList). The latter may restart at zero in the
 * middle of a series, so it must not silently become "第1集".
 *
 * Android-independent so the rules can be regression-tested on the JVM.
 */
internal object SeriesEpisodeNumberPolicy {
    fun resolve(
        indexInList: Long?,
        vidIndex: Long?,
        total: Long?,
        fullListVerified: Boolean
    ): Long? {
        val maximum = total?.takeIf { it in 1L..5000L } ?: 5000L
        val explicit = vidIndex?.takeIf { it in 1L..maximum }
        val fromFullList = if (fullListVerified)
            indexInList?.takeIf { it >= 0L && it < maximum }?.plus(1L)
        else null

        // When the official full list is available, equality between
        // indexInList and vidIndex indicates a zero-based source index.
        if (fromFullList != null && explicit != null &&
            (vidIndex == indexInList || explicit == fromFullList))
            return fromFullList

        // A partial/recycled list's position must never beat an explicit
        // server episode number; a zero value alone is not enough to
        // infer that this is the actual first episode.
        return explicit ?: fromFullList
    }

    fun label(number: Long?, total: Long?): String? {
        val current = number?.takeIf { it in 1L..5000L } ?: return null
        if (total != null && total in 1L..5000L && current > total)
            return null
        return "第" + current + "集" +
            (if (total != null && total in current..5000L)
                " · 全" + total + "集" else "")
    }
}
