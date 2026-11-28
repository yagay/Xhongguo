package com.yagay.xhongguo.hooks

import android.app.Activity
import android.os.Looper
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import com.yagay.xhongguo.LogUtil

/** Bounded, read-only resolver. Never invoke a guessed obfuscated getter. */
internal object NativeBusinessResolver {
    const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val DETAIL = "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel"
    private const val ENGINE = "com.ss.ttvideoengine.TTVideoEngine"
    enum class Page { FEED, SERIES, UNKNOWN }
    private data class Shape(val fields: List<Field>, val methods: List<Method>)
    // Bounded process-local LRU. A weak value vanished under memory pressure
    // even while its native class remained loaded, causing repeated reflection.
    private val shapes = object : LinkedHashMap<Class<*>, Shape>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Class<*>, Shape>?): Boolean = size > 128
    }
    @Synchronized private fun shape(type: Class<*>): Shape {
        shapes[type]?.let { return it }
        val fields = ArrayList<Field>()
        var cursor: Class<*>? = type
        repeat(5) {
            val c = cursor ?: return@repeat
            if (c.name.startsWith("android.") || c.name.startsWith("java.") ||
                c.name.startsWith("androidx.")) { cursor = null; return@repeat }
            fields.addAll(runCatching { c.declaredFields.toList() }.getOrDefault(emptyList())
                .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }.take(128))
            cursor = c.superclass
        }
        val methods = runCatching { type.methods.toList() }.getOrDefault(emptyList())
            .filter { !Modifier.isStatic(it.modifiers) && !it.isBridge && !it.isSynthetic }
        val value = Shape(fields, methods)
        shapes[type] = value
        return value
    }
    fun noArg(type: Class<*>, name: String, returns: ((Class<*>) -> Boolean)? = null): Method? =
        shape(type).methods.filter {
            it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE &&
                (returns == null || returns(it.returnType))
        }.singleOrNull()
    private fun read(field: Field, owner: Any): Any? =
        runCatching { field.isAccessible = true; field.get(owner) }.getOrNull()
    fun fieldsOf(owner: Any?, typeName: String): List<Any> {
        if (owner == null) return emptyList()
        val found = ArrayList<Any>()
        for (f in shape(owner.javaClass).fields) {
            if (!NativeMetadataAccess.extendsType(f.type, typeName)) continue
            val value = read(f, owner) ?: continue
            if (found.none { it === value }) found.add(value)
        }
        return found
    }
    private fun businessField(f: Field): Boolean {
        val n = f.type.name
        return !f.type.isArray && !n.startsWith("java.") && !n.startsWith("android.") &&
            !n.startsWith("androidx.") && !n.startsWith("kotlin.") &&
            !n.startsWith("com.ss.ttvideoengine.") && !f.type.isEnum && !f.type.isInterface
    }
    private fun modelId(model: Any): String? =
        shape(model.javaClass).fields.singleOrNull { it.name == "vid" }
            ?.let { read(it, model)?.toString() }?.takeIf { it.isNotBlank() && it != "null" }
    fun seriesPresenter(activity: Activity, engine: Any?): Any? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val candidates = ArrayList<Any>()
        for (f in shape(activity.javaClass).fields.filter(::businessField).take(96)) {
            val value = read(f, activity) ?: continue
            if (value === activity || candidates.any { it === value }) continue
            if (shape(value.javaClass).fields.any {
                    NativeMetadataAccess.extendsType(it.type, NativeMetadataAccess.VIDEO) ||
                        NativeMetadataAccess.extendsType(it.type, DETAIL)
                }) candidates.add(value)
        }
        val id = NativePlaybackSession.videoId(engine)
        if (id != null) {
            val exact = candidates.filter { p ->
                fieldsOf(p, NativeMetadataAccess.VIDEO).any { modelId(it) == id }
            }
            if (exact.size == 1) return exact[0]
            if (exact.size > 1) return null
        }
        return candidates.singleOrNull()
    }
    fun pager(presenter: Any?): Any? {
        if (presenter == null) return null
        val candidates = ArrayList<Any>()
        for (f in shape(presenter.javaClass).fields) {
            if (!NativeMetadataAccess.extendsType(f.type, "androidx.viewpager.widget.ViewPager") &&
                !NativeMetadataAccess.extendsType(f.type, "androidx.viewpager2.widget.ViewPager2")) continue
            val value = read(f, presenter) ?: continue
            if (candidates.any { it === value }) continue
            if (noArg(value.javaClass, "getCurrentItem") { it == Integer.TYPE } != null &&
                noArg(value.javaClass, "getAdapter") != null) candidates.add(value)
        }
        return candidates.singleOrNull()
    }
    fun boundEngine(candidate: Any?): Any? {
        if (candidate == null) return null
        if (NativeMetadataAccess.extendsType(candidate.javaClass, ENGINE)) return candidate
        val observed = ArrayList<Any>(2)
        for (name in listOf("getVideoEngine", "getTTVideoEngine")) {
            val getter = noArg(candidate.javaClass, name) {
                NativeMetadataAccess.extendsType(it, ENGINE)
            } ?: continue
            val actual = runCatching { getter.invoke(candidate) }.getOrNull()
            if (actual != null && NativeMetadataAccess.extendsType(actual.javaClass, ENGINE) &&
                observed.none { it === actual }) observed.add(actual)
        }
        if (observed.isNotEmpty()) return observed.singleOrNull()
        // Fallback only on an existing object with the player wrapper contract.
        if (noArg(candidate.javaClass, "getSurfaceHolder") == null ||
            shape(candidate.javaClass).methods.none { it.name == "pause" &&
                it.parameterCount == 0 && it.returnType == Void.TYPE } ||
            shape(candidate.javaClass).methods.none { it.name in setOf("resume", "play") &&
                it.parameterCount == 0 && it.returnType == Void.TYPE }) return null
        return fieldsOf(candidate, ENGINE).singleOrNull()
    }
    fun knownPage(type: Class<*>?): Page = when {
        NativeMetadataAccess.extendsType(type, SERIES) -> Page.SERIES
        NativeMetadataAccess.extendsType(type, MAIN) -> Page.FEED
        else -> Page.UNKNOWN
    }
    fun page(activity: Activity?, engine: Any? = null): Page {
        if (activity == null || activity.isDestroyed || activity.isFinishing) return Page.UNKNOWN
        val known = knownPage(activity.javaClass)
        if (known != Page.UNKNOWN) return known
        if (Looper.myLooper() != Looper.getMainLooper() || engine == null) return Page.UNKNOWN
        val id = NativePlaybackSession.videoId(engine) ?: return Page.UNKNOWN
        val matching = NativeMetadataAccess.episodeFragments(activity).filter { f ->
            if (NativeMetadataAccess.call(f, "isHidden") == true ||
                !(NativeMetadataAccess.call(f, "isVisible") == true ||
                    NativeMetadataAccess.call(f, "isResumed") == true)) return@filter false
            val list = NativeMetadataAccess.call(f, "getDataList") as? List<*> ?: return@filter false
            val pos = (NativeMetadataAccess.call(f, "getCurrentPosition") as? Number)?.toInt()
                ?: return@filter false
            if (list.size !in 1..5000 || pos !in list.indices) return@filter false
            NativeMetadataAccess.video(list[pos])?.let(::modelId) == id
        }
        return if (matching.size == 1) Page.SERIES else Page.UNKNOWN
    }
}

/** Circuit breaker for module-owned callbacks, never native chain.proceed(). */
internal object FeatureFaultGuard {
    private data class Failure(val count: Int, val retryAt: Long)
    private val failures = HashMap<String, Failure>()
    @Synchronized fun available(name: String): Boolean {
        val failure = failures[name] ?: return true
        if (failure.count < 3) return true
        if (android.os.SystemClock.elapsedRealtime() < failure.retryAt) return false
        failures.remove(name)
        LogUtil.info("功能隔离: $name 冷却结束，重新检测")
        return true
    }
    @Synchronized private fun success(name: String) { failures.remove(name) }
    @Synchronized private fun failure(name: String, error: Throwable) {
        val count = (failures[name]?.count ?: 0) + 1
        failures[name] = Failure(count, android.os.SystemClock.elapsedRealtime() + 60_000L)
        LogUtil.warn("功能隔离: $name failures=$count disabled=" + (count >= 3) +
            " error=" + error.javaClass.name + ": " + error.message)
    }
    fun run(name: String, block: () -> Unit) {
        if (!available(name)) return
        try { block(); success(name) } catch (error: Exception) { failure(name, error) }
        catch (error: LinkageError) { failure(name, error) }
    }
    fun test(name: String, block: () -> Boolean): Boolean {
        if (!available(name)) return false
        return try { block().also { success(name) } } catch (error: Exception) { failure(name, error); false }
        catch (error: LinkageError) { failure(name, error); false }
    }
    @Synchronized fun reset() { failures.clear() }
}
