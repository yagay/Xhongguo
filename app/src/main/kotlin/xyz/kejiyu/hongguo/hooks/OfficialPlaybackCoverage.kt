package xyz.kejiyu.hongguo.hooks

import android.net.Uri
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook

/**
 * Companion passive coverage probe for the official 7.4.2.32 APK.
 *
 * Observes transport construction, common-parameter processing, typed model
 * delivery, and TTVideoEngine consumption independently of obfuscated classes.
 * Never stores/logs full URLs, raw IDs, user data, cookies, tokens, signatures,
 * request/response bodies, model bytes, or video addresses.
 */
internal object OfficialPlaybackCoverage {
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private val hitCounts = ConcurrentHashMap<String, AtomicInteger>()
    private const val CAP_PER_STAGE = 100

    private val keyAllowlist = setOf(
        "aid", "app_name", "channel", "version_code", "version_name",
        "update_version_code", "manifest_version_code", "pv_player",
        "os", "os_api", "device_platform", "device_level",
        "video_id", "video_ids", "video_id_type", "need_all_video_definition",
        "dr_scene", "source", "from_video_id", "iid", "device_id", "cdid",
        "sessionid", "sessionid_ss", "is_from_web"
    )

    private fun get(o: Any?, name: String): Any? {
        if (o == null) return null
        return try {
            val m = o.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return null
            m.invoke(o)
        } catch (_: Throwable) { null }
    }

    private fun field(o: Any?, name: String): Any? {
        if (o == null) return null
        var c: Class<*>? = o.javaClass
        repeat(3) {
            val cls = c ?: return null
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (_: Throwable) { c = cls.superclass }
        }
        return null
    }

    private fun endpoint(url: String?): Pair<String, String>? {
        if (url.isNullOrEmpty()) return null
        val u = try { Uri.parse(url) } catch (_: Throwable) { return null }
        val path = u.path.orEmpty()
        val p = path.lowercase(Locale.US)
        val group = when {
            p.contains("/video_tab/") && p.contains("video_model") -> "video_tab"
            p.contains("video_model_lite") -> "video_lite"
            p.contains("multi_video_model") -> "video_v5"
            p.contains("/video_model/") -> "video_legacy"
            p.startsWith("/passport/") -> "passport"
            p.startsWith("/reading/user/") -> "account"
            p.contains("device_register") -> "device_register"
            else -> return null
        }
        // Endpoint only: parameter values, hosts and numeric IDs are excluded.
        val safe = path.replace(Regex("\\d{6,}"), ":id")
            .replace(Regex("[^a-zA-Z0-9_/:.-]"), "_").take(160)
        val names = try {
            u.queryParameterNames.filter { it in keyAllowlist }.sorted()
                .take(25).joinToString(",")
        } catch (_: Throwable) { "" }
        return group to ("route=" + safe + " keys=[" + names + "]")
    }

    private fun headers(req: Any): String {
        val raw = get(req, "getHeaders")
        val list = when (raw) {
            is Collection<*> -> raw.take(100)
            is Array<*> -> raw.take(100)
            else -> emptyList()
        }
        val names = HashSet<String>()
        var session = false
        for (entry in list) {
            val name = (get(entry, "getName") as? String ?: field(entry, "name") as? String)
                ?.lowercase(Locale.US) ?: continue
            names.add(name)
            if (name == "cookie") {
                // Inspect in memory solely to emit a boolean, not the value.
                val v = (get(entry, "getValue") as? String
                    ?: field(entry, "value") as? String).orEmpty()
                session = Regex("(?i)(^|;\\s*)sessionid(?:_ss)?=").containsMatchIn(v)
            }
        }
        val signNames = listOf("x-argus", "x-ladon", "x-gorgon",
            "x-medusa", "x-helios", "x-khronos", "x-ss-stub")
        return " headers=" + list.size + " headerNamesReadable=" + names.size +
            " cookiePresent=" + names.contains("cookie") +
            " loginSessionPresent=" + session +
            " authPresent=" + names.contains("authorization") +
            " signCount=" + signNames.count { it in names }
    }

    private fun emit(stage: String, event: String) {
        // A busy official app creates hundreds of account/Passport requests
        // before playback starts. Never let those exhaust the video diagnostic
        // budget. Keep independent, bounded counters for video and other work.
        val video = event.contains("group=video_")
        val bucket = when {
            stage == "builder" && video -> "builder:video"
            stage == "builder" -> "builder:other"
            else -> stage
        }
        val cap = when (bucket) {
            "builder:video" -> 100
            "builder:other" -> 12
            "cookieStore" -> 3
            else -> CAP_PER_STAGE
        }
        val n = hitCounts.computeIfAbsent(bucket) { AtomicInteger() }.incrementAndGet()
        if (n <= cap) {
            LogUtil.info("HGDEEP stage=" + stage + " bucket=" + bucket +
                " index=" + n + " " + event)
            LogUtil.incr("officialDeep_" + stage)
        } else if (n == cap + 1) {
            LogUtil.info("HGDEEP stage=" + stage + " bucket=" + bucket +
                " capReached=" + cap)
        }
    }

    private fun requestSummary(o: Any?): String? {
        val url = get(o, "getUrl") as? String
        val (kind, route) = endpoint(url) ?: return null
        val method = get(o, "getMethod") as? String
        return "group=" + kind + " " + route +
            " method=" + (method?.takeIf { it in setOf("GET", "POST", "PUT") } ?: "unknown") +
            (if (o != null) headers(o) else "")
    }

    private fun classifiedModel(o: Any?): String? {
        val cls = o?.javaClass ?: return null
        val type = cls.simpleName
        if (!type.contains("VideoModel", true) &&
            !type.contains("Playback", true)) return null
        val data = field(o, "data") ?: field(o, "videoData")
        val code = (field(o, "code") as? Number)?.toInt()
        val nested = field(data, "videoModelData")
            ?: field(data, "video_model_data")
            ?: field(data, "videoData")
        fun amount(v: Any?): String = when (v) {
            is Map<*, *> -> v.size.toString()
            is Collection<*> -> v.size.toString()
            is Array<*> -> v.size.toString()
            else -> "unknown"
        }
        return "type=" + type.take(64) +
            " dataPresent=" + (data != null) +
            " dataType=" + (data?.javaClass?.simpleName?.take(48) ?: "none") +
            " businessCode=" + (code?.takeIf { it in -1000..999999 }?.toString() ?: "unknown") +
            " dataItems=" + amount(data) +
            " nestedModelPresent=" + (nested != null) +
            " nestedModelItems=" + amount(nested)
    }

    private fun hook(module: MainHook, loader: ClassLoader,
                     className: String, methodName: String,
                     stage: String, after: (XposedInterface.Chain, Any?) -> Unit): Int {
        val c = try { Class.forName(className, false, loader) }
            catch (_: Throwable) { return 0 }
        var count = 0
        for ((index, m) in c.declaredMethods.filter { it.name == methodName }.withIndex()) {
            val key = className + "#" + methodName + "#" + index
            if (!installed.add(key)) continue
            try {
                module.hook(m).setId("hgDeep_" + stage + "_" + index)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        // The original always runs and receives unchanged args.
                        if (!OfficialPlaybackProbe.isEnabled()) return@Hooker chain.proceed()
                        val result = chain.proceed()
                        try { after(chain, result) } catch (_: Throwable) {}
                        result
                    })
                count++
            } catch (_: Throwable) {
                installed.remove(key)
            }
        }
        return count
    }

    private fun requestFields(o: Any?): String {
        if (o == null) return ""
        val parts = ArrayList<String>()
        val scene = field(o, "drScene") as? String
        if (scene != null) {
            parts.add("drScene=" + (scene.takeIf {
                it.matches(Regex("[a-zA-Z_]{1,24}"))
            } ?: "other"))
        }
        val ids = field(o, "mixedVideoIdMap") as? Map<*, *>
        if (ids != null) {
            parts.add("videoGroups=" + ids.size)
            parts.add("videoCount=" + ids.values.sumOf {
                (it as? Collection<*>)?.size ?: 0
            })
        }
        for (key in listOf("videoIds", "audioIds")) {
            val list = field(o, key)
            if (list is Collection<*>) parts.add(key + "Count=" + list.size)
        }
        val biz = field(o, "bizParam") ?: o
        for (key in listOf("deviceLevel", "needAllVideoDefinition",
                "useOsPlayer", "needMp4Align", "videoPlatform")) {
            val v = field(biz, key)
            if (v is Boolean || v is Number) parts.add(key + "=" + v)
        }
        return parts.joinToString(" ")
    }

    private fun hookDtoConstructors(module: MainHook, loader: ClassLoader,
                                    className: String): Int {
        val cls = try { Class.forName(className, false, loader) }
            catch (_: Throwable) { return 0 }
        var count = 0
        for ((i, constructor) in cls.declaredConstructors.withIndex()) {
            val key = className + "#constructor#" + i
            if (!installed.add(key)) continue
            try {
                module.hook(constructor).setId("hgDeep_dto_" +
                    cls.simpleName.take(26) + "_" + i)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        if (OfficialPlaybackProbe.isEnabled()) {
                            try {
                                val fields = requestFields(chain.thisObject)
                                emit("dto", "type=" + cls.simpleName.take(48) +
                                    " fieldsAtConstruction=[" + fields + "]" +
                                    " fieldsDeferredPossible=" + fields.isBlank() +
                                    " constructorArgCount=" + chain.args.size)
                            } catch (_: Throwable) {}
                        }
                        result
                    })
                count++
            } catch (_: Throwable) { installed.remove(key) }
        }
        return count
    }

    fun install(module: MainHook, loader: ClassLoader) {
        val installedNames = LinkedHashMap<String, Int>()

        installedNames["dto"] = listOf(
            "seriessdk.com.dragon.read.saas.rpc.model.MGetVideoModelV2Request",
            "seriessdk.com.dragon.read.saas.rpc.model.GetVideoModelRequest",
            "com.dragon.read.rpc.model.GetVideoModelRequest",
            "com.dragon.read.rpc.model.MGetVideoModelLiteRequest"
        ).sumOf { hookDtoConstructors(module, loader, it) }

        installedNames["builder"] = hook(module, loader,
            "com.bytedance.retrofit2.client.Request" + "$" + "Builder",
            "build", "builder") { _, result ->
            val summary = requestSummary(result)
            if (summary != null) emit("builder", summary)
        }

        installedNames["commonParams"] = hook(module, loader,
            "com.bytedance.frameworks.baselib.network.http.NetworkParams",
            "addCommonParams", "commonParams") { chain, result ->
            val candidate = (result as? String)
                ?: (0 until minOf(3, chain.args.size)).asSequence()
                    .mapNotNull { chain.getArg(it) as? String }
                    .firstOrNull { it.startsWith("http") }
            val (kind, route) = endpoint(candidate) ?: return@hook
            emit("commonParams", "group=" + kind + " " + route)
        }

        installedNames["parsedBody"] = hook(module, loader,
            "com.bytedance.retrofit2.SsResponse",
            "body", "parsedBody") { _, result ->
            val summary = classifiedModel(result)
            if (summary != null) emit("parsedBody", summary)
        }

        installedNames["engineModel"] = hook(module, loader,
            "com.ss.ttvideoengine.TTVideoEngine",
            "setVideoModel", "engineModel") { chain, _ ->
            val model = chain.getArg(0)
            emit("engineModel", "modelPresent=" + (model != null) +
                " modelType=" + (model?.javaClass?.simpleName?.take(60) ?: "none"))
        }

        installedNames["engineId"] = hook(module, loader,
            "com.ss.ttvideoengine.TTVideoEngine",
            "setVideoID", "engineId") { _, _ ->
            emit("engineId", "videoIdentityAssigned=true")
        }

        installedNames["engineDirect"] = hook(module, loader,
            "com.ss.ttvideoengine.TTVideoEngine",
            "setDirectURL", "engineDirect") { _, _ ->
            // The URL itself is intentionally never accessed.
            emit("engineDirect", "directSourceAssigned=true")
        }

        installedNames["cachePut"] = hook(module, loader,
            "com.ss.ttvideoengine.VideoModelCache",
            "put", "cachePut") { _, _ ->
            emit("cachePut", "playbackModelCacheWrite=true")
        }

        installedNames["cacheGet"] = hook(module, loader,
            "com.ss.ttvideoengine.VideoModelCache",
            "get", "cacheGet") { _, result ->
            emit("cacheGet", "cacheHit=" + (result != null))
        }

        installedNames["cookieStore"] = hook(module, loader,
            "com.bytedance.frameworks.baselib.network.http.NetworkParams",
            "getAppCookieStore", "cookieStore") { _, result ->
            emit("cookieStore", "cookieStoreAvailable=" + (result != null))
        }

        LogUtil.info("HGDEEP COVERAGE " +
            installedNames.entries.joinToString(" ") { it.key + "=" + it.value } +
            " limits=100/stage values=never-recorded nativeCronet=not-covered")
    }
}
