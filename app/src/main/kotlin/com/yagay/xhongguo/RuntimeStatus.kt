package com.yagay.xhongguo

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock

/** Last observed events, not a claim that the remote process is currently alive. */
internal object RuntimeStatus {
    val keys = setOf("hook_init", "settings_sync", "audio_snapshot", "playback", "media_metadata", "audio_resume",
        "native_contract", "native_transport")
    private val last = HashMap<String, Long>()
    private val executor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("runtime_evidence_v1", Context.MODE_PRIVATE)
    fun report(ctx: Context?, key: String) {
        if (ctx == null || key !in keys ||
            ctx.packageName !in setOf("com.phoenix.read", "com.phoenix.read.oversea.gp") ||
            Application.getProcessName() != ctx.packageName) return
        val now = SystemClock.elapsedRealtime()
        synchronized(last) {
            if (last[key]?.let { now - it in 0L..15_000L } == true) return
            last[key] = now
        }
        val app = ctx.applicationContext ?: ctx
        executor.execute {
            val ok = runCatching {
                app.contentResolver.call(ModuleSettingsStore.URI, "runtime_status", null,
                    Bundle().apply { putString("key", key); putString("package", app.packageName) })?.getBoolean("ok") == true
            }.getOrDefault(false)
            if (!ok) synchronized(last) { last.remove(key) }
        }
    }
    fun accept(ctx: Context, data: Bundle?): Bundle {
        val key = data?.getString("key").orEmpty()
        val pkg = data?.getString("package").orEmpty()
        val callers = ctx.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
        val ok = key in keys && pkg in setOf("com.phoenix.read", "com.phoenix.read.oversea.gp") &&
            pkg in callers
        if (ok) prefs(ctx).edit().putLong("$pkg:$key", SystemClock.elapsedRealtime())
            .putLong("$pkg:$key:wall", System.currentTimeMillis()).apply()
        return Bundle().apply { putBoolean("ok", ok) }
    }
    fun describe(ctx: Context, pkg: String, key: String): String {
        val p = prefs(ctx)
        val at = p.getLong("$pkg:$key", -1L)
        val wall = p.getLong("$pkg:$key:wall", 0L)
        val age = SystemClock.elapsedRealtime() - at
        if (at < 0 || age < 0 || wall == 0L ||
            kotlin.math.abs(System.currentTimeMillis() - wall - age) > 120_000L) return "尚未收到证据"
        if (key == "audio_snapshot" && age > 30000L) return "上次收到，当前状态未知"
        return "已观察 · " + when { age < 60_000L -> "刚刚"; age < 3600000L -> "${age / 60000} 分钟前"; else -> "${age / 3600000} 小时前" }
    }
}
