package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import xyz.kejiyu.hongguo.LogUtil

/**
 * Opt-in native host-binding experiment, scoped to the analyzed 7.4.2.32 APK.
 * Observe existing short-player wrappers; never create or pull a player
 * from the shared pool. No system window, no direct TTVideoEngine.setSurface.
 */
internal object NativeSeriesHost {
    @Volatile var enabled = false
    private val wrappers = ArrayList<WeakReference<Any>>()
    @Volatile private var changeMethod: Method? = null
    @Volatile private var engineMethod: Method? = null
    @Volatile private var holderMethod: Method? = null
    @Volatile private var hostField: java.lang.reflect.Field? = null

    fun resolve(loader: ClassLoader): Class<*>? = try {
        val c = Class.forName("l65.x", false, loader)
        val engine = Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, loader)
        val holder = Class.forName("q65.a", false, loader)
        val change = c.methods.singleOrNull { it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(FrameLayout::class.java,
                holder, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Integer::class.java)) }
        val read = c.methods.singleOrNull { it.name == "I" &&
            it.parameterCount == 0 && it.returnType == engine }
        val surface = c.methods.singleOrNull { it.name == "getSurfaceHolder" &&
            it.parameterCount == 0 && it.returnType == holder }
        val field = c.getDeclaredField("G").takeIf { it.type == FrameLayout::class.java }
        if (change == null || change.name != "z" || read == null || surface == null || field == null) null
        else {
            field.isAccessible = true
            changeMethod = change
            engineMethod = read
            holderMethod = surface
            hostField = field
            c
        }
    } catch (_: Throwable) { null }

    fun observe(candidate: Any?) {
        if (!enabled || candidate == null) return
        synchronized(wrappers) {
            wrappers.removeAll { it.get() == null }
            if (wrappers.none { it.get() === candidate }) wrappers.add(WeakReference(candidate))
            while (wrappers.size > 48) wrappers.removeAt(0)
        }
    }

    private fun engineOf(wrapper: Any): Any? =
        runCatching { engineMethod?.invoke(wrapper) }.getOrNull()
    private fun holderOf(wrapper: Any): Any? =
        runCatching { holderMethod?.invoke(wrapper) }.getOrNull()
    private fun holderView(wrapper: Any): View? =
        NativeMetadataAccess.call(holderOf(wrapper), "getView") as? View

    private fun inside(view: View?, host: View): Boolean {
        var child = view
        repeat(30) {
            if (child === host) return true
            child = child?.parent as? View
            if (child == null) return false
        }
        return false
    }

    fun prepare(activity: Activity, engine: Any, renderer: View): Binding? {
        if (!enabled || changeMethod == null) return null
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull()
        if (version != "7.4.2.32") {
            LogUtil.warn("原生小窗: 未验证 APK 版本，拒绝实验绑定 version=$version")
            return null
        }
        if (activity.window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) return null
        val matches = synchronized(wrappers) { wrappers.mapNotNull { it.get() } }
            .filter { engineOf(it) === engine }
            .mapNotNull { wrapper ->
                val host = runCatching { hostField?.get(wrapper) as? FrameLayout }.getOrNull()
                    ?: return@mapNotNull null
                val view = holderView(wrapper) ?: return@mapNotNull null
                if (!inside(view, host) ||
                    host.rootView !== activity.window.decorView.rootView ||
                    !(inside(view, renderer) || inside(renderer, host))) null
                else Binding(wrapper, engine, host)
            }
        LogUtil.info("原生小窗: 包装层候选=" + matches.size)
        return matches.singleOrNull()
    }

    internal class Binding(private val wrapper: Any, val engine: Any,
        private val originalHost: FrameLayout) {
        private var target: FrameLayout? = null
        private var changed = false

        fun bind(host: FrameLayout): Boolean {
            if (!enabled || engineOf(wrapper) !== engine || !host.isAttachedToWindow ||
                NativeMetadataAccess.call(engine, "isReleased") == true) return false
            return try {
                if (target !== host || !inside(holderView(wrapper), host)) {
                    // Exact argument tuple observed in ShortSeriesSingleFragment.ph()
                    // at 0x122. No speculative shared-pool writes or audio-manager calls.
                    changeMethod!!.invoke(wrapper, host, null, false, false, false, 0)
                    changed = true
                    target = host
                }
                val matched = engineOf(wrapper) === engine && inside(holderView(wrapper), host)
                LogUtil.info("原生小窗: changePlayerHost 已调用 hostMatched=$matched")
                LogUtil.incr(if (matched) "nativeMiniHostBound" else "nativeMiniHostUnconfirmed")
                matched
            } catch (e: Throwable) {
                LogUtil.warn("原生小窗: changePlayerHost 失败 " + e.javaClass.simpleName)
                false
            }
        }

        fun ready(): Boolean = changed && target?.let {
            inside(holderView(wrapper), it) &&
                NativeMetadataAccess.call(holderOf(wrapper), "isSurfaceAvailable") == true
        } == true

        fun transport(play: Boolean): Boolean = try {
            if (engineOf(wrapper) !== engine ||
                NativeMetadataAccess.call(engine, "isReleased") == true) false
            else {
                wrapper.javaClass.getMethod(if (play) "resume" else "pause").invoke(wrapper)
                true
            }
        } catch (_: Throwable) { false }

        fun restore() {
            if (!changed || engineOf(wrapper) !== engine ||
                NativeMetadataAccess.call(engine, "isReleased") == true) return
            try {
                changeMethod!!.invoke(wrapper, originalHost, null, false, false, false, 0)
                LogUtil.info("原生小窗: 恢复原生容器")
            } catch (e: Throwable) {
                LogUtil.warn("原生小窗: 原生容器恢复失败 " + e.javaClass.simpleName)
            } finally { changed = false; target = null }
        }
    }
}
