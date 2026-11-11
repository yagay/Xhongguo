package xyz.kejiyu.hongguo

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import xyz.kejiyu.hongguo.hooks.TargetNames

/** Root-assisted export of Xposed logs, Hongguo process logcat, and diagnostics. */
object ModuleDiagnostics {
    const val REQUEST = 22017
    private val targets = listOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)

    fun chooseDestination(activity: Activity) {
        val name = "Xhongguo-diagnostic-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQUEST)
    }

    fun onActivityResult(activity: Activity, request: Int, result: Int, data: Intent?): Boolean {
        if (request != REQUEST) return false
        if (result != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        val app = activity.applicationContext
        Toast.makeText(activity, "正在收集诊断日志…", Toast.LENGTH_SHORT).show()
        Thread({
            val message = try {
                export(app, uri)
                "诊断 ZIP 导出成功"
            } catch (e: Throwable) {
                android.util.Log.e("XhDiagnostics", "Export failed", e)
                "导出失败: " + e.javaClass.simpleName + ": " + e.message
            }
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }, "Xhongguo-log-export").start()
        return true
    }

    private fun redact(raw: String): String = raw
        .replace(Regex("(?i)(authorization|cookie|password|access[_-]?token|refresh[_-]?token|secret)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        .replace(Regex("https?://[^\\s\\\"'<>]+"), "[URL]")

    private fun fileTail(file: File, cap: Int = 260000): String {
        if (!file.isFile) return ""
        return file.inputStream().buffered().use { stream ->
            var left = (file.length() - cap).coerceAtLeast(0L)
            while (left > 0) {
                val n = stream.skip(left)
                if (n <= 0) break
                left -= n
            }
            val data = ByteArray(cap)
            var size = 0
            while (size < cap) {
                val n = stream.read(data, size, cap - size)
                if (n <= 0) break
                size += n
            }
            String(data, 0, size, Charsets.UTF_8)
        }
    }

    private fun asRoot(ctx: Context, shell: String, timeout: Long = 7): String? {
        val output = File.createTempFile("xh-log-", ".txt", ctx.cacheDir)
        return try {
            val p = ProcessBuilder("su", "-c", shell)
                .redirectErrorStream(true).redirectOutput(output).start()
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                null
            } else if (p.exitValue() == 0) fileTail(output, 300000) else null
        } catch (_: Throwable) {
            null
        } finally { output.delete() }
    }

    private fun collectRoot(ctx: Context): Map<String, String> {
        val files = linkedMapOf<String, String>()
        if (asRoot(ctx, "id -u", 10)?.trim() != "0") {
            files["root/status.txt"] = "ROOT NOT GRANTED: authorize Xhongguo in KernelSU Next."
            return files
        }
        files["root/status.txt"] = "ROOT GRANTED"
        for (pkg in targets) {
            val dir = "/storage/emulated/0/Android/media/" + pkg + "/K_红果logs"
            // Avoid blending months-old international-build logs into a current
            // diagnosis; retain today and yesterday across midnight.
            val recentDates = setOf(
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()),
                SimpleDateFormat("yyyyMMdd", Locale.US).format(
                    Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L))
            )
            val names = asRoot(ctx, "ls -1t '" + dir + "'").orEmpty().lines()
                .map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z0-9._-]+\\.log")) }
                .filter { it.take(8) in recentDates }
                .take(5)
            if (names.isEmpty()) files["status/" + pkg + ".txt"] =
                "No module log. Check LSPosed scope, Root and log initialization."
            for (name in names) {
                val content = asRoot(ctx, "tail -c 260000 '" + dir + "/" + name + "'")
                if (!content.isNullOrBlank()) {
                    files["hook/" + pkg + "/" + name] = redact(content)
                }
            }
            val pids = asRoot(ctx, "pidof '" + pkg + "'").orEmpty().trim()
                .split(Regex("\\s+"))
                .filter { it.matches(Regex("\\d{1,8}")) }.take(3)
            if (pids.isEmpty()) files["status/" + pkg + "-process.txt"] =
                "Main process not running. Reproduce issue before exporting."
            for (pid in pids) {
                val raw = asRoot(ctx,
                    "logcat -b all -d -v threadtime -t 2500 --pid=" + pid, 8)
                    ?: asRoot(ctx, "logcat -d -v threadtime -t 2500 --pid=" + pid, 8)
                if (raw == null) {
                    files["status/" + pkg + "-logcat-" + pid + ".txt"] =
                        "Root is granted, but logcat --pid returned no readable output. " +
                        "Check Android log buffer availability and process lifetime."
                    continue
                }
                val terms = listOf("ZongHe", "Xhongguo", "MediaSession",
                    "MediaCodec", "AudioTrack", "VideoController", "TTVideo",
                    "ExoPlayer", "videoshop", "AndroidRuntime", "FATAL EXCEPTION",
                    "NotificationService", "Surface", "ActivityManager")
                val filtered = raw.lineSequence().filter { line ->
                    terms.any { line.contains(it, true) }
                }.take(1600).joinToString("\n")
                if (filtered.isNotBlank()) {
                    files["logcat/" + pkg + "-" + pid + ".txt"] = redact(filtered)
                } else {
                    files["status/" + pkg + "-logcat-" + pid + ".txt"] =
                        "Logcat read succeeded but matched zero media/Hook log entries." +
                        " rawBytes=" + raw.length
                }
            }
        }
        return files
    }

    private fun export(ctx: Context, uri: Uri) {
        val files = linkedMapOf<String, String>()
        files["device.txt"] = buildString {
            appendLine("Xhongguo " + BuildConfig.VERSION_NAME + " / " + BuildConfig.VERSION_CODE)
            appendLine("Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT)
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.DEVICE)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            appendLine("Module notifications: " + nm?.areNotificationsEnabled())
            for (pkg in targets) {
                try {
                    @Suppress("DEPRECATION")
                    val info = ctx.packageManager.getPackageInfo(pkg, 0)
                    appendLine(pkg + " version=" + info.versionName +
                        " officiallySupported=" + TargetNames.isSupported(pkg, info.versionName))
                } catch (_: Throwable) { appendLine(pkg + " not installed") }
            }
        }
        val prefs = ModuleSettingsStore.prefs(ctx)
        files["settings.txt"] = buildString {
            for (key in ModuleSettingsStore.BOOLEANS.sorted()) {
                appendLine(key + "=" + prefs.getBoolean(key,
                    key == "video_longpress_settings" || key == "disable_shopping_mall"))
            }
        }
        files.putAll(collectRoot(ctx))
        val evidence = files.filterKeys { it.startsWith("hook/") || it.startsWith("logcat/") }
            .values.joinToString("\n").takeLast(3_000_000)
        val trace = FeatureTraceReport.analyze(evidence)
        files["feature/TRACE_SUMMARY.txt"] = trace.summary
        files["feature/TRACE_EVENTS.log"] = trace.events
        files["feature/TRACE_ERRORS.log"] = trace.errors
        files["feature/README.txt"] = (
            "Structured events: XH_TRACE feature=... stage=... result=... flow=...\n" +
            "observed = evidence only, not proof of successful behavior.\n" +
            "fail/degraded = suspected issue; cross-reference hook/logcat.\n" +
            "flow joins metadata changes during one native playback transition.\n" +
            "No events can mean untriggered feature, missing Root or absent scope.\n")
        files["REPORT.txt"] = FeatureDiagnostics.analyze(ctx, evidence,
            files["root/status.txt"] == "ROOT GRANTED") +
            "\n\n" + trace.summary
        val output = ctx.contentResolver.openOutputStream(uri, "w")
            ?: error("Cannot open destination ZIP")
        output.use { target ->
            ZipOutputStream(target).use { zip ->
                for ((name, text) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }
}
