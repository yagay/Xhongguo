package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.Looper
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import xyz.kejiyu.hongguo.LogUtil

/** Read-only bounded native metadata discovery. */
internal object NativeMetadataAccess {
    const val VIDEO = "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"
    private val tracked = ArrayList<WeakReference<Any>>()
    private var lastReport = 0L

    fun extendsType(type: Class<*>?, name: String): Boolean {
        var c = type
        while (c != null) {
            if (c.name == name) return true
            c = c.superclass
        }
        return false
    }

    fun call(target: Any?, name: String): Any? = try {
        target?.javaClass?.methods?.firstOrNull {
            it.name == name && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers)
        }?.invoke(target)
    } catch (_: Throwable) { null }

    fun video(input: Any?): Any? {
        if (input == null) return null
        if (extendsType(input.javaClass, VIDEO)) return input
        return try {
            input.javaClass.methods.firstOrNull {
                it.name == "getVideoData" && it.parameterCount == 0 &&
                    extendsType(it.returnType, VIDEO)
            }?.invoke(input)?.takeIf { extendsType(it.javaClass, VIDEO) }
        } catch (_: Throwable) { null }
    }

    fun typedFields(owner: Any?, type: String): List<Any> {
        if (owner == null) return emptyList()
        val result = ArrayList<Any>()
        var c: Class<*>? = owner.javaClass
        repeat(5) {
            val cls = c ?: return@repeat
            for (field in runCatching { cls.declaredFields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(field.modifiers) || !extendsType(field.type, type)) continue
                runCatching { field.isAccessible = true; field.get(owner) }.getOrNull()?.let { value ->
                    if (result.none { it === value }) result.add(value)
                }
            }
            c = cls.superclass
        }
        return result
    }

    fun isEpisodeFragment(fragment: Any): Boolean {
        if (!extendsType(fragment.javaClass, "androidx.fragment.app.Fragment")) return false
        val methods = runCatching { fragment.javaClass.methods }.getOrDefault(emptyArray())
        return methods.any { it.name == "getCurrentPosition" &&
            it.parameterCount == 0 && it.returnType == Integer.TYPE } &&
            methods.any { it.name == "getDataList" && it.parameterCount == 0 &&
                List::class.java.isAssignableFrom(it.returnType) }
    }

    @Synchronized fun observeFragment(fragment: Any?) {
        if (fragment == null || !isEpisodeFragment(fragment)) return
        tracked.removeAll { it.get() == null || it.get() === fragment }
        tracked.add(WeakReference(fragment))
        while (tracked.size > 32) tracked.removeAt(0)
    }

    @Synchronized fun forgetFragment(fragment: Any?) {
        tracked.removeAll { it.get() == null || it.get() === fragment }
    }

    @Synchronized private fun registered(): List<Any> = tracked.mapNotNull { it.get() }

    fun episodeFragments(activity: Activity): List<Any> {
        if (Looper.myLooper() != Looper.getMainLooper()) return emptyList()
        val result = ArrayList<Any>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val managers = java.util.ArrayDeque<Pair<Any, Int>>()
        call(activity, "getSupportFragmentManager")?.let { managers.add(it to 0) }
        var scanned = 0
        while (managers.isNotEmpty() && scanned++ < 64) {
            val (manager, depth) = managers.removeFirst()
            val items = call(manager, "getFragments") as? List<*> ?: continue
            for (fragment in items.take(64)) {
                if (fragment == null || !seen.add(fragment)) continue
                if (isEpisodeFragment(fragment)) result.add(fragment)
                if (depth < 8 && call(fragment, "isAdded") == true)
                    call(fragment, "getChildFragmentManager")?.let { managers.add(it to depth + 1) }
            }
        }
        val lifecycle = registered()
        for (fragment in lifecycle) {
            if (result.none { it === fragment }) result.add(fragment)
        }
        val valid = result.filter { call(it, "getActivity") === activity &&
            call(it, "isAdded") == true }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReport > 12_000L) {
            lastReport = now
            LogUtil.info("系统媒体: 通用 Fragment 扫描 managers=$scanned" +
                " visited=" + seen.size + " typed=" + result.size +
                " attached=" + valid.size + " lifecycle=" + lifecycle.size)
        }
        return valid
    }
}
