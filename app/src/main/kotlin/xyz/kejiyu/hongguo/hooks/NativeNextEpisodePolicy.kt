package xyz.kejiyu.hongguo.hooks

/**
 * Pure host-data identity gate, independent of Activity, ViewPager and
 * MediaSession state. Never infer "next episode" from a non-null video alone.
 */
internal object NativeNextEpisodePolicy {
    enum class Result {
        MISSING_IDENTITY,
        DIFFERENT_SERIES,
        SAME_VIDEO,
        NON_ADJACENT_INDEX,
        VERIFIED_NEXT
    }

    fun check(
        currentSeries: String?,
        currentVideo: String?,
        currentIndex: Int?,
        nextSeries: String?,
        nextVideo: String?,
        nextIndex: Int?
    ): Result {
        if (currentSeries.isNullOrBlank() || currentVideo.isNullOrBlank() ||
            nextSeries.isNullOrBlank() || nextVideo.isNullOrBlank() ||
            currentIndex == null || nextIndex == null) return Result.MISSING_IDENTITY
        if (currentSeries != nextSeries) return Result.DIFFERENT_SERIES
        if (currentVideo == nextVideo) return Result.SAME_VIDEO
        if (currentIndex !in 0..4998 || nextIndex != currentIndex + 1)
            return Result.NON_ADJACENT_INDEX
        return Result.VERIFIED_NEXT
    }
}
