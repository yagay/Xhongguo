package xyz.kejiyu.hongguo.hooks

import android.app.ActivityManager
import android.os.SystemClock
import xyz.kejiyu.hongguo.FeatureTrace
import java.util.concurrent.atomic.AtomicLong

/**
 * Original Hongguo 7.3.5.32 feed-to-inner-series handoff observer.
 *
 * APK verification: adapter.e.J2(boolean,String,Bundle,boolean,Integer,
 * Boolean,Boolean) returns void and contains official player-sharing
 * preparation followed by ShortSeriesApi.openShortSeriesActivityForResult.
 *
 * This class deliberately never calls J2, changes its parameters, modifies
 * an Activity launch, touches a Surface or creates a second video engine.
 * A J2 invocation is NOT proof of a successful background media handoff.
 */
internal object NativePlayerHandoff {
    private val sequence = AtomicLong()
    private val activeCall = ThreadLocal<Long?>()

    private fun isBackground(): Boolean = try {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        process.importance >=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
    } catch (_: Throwable) { false }

    fun entering(args: Array<Any?>): Long {
        val callId = sequence.incrementAndGet()
        activeCall.set(callId)
        // Do not log the string, Bundle, or opaque user/video identifiers.
        val keys = listOf(0, 3, 5, 6)
        val flags = keys.joinToString(",") { index ->
            "b$index=" + when (args.getOrNull(index)) {
                true -> "true"
                false -> "false"
                else -> "unknown"
            }
        }
        FeatureTrace.event("native_handoff", "j2_enter", "observed",
            "call=$callId,background=" + isBackground() +
                ",args=" + args.size + "," + flags +
                ",bundlePresent=" + (args.getOrNull(2) != null))
        return callId
    }

    fun exiting(callId: Long, elapsedMs: Long, failed: Boolean) {
        if (activeCall.get() == callId) activeCall.remove()
        FeatureTrace.event("native_handoff", "j2_exit",
            if (failed) "error" else "observed",
            "call=$callId,background=" + isBackground() +
                ",elapsedMs=$elapsedMs,hostException=$failed")
    }

    fun timed(block: () -> Any?, args: Array<Any?>): Any? {
        val start = SystemClock.elapsedRealtime()
        val callId = entering(args)
        var failed = true
        try {
            val result = block()
            failed = false
            return result
        } finally {
            exiting(callId,
                (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L),
                failed)
        }
    }
}
