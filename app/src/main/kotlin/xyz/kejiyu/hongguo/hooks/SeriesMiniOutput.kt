package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/** One in-app rendering target, not a second decoder. */
internal class SeriesMiniOutput(activity: Activity, private val engine: Any) :
    TextureView.SurfaceTextureListener {
    val view = TextureView(activity).apply {
        isOpaque = true
        surfaceTextureListener = this@SeriesMiniOutput
    }
    @Volatile private var surface: Surface? = null
    @Volatile private var bound = false
    @Volatile private var closed = false
    @Volatile private var internalBinding = false
    private var nativeSurface = WeakReference(
        NativeMetadataAccess.call(engine, "getSurface") as? Surface)

    fun ownsOutput() = !closed && bound && surface?.isValid == true
    fun isOwnBinding(supplied: Any?) = internalBinding || supplied === surface

    fun rememberNativeOutput(supplied: Any?) {
        val next = when (supplied) {
            is Surface -> supplied
            is SurfaceHolder -> supplied.surface
            else -> null
        }
        if (next != null && next !== surface && next.isValid)
            nativeSurface = WeakReference(next)
    }

    private fun bind(next: Surface?): Boolean {
        if (NativeMetadataAccess.call(engine, "isReleased") == true) return false
        internalBinding = true
        return try {
            engine.javaClass.getMethod("setSurface", Surface::class.java).invoke(engine, next)
            true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗: 全集 Surface 绑定失败 " + e.javaClass.simpleName)
            false
        } finally { internalBinding = false }
    }

    fun rebind() {
        val next = surface ?: return
        if (closed || !next.isValid) return
        bound = bind(next)
        LogUtil.info("应用内小窗: 全集 Surface 绑定 result=$bound")
        LogUtil.incr(if (bound) "inAppMiniSeriesSurfaceBound" else "inAppMiniSeriesSurfaceFailed")
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        surface?.release()
        surface = Surface(texture)
        rebind()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        bound = false
        val old = surface
        surface = null
        old?.release()
        LogUtil.info("应用内小窗: 全集展示 Surface 离开页面")
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    fun close(restoreNative: Boolean) {
        if (closed) return
        closed = true
        bound = false
        if (restoreNative && surface != null) {
            val original = nativeSurface.get()?.takeIf { it.isValid }
            bind(original)
            LogUtil.info("应用内小窗: 全集原生 Surface 恢复 valid=" + (original != null))
        }
        surface?.release()
        surface = null
    }
}
