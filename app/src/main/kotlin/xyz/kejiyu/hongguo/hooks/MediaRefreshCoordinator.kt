package xyz.kejiyu.hongguo.hooks

import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * One metadata refresh lane for home feed, full-series and autoplay.
 *
 * Host DTOs can arrive before or long after engine.play(). Dropping an event
 * because it arrived inside a fixed throttle window leaves the system media
 * card permanently empty. This coordinator uses a leading + trailing DTO
 * debounce and a bounded, generation-checked recovery sequence.
 *
 * It never starts video playback, downloads new content or scans a view tree
 * from a worker thread. All refresh callbacks execute on the main looper.
 */
internal class MediaRefreshCoordinator(
    private val main: Handler,
    private val isActive: () -> Boolean,
    private val refresh: (String) -> Unit
) {
    private val generation = AtomicLong(0L)
    private val dtoEvent = AtomicLong(0L)
    private val lastLeadingDtoAt = AtomicLong(0L)

    fun onPlaybackTransition(reason: String) {
        val epoch = generation.incrementAndGet()
        dtoEvent.incrementAndGet() // invalidate pending DTO pulses
        // Cover a delayed original-app presenter swap, network/JSON callback,
        // background playback, and final-episode autoplay without polling forever.
        for (delay in longArrayOf(160L, 650L, 1600L, 3500L,
            7500L, 15000L, 30000L)) {
            main.postDelayed({
                if (generation.get() == epoch && isActive()) {
                    safelyRefresh("transition:$reason:$delay")
                }
            }, delay)
        }
    }

    fun onNativeDataChanged() {
        val epoch = generation.get()
        val request = dtoEvent.incrementAndGet()
        val now = SystemClock.uptimeMillis()
        // Leading refresh prevents a continuous stream of preloaded DTOs from
        // starving the active video. The trailing refresh captures the last DTO.
        val previous = lastLeadingDtoAt.get()
        if (now - previous >= 850L &&
            lastLeadingDtoAt.compareAndSet(previous, now)) {
            main.postDelayed({
                if (epoch == generation.get() && isActive()) {
                    safelyRefresh("dto-leading")
                }
            }, 80L)
        }
        main.postDelayed({
            if (epoch == generation.get() && request == dtoEvent.get() &&
                isActive()) {
                safelyRefresh("dto-trailing")
            }
        }, 240L)
    }

    fun cancel() {
        generation.incrementAndGet()
        dtoEvent.incrementAndGet()
    }

    private fun safelyRefresh(reason: String) {
        try {
            refresh(reason)
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 元数据调度独立失败 " +
                e.javaClass.simpleName + " reason=" + reason)
            LogUtil.incr("systemMediaMetadataRefreshFailure")
        }
    }
}
