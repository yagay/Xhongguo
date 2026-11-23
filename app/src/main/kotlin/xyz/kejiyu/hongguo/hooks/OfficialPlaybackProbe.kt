package xyz.kejiyu.hongguo.hooks

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook

/**
 * Passive official-Hongguo 7.4.2.32 playback/auth diagnostics.
 *
 * No request/response mutations, no body reads, no secrets, no full URLs,
 * no session/device/video identifiers and no signed-media addresses in logs.
 * All hooks are guarded by an explicitly enabled module setting.
 */
internal object OfficialPlaybackProbe {
    @Volatile private var enabled = false
    @Volatile private var prefs: SharedPreferences? = null
    private val events = AtomicInteger(0)
    private const val MAX_EVENTS = 450
    private const val PREFIX = "HGPROBE"
    private val installed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val relevantQueryKeys = setOf(
        "aid", "app_name", "channel", "version_code", "version_name",
        "manifest_version_code", "update_version_code", "os", "os_api",
        "device_platform", "device_id", "iid", "cdid", "video_id",
        "video_ids", "video_id_type", "dr_scene", "pv_player",
        "need_all_video_definition", "device_level", "video_platform",
        "source", "from_video_id", "is_from_web"
    )

    fun attach(context: Context) {
        prefs = context.getSharedPreferences("lspilot_kejiyu", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("official_playback_probe", false) == true
        LogUtil.info(PREFIX + " mode=" + if (enabled) "ON" else "OFF")
    }

    private fun enabledNow(): Boolean =
        prefs?.getBoolean("official_playback_probe", false) ?: enabled

    private fun route(url: String): Pair<String, String>? {
        val parsed = try { Uri.parse(url) } catch (_: Throwable) { return null }
        val path = try { parsed.path.orEmpty() } catch (_: Throwable) { "" }
        val lower = path.lowercase(Locale.US)
        val relevant = lower.contains("/multi_video_model/") ||
            lower.contains("/video_model/") ||
            lower.contains("/video_model_lite/") ||
            lower.startsWith("/passport/") ||
            lower.startsWith("/reading/user/") ||
            lower.startsWith("/reading/distribution/video_model") ||
            lower.contains("/device_register")
        if (!relevant || path.length > 180) return null

        val group = when {
            lower.contains("video_model_lite") -> "lite"
            lower.contains("video_tab") -> "video_tab"
            lower.contains("multi_video_model") -> "v5"
            lower.contains("/video_model/") -> "legacy"
            lower.startsWith("/passport/") -> "passport"
            lower.startsWith("/reading/user/") -> "account"
            lower.contains("device_register") -> "device"
            else -> "other"
        }
        // Never log host, query values, numeric IDs or arbitrary path values.
        val safePath = path.replace(Regex("[0-9]{6,}"), ":id")
            .replace(Regex("[^A-Za-z0-9_:/.-]"), "_")
            .take(150)
        return group to safePath
    }

    private fun get(obj: Any?, method: String): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.methods.firstOrNull {
                it.name == method && it.parameterCount == 0
            } ?: return null
            m.invoke(obj)
        } catch (_: Throwable) { null }
    }

    private fun field(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) { null }
    }

    private fun request(obj: Any?): Any? =
        get(obj, "request") ?: field(obj, "mOriginalRequest") ?:
            field(obj, "originalRequest") ?: field(obj, "delayRequest")

    private fun headerMap(req: Any?): Map<String, String> {
        val raw = get(req, "getHeaders")
        val out = LinkedHashMap<String, String>()
        val headers = when (raw) {
            is Iterable<*> -> raw.toList()
            is Array<*> -> raw.toList()
            else -> emptyList()
        }
        for (item in headers.take(96)) {
            // Headers are observed in memory only. Only booleans are logged.
            val name = (get(item, "getName") as? String)
                ?: (field(item, "name") as? String) ?: continue
            val value = (get(item, "getValue") as? String)
                ?: (field(item, "value") as? String).orEmpty()
            if (name.length in 1..80) out[name.lowercase(Locale.US)] = value
        }
        return out
    }

    private fun requestSnapshot(req: Any?): String? {
        val fullUrl = get(req, "getUrl") as? String ?: return null
        val (group, safePath) = route(fullUrl) ?: return null
        val method = (get(req, "getMethod") as? String)
            ?.takeIf { it in setOf("GET", "POST", "PUT", "DELETE") } ?: "UNKNOWN"
        val queryKeys = try {
            Uri.parse(fullUrl).queryParameterNames
                .filter { it in relevantQueryKeys }.sorted().take(28).joinToString(",")
        } catch (_: Throwable) { "" }
        val headers = headerMap(req)
        val cookie = headers["cookie"].orEmpty()
        val session = Regex("(?i)(?:^|;\\s*)sessionid(?:_ss)?=").containsMatchIn(cookie)
        val auth = headers.containsKey("authorization")
        val sign = listOf("x-argus", "x-ladon", "x-gorgon", "x-medusa",
            "x-helios", "x-khronos", "x-ss-stub").filter { headers.containsKey(it) }
        val length = when (val body = get(req, "getBody")) {
            null -> "none"
            else -> safeLength(body)?.toString() ?: "unknown"
        }
        return "group=" + group + " method=" + method + " path=" + safePath +
            " queryKeys=[" + queryKeys + "]" +
            " cookieHeader=" + headers.containsKey("cookie") +
            " sessionCookie=" + session +
            " authorization=" + auth +
            " signedHeaders=[" + sign.joinToString(",") + "]" +
            " bodyLength=" + length
    }

    private fun safeLength(body: Any?): Long? {
        if (body == null) return null
        val len = get(body, "length") ?: get(body, "contentLength")
        return (len as? Number)?.toLong()?.takeIf { it >= 0L }
    }

    private fun modelArgsSnapshot(call: Any?): String {
        val args = field(call, "args") as? Array<*> ?: return ""
        val parts = ArrayList<String>()
        for (arg in args.take(8)) {
            val kind = arg?.javaClass?.name.orEmpty()
            if (!kind.endsWith(".MGetVideoModelV2Request") &&
                !kind.endsWith(".GetVideoModelRequest") &&
                !kind.endsWith(".MGetVideoModelLiteRequest")) continue
            parts.add("type=" + kind.substringAfterLast('.'))
            val scene = field(arg, "drScene") as? String
            // Scene labels are non-secret controlled keywords, not IDs.
            parts.add("drScene=" +
                (scene?.takeIf { it.matches(Regex("[A-Za-z_]{1,24}")) } ?: "unknown"))
            val group = field(arg, "mixedVideoIdMap") as? Map<*, *>
            if (group != null) {
                parts.add("idGroups=" + group.size)
                val ids = group.values.sumOf { (it as? Collection<*>)?.size ?: 0 }
                parts.add("episodeCount=" + ids)
            }
            for (key in listOf("videoIds", "audioIds")) {
                val list = field(arg, key) as? Collection<*>
                if (list != null) parts.add(key + "Count=" + list.size)
            }
            val biz = field(arg, "bizParam") ?: arg
            for (key in listOf("deviceLevel", "needAllVideoDefinition",
                "needMp4Align", "useOsPlayer", "source")) {
                val v = field(biz, key)
                if (v is Boolean || v is Number) parts.add(key + "=" + v)
            }
            parts.add("novelCommonParam=" +
                (field(arg, "novelCommonParam") != null))
        }
        return if (parts.isEmpty()) "" else " modelFields=[" + parts.joinToString(",") + "]"
    }

    private fun modelResultSnapshot(response: Any?): String {
        if (response == null) return ""
        // SsResponse.body() is a parsed DTO getter, not a network stream read.
        val body = get(response, "body") ?: return ""
        val type = body.javaClass.name
        if (!type.endsWith(".MGetVideoModelResponse") &&
            !type.endsWith(".MGetVideoModelLiteResponse") &&
            !type.endsWith(".GetVideoModelResponse")) return ""
        val data = field(body, "data")
        val code = field(body, "code")
        val dataCount = when (data) {
            is Map<*, *> -> data.size
            is Collection<*> -> data.size
            else -> null
        }
        return " modelResultType=" + type.substringAfterLast('.') +
            " modelDataPresent=" + (data != null) +
            " modelDataCount=" + (dataCount?.toString() ?: "unknown") +
            " modelCodePresent=" + (code != null)
    }

    private fun responseSnapshot(response: Any?): String {
        if (response == null) return "http=unknown length=unknown"
        val raw = get(response, "raw") ?: response
        val code = (get(response, "code") as? Number)?.toInt()
            ?: (get(raw, "getStatus") as? Number)?.toInt()
        val body = get(raw, "getBody")
        return "http=" + (code?.toString() ?: "unknown") +
            " length=" + (safeLength(body)?.toString() ?: "unknown") +
            " responseType=" + response.javaClass.simpleName.take(50)
    }

    private fun observe(label: String, chain: XposedInterface.Chain): Any? {
        if (!enabledNow()) return chain.proceed()
        val started = SystemClock.elapsedRealtime()
        // Inspect after the original call; never force early Retrofit request
        // creation, signing, cookie access or stream consumption.
        var response: Any? = null
        var error: Throwable? = null
        try {
            response = chain.proceed()
            return response
        } catch (t: Throwable) {
            error = t
            throw t
        } finally {
            try {
                if (events.get() < MAX_EVENTS) {
                    val req = request(chain.thisObject)
                    val reqInfo = requestSnapshot(req)
                    if (reqInfo != null && events.incrementAndGet() <= MAX_EVENTS) {
                        val result = if (error == null) responseSnapshot(response)
                            else "exception=" + error.javaClass.simpleName.take(60)
                        LogUtil.info(PREFIX + " EVENT layer=" + label +
                            " elapsedMs=" + (SystemClock.elapsedRealtime() - started) +
                            " " + reqInfo + modelArgsSnapshot(chain.thisObject) +
                            " " + result + modelResultSnapshot(response))
                        LogUtil.incr("officialProbeEvent")
                    }
                }
            } catch (_: Throwable) {
                // Never interfere with the target app's real success/error.
            }
        }
    }

    private fun installMethod(module: MainHook, loader: ClassLoader,
                              className: String, methodName: String,
                              label: String): Int {
        val cls = try { Class.forName(className, false, loader) } catch (_: Throwable) { return 0 }
        var count = 0
        for ((index, method) in cls.declaredMethods.filter { it.name == methodName }.withIndex()) {
            val signature = className + "#" + methodName + ":" + index
            if (!installed.add(signature)) continue
            try {
                module.hook(method)
                    .setId("hgProbe_" + label + "_" + index)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain -> observe(label, chain) })
                count++
            } catch (_: Throwable) {
                installed.remove(signature)
            }
        }
        return count
    }

    fun install(module: MainHook, loader: ClassLoader, pkg: String, version: String) {
        val client = installMethod(module, loader,
            "com.bytedance.retrofit2.SsHttpCall", "getResponseWithInterceptorChain",
            "retrofit")
        val network = installMethod(module, loader,
            "com.bytedance.retrofit2.CallServerInterceptor", "executeCall",
            "network")
        val parse = installMethod(module, loader,
            "com.bytedance.retrofit2.CallServerInterceptor", "parseResponse",
            "decode")
        val modelClasses = listOf(
            "seriessdk.com.dragon.read.saas.rpc.model.MGetVideoModelV2Request",
            "seriessdk.com.dragon.read.saas.rpc.model.MGetVideoModelResponse",
            "com.dragon.read.rpc.model.MGetVideoModelLiteRequest",
            "com.dragon.read.rpc.model.MGetVideoModelLiteResponse"
        )
        val detected = modelClasses.mapNotNull { name ->
            try {
                val cls = Class.forName(name, false, loader)
                // Names and primitive types only; never inspect model values.
                name.substringAfterLast('.') + "{" +
                    cls.declaredFields.asSequence()
                        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .map { it.name.take(48) }
                        .take(16).joinToString(",") + "}"
            } catch (_: Throwable) { null }
        }
        LogUtil.info(PREFIX + " INSTALLED pkg=" + pkg + " version=" + version +
            " retrofit=" + client + " network=" + network +
            " decode=" + parse + " models=" + detected.joinToString(";"))
        LogUtil.info(PREFIX + " POLICY defaultOff=true passive=true eventCap=" +
            MAX_EVENTS + " secrets=false rawBodies=false fullUrls=false")
    }
}
