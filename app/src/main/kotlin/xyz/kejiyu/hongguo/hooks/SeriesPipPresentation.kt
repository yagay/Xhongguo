package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.View
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * YHongguo's presentation-only principle, adapted to the host which owns
 * its decoder in a separate Activity. Keep that Activity and its Surface
 * together. Android, not a reparented View, presents the same video.
 *
 * No hidden API, manifest rewrite, configuration override, new decoder,
 * source request, or interception of release/finish.
 */
internal object SeriesPipPresentation {
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private val main = Handler(Looper.getMainLooper())
    private data class Entry(
        val owner: WeakReference<Activity>,
        val engine: WeakReference<Any>,
        val deadline: Long,
        val wasPlaying: Boolean,
        var confirmed: Boolean = false
    )
    @Volatile private var entry: Entry? = null

    fun supported(activity: Activity): Boolean {
        if (activity.javaClass.name != SERIES || activity.isFinishing ||
            activity.isDestroyed || Build.VERSION.SDK_INT < 26) return false
        return try {
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } catch (_: Throwable) { false }
    }

    fun enter(activity: Activity, engine: Any, host: View?, wasPlaying: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || !supported(activity)) return false
        if (activity.isInPictureInPictureMode) return true
        if (host == null || !host.isAttachedToWindow || !host.isShown ||
            host.rootView !== activity.window.decorView.rootView) return false
        val rect = Rect()
        if (!host.getGlobalVisibleRect(rect) || rect.width() <= 0 || rect.height() <= 0) return false
        val ratio = if (rect.width() < rect.height()) Rational(9, 16) else Rational(16, 9)
        val current = Entry(WeakReference(activity), WeakReference(engine),
            SystemClock.uptimeMillis() + 2200L, wasPlaying)
        entry = current
        return try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setSourceRectHint(rect)
                .apply {
                    if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(false)
                }.build()
            val accepted = activity.enterPictureInPictureMode(params)
            LogUtil.info("全集小窗: 原生画中画请求 accepted=$accepted wasPlaying=$wasPlaying")
            LogUtil.incr(if (accepted) "seriesPipAccepted" else "seriesPipRejected")
            if (!accepted) {
                if (entry === current) entry = null
                false
            } else {
                main.postDelayed({
                    if (entry === current && !activity.isInPictureInPictureMode &&
                        !current.confirmed) {
                        entry = null
                        LogUtil.warn("全集小窗: 系统未确认画中画")
                    }
                }, 2300L)
                true
            }
        } catch (e: Throwable) {
            if (entry === current) entry = null
            LogUtil.warn("全集小窗: 原生画中画失败 " + e.javaClass.simpleName)
            false
        }
    }

    /** Only lifecycle pauses during this explicitly requested transition.
     * Do not block a user/media button, background audio focus, or release. */
    fun shouldProtectPause(candidate: Any?): Boolean {
        val current = entry ?: return false
        val a = current.owner.get() ?: return false
        if (!InAppMiniPlayer.enabled || !current.wasPlaying || candidate == null ||
            current.engine.get() !== candidate || a.isFinishing || a.isDestroyed ||
            SystemClock.uptimeMillis() > current.deadline ||
            SystemMediaBridge.userTransportActive() ||
            InAppMiniPlayer.ownsTransportCommand()) return false
        val lifecycle = Thread.currentThread().stackTrace.any {
            it.methodName in setOf("onPause", "onStop", "onLifeCycleOnPause",
                "onLifeCycleOnStop", "performPause", "performStop")
        }
        if (lifecycle) LogUtil.incr("seriesPipLifecyclePauseProtected")
        return lifecycle
    }

    fun onModeChanged(activity: Activity, inPip: Boolean) {
        val current = entry ?: return
        if (current.owner.get() !== activity) return
        current.confirmed = inPip
        LogUtil.info("全集小窗: 系统画中画状态=$inPip ownerFinishing=" + activity.isFinishing)
        LogUtil.incr(if (inPip) "seriesPipEntered" else "seriesPipExited")
        if (!inPip) entry = null
    }

    fun onActivityDestroyed(activity: Activity) {
        if (entry?.owner?.get() === activity) entry = null
    }

    fun activeFor(activity: Activity): Boolean =
        entry?.owner?.get() === activity && activity.isInPictureInPictureMode
}
