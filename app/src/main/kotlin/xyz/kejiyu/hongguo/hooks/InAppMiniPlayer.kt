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
import java.lang.ref.WeakReference
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
        val nativeEngine: WeakReference<Any>,
        var nativePauseGuardsUsed: Int = 0
    )

    @Volatile private var session: Session? = null
    private var lastPlayingController: Any? = null
    @Volatile private var lastNativeEngine = WeakReference<Any>(null)
    @Volatile private var suppressPauseUntil = 0L
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

    /** Captured ONLY from the actual TTVideoEngine.play event. The engine
     * is pinned to the session at dock-time, so a later preloaded engine
     * cannot have its pause suppressed by accident. */
    fun rememberNativeEngine(candidate: Any?) {
        if (candidate?.javaClass?.name ==
            "com.ss.ttvideoengine.TTVideoEngine") {
            lastNativeEngine = WeakReference(candidate)
        }
    }

    /** Native feed navigation calls TTVideoEngine.pause directly, bypassing
     * the existing VideoController pause hook. Protect only the engine that
     * was playing when we docked, and only during the brief tab transition.
     * Never block user media commands after the transition or app background. */
    fun shouldKeepNativeEnginePlaying(candidate: Any?): Boolean {
        val current = session ?: return false
        if (!enabled || restoreInProgress || current.activity.isFinishing ||
            !current.activity.hasWindowFocus() ||
            candidate == null || candidate !== current.nativeEngine.get() ||
            android.os.SystemClock.uptimeMillis() > suppressPauseUntil)
            return false
        return synchronized(current) {
            if (current.nativePauseGuardsUsed >= 3) false
            else {
                current.nativePauseGuardsUsed++
                LogUtil.info("应用内小窗：阻止页面切换引发的 TTVideoEngine.pause" +
                    " count=" + current.nativePauseGuardsUsed)
                LogUtil.incr("inAppMiniNativeEnginePauseIntercept")
                true
            }
        }
    }

    private fun nativeEnginePlaying(candidate: Any?): Boolean? {
        if (candidate == null) return null
        return try {
            candidate.javaClass.methods.firstOrNull {
                it.name == "isPlaying" && it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.invoke(candidate) as? Boolean
        } catch (_: Throwable) { null }
    }

    fun shouldKeepPlaying(controller: Any?): Boolean {
        val s = session ?: return false
        if (!enabled || restoreInProgress || s.activity.isFinishing) return false
        if (controller !== lastPlayingController) return false
        return android.os.SystemClock.uptimeMillis() <= suppressPauseUntil
    }

    private var lastResumeAttemptAt = 0L

    /**
     * Some target builds call native pause when the Home fragment loses focus.
     * Retry only once after the page switch, never create another media player.
     * Reflection is deliberately limited to the tracked playing controller.
     */
    fun resumeAfterTabSwitch() {
        val active = session ?: return
        if (!enabled || active.activity.isFinishing) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastResumeAttemptAt < 1000L) return
        lastResumeAttemptAt = now
        // Recheck the exact engine pinned at dock-time. If the host paused
        // without going through the intercepted method, retry once only when
        // the native engine confirms it really is NOT playing.
        val engine = active.nativeEngine.get()
        val nativePlaying = nativeEnginePlaying(engine)
        if (engine != null && nativePlaying == false) {
            try {
                val play = engine.javaClass.methods.firstOrNull {
                    it.name == "play" && it.parameterCount == 0
                }
                if (play != null && session === active &&
                    android.os.SystemClock.uptimeMillis() <= suppressPauseUntil) {
                    play.invoke(engine)
                    LogUtil.info("应用内小窗：原生引擎确认暂停，尝试恢复当前播放")
                    LogUtil.incr("inAppMiniNativeEngineResume")
                    return
                }
            } catch (e: Throwable) {
                LogUtil.warn("应用内小窗：原生引擎恢复失败: " + e.message)
            }
        }
        if (nativePlaying == true) {
            LogUtil.info("应用内小窗：当前原生引擎仍在播放，无需再次启动")
            return
        }
        val controller = lastPlayingController ?: run {
            LogUtil.info("应用内小窗：VideoController 未捕获，使用原生引擎保护")
            return
        }
        for (name in listOf("resume", "resumeVideo", "play")) {
            try {
                val method = controller.javaClass.methods.firstOrNull { m ->
                    m.name == name && m.parameterCount == 0
                } ?: continue
                method.invoke(controller)
                LogUtil.info("应用内小窗：原播放器尝试继续播放，method=" + name)
                LogUtil.incr("inAppMiniResumeAttempt")
                return
            } catch (e: Throwable) {
                LogUtil.warn("应用内小窗：恢复播放方法 " + name + " 不可用: " + e.message)
            }
        }
        LogUtil.warn("应用内小窗：播放器没有找到无参 resume/play 方法")
    }

    fun hasWindow(): Boolean = session != null

    fun activeFor(activity: Activity): Boolean = session?.activity === activity

    /**
     * Use a stable native host View; moving a bare TextureView or SurfaceView
     * tears down its surface on many OS builds, so neither is directly moved.
     */
    /** Returns true only for a real video renderer, never a static cover image. */
    fun hasVideoRenderer(root: View?, maxDepth: Int = 9): Boolean {
        if (root == null || maxDepth < 0) return false
        if (root is android.view.TextureView || root is android.view.SurfaceView) return true
        if (root.javaClass.name == "com.ss.android.videoshop.mediaview.LayerHostMediaLayout")
            return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasVideoRenderer(root.getChildAt(i), maxDepth - 1)) return true
            }
        }
        return false
    }

    fun dock(activity: Activity, videoHost: View?): Boolean {
        if (!enabled || activity.isFinishing) {
            LogUtil.info("应用内小窗跳过: 模块开关未启用或 Activity 已关闭")
            return false
        }
        if (session != null) return session?.activity === activity
        val video = videoHost
        if (video == null) {
            LogUtil.warn("应用内小窗跳过: 未找到可复用的视频渲染容器")
            LogUtil.incr("inAppMiniNoRenderer")
            return false
        }
        if (!video.isShown || !video.isAttachedToWindow || video.width < dp(activity, 80f) ||
            video.height < dp(activity, 100f)) {
            LogUtil.warn("应用内小窗跳过: 候选视频容器未显示 " +
                video.javaClass.name + " " + video.width + "x" + video.height)
            return false
        }
        if (!hasVideoRenderer(video)) {
            LogUtil.warn("应用内小窗跳过: 候选容器没有原生视频渲染节点 " +
                video.javaClass.name)
            return false
        }
        val parent = video.parent as? ViewGroup ?: run {
            LogUtil.warn("应用内小窗跳过: 视频容器没有可复用的父布局")
            return false
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout
            ?: run {
                LogUtil.warn("应用内小窗跳过: Activity content 非 FrameLayout")
                return false
            }
        if (parent === content) {
            LogUtil.warn("应用内小窗跳过: 视频容器直接属于 Activity 主内容，避免破坏主页面")
            return false
        }
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
        val current = Session(activity, video, parent, index,
            originalParams, container, WeakReference(lastNativeEngine.get()))
        session = current
        LogUtil.info("应用内小窗：开始保护切换时的原生播放器" +
            " engineBound=" + (current.nativeEngine.get() != null) +
            " playing=" + nativeEnginePlaying(current.nativeEngine.get()))
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
            LogUtil.info("应用内小窗：已移动原生播放器到悬浮容器 " +
                video.javaClass.name + " / parent=" + parent.javaClass.name)
            LogUtil.incr("inAppMiniDock")
            return true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：移动播放器失败 " + e.javaClass.simpleName +
                ": " + e.message)
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
