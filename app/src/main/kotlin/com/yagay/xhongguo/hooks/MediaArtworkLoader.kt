package com.yagay.xhongguo.hooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import com.yagay.xhongguo.LogUtil

/**
 * Loads the EXISTING official Saas poster, never a video frame or new stream.
 * Work takes place on one IO worker; the host video/main thread is untouched.
 * URLs are not written to Xposed diagnostic files or user-visible metadata.
 */
internal object MediaArtworkLoader {
    private const val MAX_IMAGE_BYTES = 1_600_000
    private const val MAX_SQUARE_PX = 320
    private const val CACHE_MAX_FILES = 32
    private const val CACHE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Xhongguo-MediaArtwork").apply { isDaemon = true }
    }
    private val memory = object : LruCache<String, Bitmap>(8) {}
    class Subscription {
        @Volatile var cancelled = false
            private set
        fun cancel() { cancelled = true }
    }
    private data class Subscriber(val token: Subscription, val callback: (Bitmap?, String) -> Unit)
    private val inflight = HashMap<String, MutableList<Subscriber>>()
    private val urlPattern = Regex("""https?://[^\s"'\\<>}\]]+""",
        RegexOption.IGNORE_CASE)

    /**
     * Official cover fields can hold a direct URL or a JSON-escaped list.
     * Accept only https external hostnames, no IP addresses/private hosts.
     * The real app is allowed to open its own official poster URLs.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val decoded = raw.trim().replace("\\/", "/")
            .replace("\\u0026", "&")
        val candidate = urlPattern.find(decoded)?.value
            ?.trimEnd(',', ';', ')', ']') ?: return null
        return try {
            val u = URL(candidate)
            val host = u.host.lowercase()
            if (u.protocol.lowercase() != "https" ||
                host.isBlank() || '.' !in host ||
                host == "localhost" || host.endsWith(".local") ||
                host.endsWith(".internal") || host.endsWith(".localhost") ||
                host.matches(Regex("""[\d.]+""")) ||
                host.contains(':') || u.port !in listOf(-1, 443) ||
                candidate.length > 2048 || u.userInfo != null) null
            else u.toExternalForm()
        } catch (_: Throwable) { null }
    }

    private fun cacheKey(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val measure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, measure)
        if (measure.outWidth !in 32..6000 ||
            measure.outHeight !in 32..6000) return null
        var sample = 1
        while (measure.outWidth / sample > MAX_SQUARE_PX * 2 ||
            measure.outHeight / sample > MAX_SQUARE_PX * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        val ratio = minOf(MAX_SQUARE_PX.toFloat() / bitmap.width,
            MAX_SQUARE_PX.toFloat() / bitmap.height, 1f)
        if (ratio >= 1f) return bitmap
        val resized = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    private fun cacheDir(ctx: Context): File? = try {
        File(ctx.cacheDir, "xhongguo-artwork").apply {
            if (!exists()) mkdirs()
        }.takeIf { it.isDirectory }
    } catch (_: Throwable) { null }

    private fun localImage(ctx: Context, key: String): Bitmap? {
        val file = File(cacheDir(ctx) ?: return null, "$key.img")
        return try {
            if (!file.isFile ||
                file.length() !in 100L..MAX_IMAGE_BYTES.toLong() ||
                System.currentTimeMillis() - file.lastModified() >
                CACHE_MAX_AGE_MS) {
                if (file.isFile) file.delete()
                return null
            }
            decode(file.readBytes()).also {
                if (it == null) file.delete()
            }
        } catch (_: Throwable) { null }
    }

    private fun rememberDisk(ctx: Context, key: String, bytes: ByteArray) {
        val dir = cacheDir(ctx) ?: return
        try {
            File(dir, "$key.img").writeBytes(bytes)
            val existing = dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".img")
            }?.sortedByDescending { it.lastModified() } ?: return
            existing.drop(CACHE_MAX_FILES).forEach { it.delete() }
        } catch (_: Throwable) { }
    }

    private fun download(url: String): ByteArray? {
        var next = url
        for (attempt in 0..2) {
            val conn = (URL(next).openConnection() as? HttpURLConnection)
                ?: return null
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,image/*")
                conn.setRequestProperty("User-Agent", "Xhongguo-MediaArtwork/1.0")
                val status = conn.responseCode
                if (status in 300..399) {
                    val redirect = conn.getHeaderField("Location")
                        ?: return null
                    val destination = try {
                        URL(URL(next), redirect).toExternalForm()
                    } catch (_: Throwable) { return null }
                    next = normalize(destination) ?: return null
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK ||
                    conn.contentLengthLong > MAX_IMAGE_BYTES) return null
                val type = conn.contentType?.substringBefore(';')?.lowercase()
                if (type != null && !type.startsWith("image/") &&
                    type != "application/octet-stream") return null
                return conn.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (output.size() + n > MAX_IMAGE_BYTES) return@use null
                        output.write(buf, 0, n)
                    }
                    output.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Asynchronous and bounded. callback always runs on the host main thread.
     * Every call is associated with the exact URL; SystemMediaBridge must
     * also check the playback generation before applying the returned bitmap.
     */
    fun load(ctx: Context, raw: String, callback: (Bitmap?, String) -> Unit): Subscription {
        val token = Subscription()
        fun deliver(bitmap: Bitmap?, result: String) {
            main.post { if (!token.cancelled) callback(bitmap, result) }
        }
        val url = normalize(raw) ?: run { deliver(null, "invalid-url"); return token }
        val key = cacheKey(url)
        val subscriber = Subscriber(token, callback)
        synchronized(memory) {
            memory.get(key)?.takeUnless { it.isRecycled }?.let {
                deliver(it, "memory-cache")
                return token
            }
            val pending = inflight[key]
            if (pending != null) {
                pending.removeAll { it.token.cancelled }
                if (pending.size < 16) pending.add(subscriber)
                else deliver(null, "queue-limited")
                return token
            }
            if (inflight.size >= 16) {
                deliver(null, "queue-limited")
                return token
            }
            inflight[key] = mutableListOf(subscriber)
        }
        io.execute {
            synchronized(memory) {
                if (inflight[key].orEmpty().none { !it.token.cancelled }) {
                    inflight.remove(key)
                    return@execute
                }
            }
            var outcome = "disk-cache"
            val art = runCatching {
                localImage(ctx, key) ?: run {
                    outcome = "network"
                    val bytes = download(url)
                    if (bytes == null) null else decode(bytes)?.also {
                        rememberDisk(ctx, key, bytes)
                    }
                }
            }.getOrNull()
            if (art == null) outcome = "unavailable"
            val subscribers = synchronized(memory) {
                if (art != null) memory.put(key, art)
                inflight.remove(key).orEmpty().toList()
            }
            main.post {
                subscribers.filterNot { it.token.cancelled }.forEach {
                    runCatching { it.callback(art, outcome) }
                }
            }
        }
        return token
    }
}
