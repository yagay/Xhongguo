package xyz.kejiyu.hongguo

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead structured diagnostics shared by host Hooks and module UI.
 *
 * Format: XH_TRACE feature=... stage=... result=... flow=... seq=...
 * Every line is safe to process without reflection or external I/O.
 *
 * No titles, account details, URLs, native video IDs, tokens or full
 * exception messages should be included. Call sites log booleans/counts.
 */
object FeatureTrace {
    @Volatile private var verbose = false
    private val sequence = AtomicLong()
    private val nextFlow = AtomicLong()
    private val lastEventAt = ConcurrentHashMap<String, Long>()

    private class CounterWindow {
        var count = 0L
        var pending = 0L
        var lastLogAt = 0L
    }
    private val counterWindows = ConcurrentHashMap<String, CounterWindow>()

    fun configure(detailEnabled: Boolean) {
        if (verbose == detailEnabled) return
        verbose = detailEnabled
        event("diagnostics", "detail_mode", "changed",
            "enabled=" + detailEnabled)
    }

    fun newFlow(feature: String, reason: String): Long {
        val flow = nextFlow.incrementAndGet()
        event(feature, "flow_start", "observed",
            "reason=" + safe(reason, 48), flow = flow)
        return flow
    }

    fun event(
        feature: String, stage: String, result: String,
        detail: String = "", flow: Long = 0L, minIntervalMs: Long = 0L
    ) {
        val category = safe(feature, 32)
        val step = safe(stage, 48)
        val state = safe(result, 24)
        if (minIntervalMs > 0L) {
            val key = category + ":" + step + ":" + state
            val now = SystemClock.elapsedRealtime()
            // Do not update the timestamp for a suppressed event: otherwise
            // a busy hook could silence diagnostics indefinitely.
            val before = lastEventAt[key]
            if (before != null && now - before in 0L until minIntervalMs) return
            lastEventAt[key] = now
        }
        val label = buildString {
            append("XH_TRACE feature=").append(category)
            append(" stage=").append(step)
            append(" result=").append(state)
            append(" flow=").append(flow.coerceAtLeast(0L))
            append(" seq=").append(sequence.incrementAndGet())
            append(" pid=").append(Process.myPid())
            append(" monoMs=").append(SystemClock.elapsedRealtime())
            if (detail.isNotBlank()) append(" detail=").append(safe(detail, 160))
        }
        if (result == "fail" || result == "error" || result == "degraded")
            LogUtil.warn(label)
        else LogUtil.info(label)
    }

    /**
     * Keep old counters unchanged; additionally emit first observation and a
     * throttled aggregate for each named functional signal. Generic inflate
     * and per-frame scans never create new trace events.
     */
    fun counter(signal: String) {
        val feature = FeatureTraceCatalog.forCounter(signal) ?: return
        val now = SystemClock.elapsedRealtime()
        val window = counterWindows.computeIfAbsent(signal) { CounterWindow() }
        var snapshot: Pair<Long, Long>? = null
        synchronized(window) {
            window.count++
            window.pending++
            val interval = if (verbose) 3000L else 15000L
            if (window.count == 1L || now - window.lastLogAt >= interval) {
                snapshot = window.count to window.pending
                window.pending = 0L
                window.lastLogAt = now
            }
        }
        val counts = snapshot ?: return
        event(feature, "counter", FeatureTraceCatalog.outcomeForCounter(signal),
            "signal=" + signal + ",total=" + counts.first +
                ",sinceLast=" + counts.second)
    }

    /**
     * Optional targeted Hook hit counters. This records that the callback
     * actually ran; it does NOT assert that user-visible behavior succeeded.
     */
    fun hookHit(id: String) {
        val feature = FeatureTraceCatalog.forHookId(id) ?: return
        val signal = "hook:" + id.take(80)
        val now = SystemClock.elapsedRealtime()
        val window = counterWindows.computeIfAbsent(signal) { CounterWindow() }
        var total: Long? = null
        synchronized(window) {
            window.count++
            val interval = if (verbose) 3000L else 15000L
            if (window.count == 1L || now - window.lastLogAt >= interval) {
                window.lastLogAt = now
                total = window.count
            }
        }
        val observed = total ?: return
        event(feature, "hook_hit", "observed",
            "hook=" + id.take(70) + ",count=" + observed)
    }

    /**
     * Diagnostic settings are boolean feature switches, never personally
     * identifying configuration values.
     */
    fun setting(key: String, enabled: Boolean, origin: String, ok: Boolean = true) {
        event(FeatureTraceCatalog.forSetting(key), "setting",
            if (ok) "changed" else "fail",
            "key=" + key + ",enabled=" + enabled + ",origin=" + origin)
    }

    private fun safe(raw: String, limit: Int): String {
        // Treat *all* labels as untrusted, even if most call sites use literals.
        // Never dump raw URL fragments, e-mail addresses or exception messages.
        val withoutSecrets = raw
            .replace(Regex("(?i)https?://[^\\s,]+"), "redacted_url")
            .replace(Regex("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}"),
                "redacted_email")
            .replace(Regex("(?i)(token|cookie|password|authorization|secret)=[^,\\s]+"),
                "$1=redacted")
        return withoutSecrets.replace(Regex("[^A-Za-z0-9_.:,=+/-]"), "_")
            .take(limit)
    }
}
