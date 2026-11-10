package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import xyz.kejiyu.hongguo.LogUtil

/**
 * In-app (not system-overlay) mini video.
 *
 * Moves the EXISTING player host View, rather than starting a second player,
 * URL, playback session, or media decoder. No SYSTEM_ALERT_WINDOW permission
 * and no modifications to status / navigation bar insets.
 *
 * Experimental: only activate for a confirmed live LayerHostMediaLayout on the
 * main tab screen. If the target rebuilds its fragments/Surface during a switch,
 * safely restore the original parent and let native playback take over.
 */
internal object InAppMiniPlayer {
    @Volatile var enabled: Boolean = false

    private data class Session(
        val activity: Activity,
        val source: View,
        val originalParent: ViewGroup,
        val originalIndex: Int,
        val originalLayoutParams: ViewGroup.LayoutParams,
        val overlay: FrameLayout,
    )

    private var session: Session? = null
    private var lastPlayingController: Any? = null
    private var suppressPauseUntil = 0L
    private var restoreInProgress = false
    private var returningHome = false
    private const val FLOAT_TAG = "XHONGGUO_IN_APP_VIDEO_WINDOW"

    private fun dp(activity: Activity, n: Float): Int =
        (activity.resources.displayMetrics.density * n + 0.5f).toInt()

    private fun roundedBackground(alpha: Int = 245): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(alpha, 16, 17, 19))
            cornerRadius = 18f
        }

    fun rememberPlayingController(controller: Any?) {
        if (controller != null) lastPlayingController = controller
    }

    fun shouldKeepPlaying(controller: Any?): Boolean {
        val s = session ?: return false
        if (!enabled || restoreInProgress || s.activity.isFinishing) return false
        if (controller !== lastPlayingController) return false
        return android.os.SystemClock.uptimeMillis() <= suppressPauseUntil
    }

    fun hasWindow(): Boolean = session != null

    fun activeFor(activity: Activity): Boolean = session?.activity === activity

    /**
     * Use a stable native host View; moving a bare TextureView or SurfaceView
     * tears down its surface on many OS builds, so neither is directly moved.
     */
    fun dock(activity: Activity, videoHost: View?): Boolean {
        if (!enabled || activity.isFinishing) return false
        if (session != null) return session?.activity === activity
        val video = videoHost ?: return false
        if (!video.isShown || !video.isAttachedToWindow || video.width < dp(activity, 80f) ||
            video.height < dp(activity, 100f)) return false
        if (video.javaClass.name != "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") {
            return false
        }
        val parent = video.parent as? ViewGroup ?: return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout
            ?: return false
        if (parent === content) return false
        val index = parent.indexOfChild(video)
        if (index < 0) return false
        val originalParams = video.layoutParams ?: return false

        val container = FrameLayout(activity).apply {
            tag = FLOAT_TAG
            clipChildren = true
            clipToPadding = true
            background = roundedBackground()
            elevation = dp(activity, 16f).toFloat()
            isClickable = true
        }
        val width = dp(activity, 124f)
        val height = dp(activity, 206f)
        val lp = FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(activity, 12f)
            // Remain ABOVE the app bottom tabs and Android's three-button bar.
            bottomMargin = dp(activity, 96f)
        }
        val current = Session(activity, video, parent, index, originalParams, container)
        session = current
        suppressPauseUntil = android.os.SystemClock.uptimeMillis() + 2200L
        try {
            // Avoid destroying the original video engine. Surface reconstruction
            // is handled by the media host itself after reattachment.
            parent.removeView(video)
            container.addView(video, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            content.addView(container, lp)
            addControls(current)
            LogUtil.info("应用内小窗：已移动原生播放器到悬浮容器")
            LogUtil.incr("inAppMiniDock")
            return true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：移动播放器失败 " + e.message)
            restore()
            return false
        }
    }

    private fun textButton(activity: Activity, label: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = when (label) {
                "×" -> "关闭悬浮窗"
                "⤢" -> "返回播放页"
                else -> "拖动悬浮窗"
            }
            background = roundedBackground(195)
        }

    private fun addControls(current: Session) {
        val activity = current.activity
        val container = current.overlay
        val drag = textButton(activity, "⋮⋮").apply {
            contentDescription = "拖动悬浮窗"
            textSize = 16f
        }
        val expand = textButton(activity, "⤢")
        val close = textButton(activity, "×")
        val controlSize = dp(activity, 35f)

        container.addView(drag, FrameLayout.LayoutParams(
            dp(activity, 46f), controlSize, Gravity.TOP or Gravity.START
        ))
        container.addView(close, FrameLayout.LayoutParams(
            controlSize, controlSize, Gravity.TOP or Gravity.END
        ))
        container.addView(expand, FrameLayout.LayoutParams(
            controlSize, controlSize, Gravity.BOTTOM or Gravity.END
        ))

        var startX = 0f
        var startY = 0f
        var baseRight = 0
        var baseBottom = 0
        drag.setOnTouchListener { _, event ->
            val params = container.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    baseRight = params.rightMargin
                    baseBottom = params.bottomMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = activity.resources.displayMetrics
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    params.rightMargin = (baseRight - dx).coerceIn(
                        dp(activity, 4f), (dm.widthPixels - container.width).coerceAtLeast(dp(activity, 4f))
                    )
                    params.bottomMargin = (baseBottom - dy).coerceIn(
                        dp(activity, 70f), (dm.heightPixels - container.height).coerceAtLeast(dp(activity, 70f))
                    )
                    container.layoutParams = params
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        expand.setOnClickListener { returnToVideo() }
        close.setOnClickListener {
            restore()
            LogUtil.info("应用内小窗：用户关闭")
        }
    }

    /** Called BEFORE a native Home tab click, or by mini expand. */
    fun restore(): Boolean {
        val old = session ?: return false
        session = null
        restoreInProgress = true
        suppressPauseUntil = 0L
        try {
            val currentParent = old.source.parent as? ViewGroup
            currentParent?.removeView(old.source)
            if (old.source.parent == null) {
                val index = old.originalIndex.coerceIn(0, old.originalParent.childCount)
                old.originalParent.addView(old.source, index, old.originalLayoutParams)
            }
            (old.overlay.parent as? ViewGroup)?.removeView(old.overlay)
            old.source.requestLayout()
            LogUtil.incr("inAppMiniRestore")
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：归位播放器失败 " + e.message)
            try { (old.overlay.parent as? ViewGroup)?.removeView(old.overlay) }
            catch (_: Throwable) {}
        } finally {
            restoreInProgress = false
        }
        return true
    }

    fun onActivityClosed(activity: Activity) {
        if (session?.activity === activity) restore()
    }

    /** Opens Home using the target app's original bottom-tab click handler. */
    var openHomeTab: ((Activity) -> Unit)? = null

    fun returnToVideo() {
        val host = session?.activity ?: return
        if (returningHome) return
        returningHome = true
        try {
            restore()
            openHomeTab?.invoke(host)
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：返回播放页失败 " + e.message)
        } finally { returningHome = false }
    }
}
