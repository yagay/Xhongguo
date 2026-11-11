package xyz.kejiyu.hongguo.hooks

import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import xyz.kejiyu.hongguo.FeatureTrace
import xyz.kejiyu.hongguo.LogUtil

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
    private val transitionCallbacks =
        AtomicReference<List<Runnable>>(emptyList())
    private val trailingDtoCallback = AtomicReference<Runnable?>(null)

    fun onPlaybackTransition(reason: String) {
        val epoch = generation.incrementAndGet()
        FeatureTrace.event("media", "refresh_schedule", "queued",
            "reason=" + reason + ",generation=" + epoch)
        dtoEvent.incrementAndGet()
        transitionCallbacks.getAndSet(emptyList()).forEach(main::removeCallbacks)
        trailingDtoCallback.getAndSet(null)?.let(main::removeCallbacks)
        // Cover late original-app presenter swaps and final-episode autoplay
        // without leaving hundreds of obsolete callbacks after rapid swipes.
        val delays = longArrayOf(160L, 650L, 1600L, 3500L,
            7500L, 15000L, 30000L)
        val callbacks = delays.map { delay ->
            Runnable {
                if (generation.get() == epoch && isActive()) {
                    safelyRefresh("transition:$reason:$delay")
                }
            }
        }
        transitionCallbacks.set(callbacks)
        callbacks.forEachIndexed { index, callback ->
            main.postDelayed(callback, delays[index])
        }
    }

    fun onNativeDataChanged() {
        val epoch = generation.get()
        val request = dtoEvent.incrementAndGet()
        FeatureTrace.event("media", "dto_refresh", "queued",
            "generation=" + epoch, minIntervalMs = 2500L)
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
        val trailing = Runnable {
            if (epoch == generation.get() && request == dtoEvent.get() &&
                isActive()) {
                safelyRefresh("dto-trailing")
            }
        }
        trailingDtoCallback.getAndSet(trailing)?.let(main::removeCallbacks)
        main.postDelayed(trailing, 240L)
    }

    fun cancel() {
        generation.incrementAndGet()
        dtoEvent.incrementAndGet()
        transitionCallbacks.getAndSet(emptyList()).forEach(main::removeCallbacks)
        trailingDtoCallback.getAndSet(null)?.let(main::removeCallbacks)
    }

    private fun safelyRefresh(reason: String) {
        FeatureTrace.event("media", "refresh_invoke", "attempt",
            "reason=" + reason, minIntervalMs = 2500L)
        try {
            refresh(reason)
        } catch (e: Throwable) {
            FeatureTrace.event("media", "refresh_invoke", "fail",
                "errorType=" + e.javaClass.simpleName)
            LogUtil.warn("系统媒体: 元数据调度独立失败 " +
                e.javaClass.simpleName + " reason=" + reason)
            LogUtil.incr("systemMediaMetadataRefreshFailure")
        }
    }
}
