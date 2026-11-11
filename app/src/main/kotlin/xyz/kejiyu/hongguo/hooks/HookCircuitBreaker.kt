package xyz.kejiyu.hongguo.hooks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.FeatureTrace

/**
 * Isolate failing optional hooks from the host application.
 *
 * After repeated callback failures, subsequent invocations bypass only the
 * faulty hook and execute the original method. A failure in a callback which
 * has already called chain.proceed() must NOT call the original again: the
 * original exception is rethrown to libxposed's PROTECTIVE exception mode.
 *
 * This is per-process and per-hook ID; toggling the feature or restarting
 * the process resets it. No hidden retry loop or remote telemetry.
 */
internal object HookCircuitBreaker {
    private const val MAX_FAILURES = 3

    private class State {
        val failures = AtomicInteger()
        val disabled = AtomicBoolean()
    }

    private val states = ConcurrentHashMap<String, State>()

    fun disabled(id: String): Boolean = states[id]?.disabled?.get() == true

    fun failure(id: String, throwable: Throwable) {
        val state = states.computeIfAbsent(id) { State() }
        val count = state.failures.incrementAndGet()
        if (count <= MAX_FAILURES) {
            LogUtil.warn("可选 Hook 回调失败 id=$id count=$count" +
                " type=" + throwable.javaClass.simpleName)
            LogUtil.incr("hookCallbackFailure")
        }
        if (count >= MAX_FAILURES &&
            state.disabled.compareAndSet(false, true)) {
            LogUtil.warn("可选 Hook 已自动隔离 id=$id；后续调用原方法")
            LogUtil.incr("hookCircuitBreakerOpened")
            FeatureTrace.event("hook", "circuit_breaker", "degraded",
                "hook=" + id.take(60) + ",failures=" + count)
        }
    }
}
