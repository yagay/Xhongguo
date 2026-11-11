package xyz.kejiyu.hongguo.hooks

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
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
        var nativeEngine: WeakReference<Any>,
        var nativePauseGuardsUsed: Int = 0,
        @Volatile var userPaused: Boolean = false,
        var snapAnimation: ValueAnimator? = null,
        var playbackButton: WeakReference<TextView> = WeakReference(null),
        var presentedActivity: Activity = activity,
        var detailTransitionUntil: Long = 0L,
        var resumeAfterDetails: Boolean = false
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
        val now = android.os.SystemClock.uptimeMillis()
        val inDetailsTransition = now <= current.detailTransitionUntil
        if (!enabled || restoreInProgress || current.userPaused ||
            current.activity.isFinishing ||
            !(current.presentedActivity.hasWindowFocus() || inDetailsTransition) ||
            candidate == null || candidate !== current.nativeEngine.get() ||
            now > suppressPauseUntil)
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
        if (!enabled || restoreInProgress || s.userPaused ||
            SystemMediaBridge.isExplicitMediaCommandInProgress() ||
            s.activity.isFinishing) return false
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
        if (!enabled || active.activity.isFinishing || active.userPaused) return
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

    /** Keep mini playback controls in sync with the native engine and
     * notification media actions, without polling or creating a player. */
    fun onNativeEngineAction(candidate: Any?, action: String) {
        val current = session ?: return
        if (candidate !== current.nativeEngine.get()) return
        if (action !in setOf("play", "pause", "stop")) return
        if (SystemMediaBridge.isExplicitMediaCommandInProgress()) {
            current.userPaused = action != "play"
            current.resumeAfterDetails = action == "play"
        }
        val button = current.playbackButton.get() ?: return
        val paused = action != "play"
        button.post {
            if (session === current) {
                button.text = if (paused) "▶" else "Ⅱ"
                button.contentDescription =
                    if (paused) "继续播放" else "暂停播放"
            }
        }
    }

    /** A new official playback owner means this floating surface belongs
     * to an old video. Restore it rather than leave an invisible player
     * decoupled from the notification's active engine. */
    /**
     * The detail screen is a different Activity in the official APK.
     * Mark the transition BEFORE startActivity() pauses its source window.
     * It is still the SAME TTVideoEngine and SAME media surface.
     */
    fun prepareForDetails(owner: Activity): Boolean {
        val current = session ?: return false
        if (current.activity !== owner || !enabled) return false
        val until = android.os.SystemClock.uptimeMillis() + 5000L
        current.resumeAfterDetails =
            nativeEnginePlaying(current.nativeEngine.get()) == true &&
            !current.userPaused
        current.detailTransitionUntil = until
        suppressPauseUntil = until
        LogUtil.info("应用内小窗：已准备跨 Activity 剧集详情切换" +
            " nativeEngine=" + (current.nativeEngine.get() != null))
        LogUtil.incr("inAppMiniDetailsPrepared")
        return true
    }

    private fun isDetailsActivity(activity: Activity): Boolean =
        activity.javaClass.name in setOf(
            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity"
        )

    /** Attach the EXISTING overlay to the detail Activity window.
     * No player is created, restarted, or seeked. Android may still
     * recreate a video Surface when the native container moves; the
     * diagnostic records the transfer independently of playback state.
     */
    fun attachToDetails(activity: Activity): Boolean {
        val current = session ?: return false
        if (!enabled || !isDetailsActivity(activity) || activity.isFinishing ||
            android.os.SystemClock.uptimeMillis() > current.detailTransitionUntil ||
            current.presentedActivity === activity) return false
        val newParent = activity.findViewById<ViewGroup>(
            android.R.id.content) as? FrameLayout ?: return false
        val overlay = current.overlay
        return try {
            current.snapAnimation?.cancel()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            val oldParams = overlay.layoutParams as? FrameLayout.LayoutParams
            val params = FrameLayout.LayoutParams(
                oldParams?.width ?: dp(activity, 124f),
                oldParams?.height ?: dp(activity, 206f),
                Gravity.END or Gravity.BOTTOM
            ).apply {
                rightMargin = dp(activity, 12f)
                bottomMargin = dp(activity, 96f)
            }
            newParent.addView(overlay, params)
            current.presentedActivity = activity
            // No manual player controls exist behind the detail page. Keep
            // the pinned decoder active while its mini surface is visible;
            // explicit mini/media-card pause remains allowed.
            suppressPauseUntil = android.os.SystemClock.uptimeMillis() +
                30L * 60L * 1000L
            LogUtil.info("应用内小窗：详情页已接管原有视频窗口" +
                " engineRetained=" + (current.nativeEngine.get() != null))
            LogUtil.incr("inAppMiniDetailsAttached")
            true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：详情页视频窗口转移失败 " +
                e.javaClass.simpleName)
            // Restore ownership even after a failed cross-window reparent.
            restore()
            false
        }
    }

    fun onPlaybackOwnerChanged(previous: Any, current: Any, samePage: Boolean) {
        val active = session ?: return
        if (previous === current || active.nativeEngine.get() !== previous) return
        if (samePage && active.source.isAttachedToWindow) {
            active.nativeEngine = WeakReference(current)
            active.userPaused = false
            active.playbackButton.get()?.let { button ->
                button.post {
                    if (session === active) {
                        button.text = "Ⅱ"
                        button.contentDescription = "暂停播放"
                    }
                }
            }
            LogUtil.info("应用内小窗：同页面新集复用小窗，更新原生播放所有者")
            LogUtil.incr("inAppMiniEngineRebound")
        } else {
            LogUtil.info("应用内小窗：切换播放页面，归位旧视频渲染层")
            LogUtil.incr("inAppMiniOwnerTransferRestore")
            restore()
        }
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
            clipToOutline = true
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

    private fun textButton(activity: Activity, label: String, description: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = description
            background = roundedBackground(200)
        }

    /** The mini view floats over the native tab layout; keep its whole
     * rectangle inside Activity content and above the bottom navigation. */
    private data class DragLimits(
        val minRight: Int,
        val maxRight: Int,
        val minBottom: Int,
        val maxBottom: Int
    )

    private fun dragLimits(current: Session): DragLimits {
        val parent = current.overlay.parent as? ViewGroup
        val w = (parent?.width ?: current.activity.resources.displayMetrics.widthPixels)
            .coerceAtLeast(0)
        val h = (parent?.height ?: current.activity.resources.displayMetrics.heightPixels)
            .coerceAtLeast(0)
        val maxRightEdge = (w - current.overlay.width).coerceAtLeast(0)
        val minRight = dp(current.activity, 6f).coerceAtMost(maxRightEdge)
        val maxRight = (maxRightEdge - dp(current.activity, 6f))
            .coerceAtLeast(minRight)
        val maxBottomEdge = (h - current.overlay.height).coerceAtLeast(0)
        val minBottom = dp(current.activity, 72f).coerceAtMost(maxBottomEdge)
        val maxBottom = (maxBottomEdge - dp(current.activity, 8f))
            .coerceAtLeast(minBottom)
        return DragLimits(minRight, maxRight, minBottom, maxBottom)
    }

    /** Smooth native-PiP-style left/right edge snap. Position is held in
     * FrameLayout margins so the window survives normal page relayouts. */
    private fun snapToEdge(current: Session) {
        if (session !== current) return
        val frame = current.overlay
        val params = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        val limits = dragLimits(current)
        val nearestRight = if (params.rightMargin <=
            (limits.minRight + limits.maxRight) / 2) limits.minRight
            else limits.maxRight
        current.snapAnimation?.cancel()
        if (params.rightMargin == nearestRight) return
        val anim = ValueAnimator.ofInt(params.rightMargin, nearestRight)
        current.snapAnimation = anim
        anim.duration = 170L
        anim.addUpdateListener { value ->
            if (session === current) {
                val lp = frame.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.rightMargin = value.animatedValue as Int
                    frame.layoutParams = lp
                }
            }
        }
        anim.start()
        LogUtil.incr("inAppMiniEdgeSnap")
    }

    private fun invokeNative(current: Session, name: String): Boolean {
        val engine = current.nativeEngine.get() ?: return false
        return try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return false
            method.invoke(engine)
            true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：原生播放操作 " + name +
                " 失败: " + e.javaClass.simpleName)
            false
        }
    }

    private fun togglePlayback(current: Session, button: TextView) {
        if (session !== current || !enabled) return
        val currentlyPlaying = nativeEnginePlaying(current.nativeEngine.get())
        val pause = when (currentlyPlaying) {
            true -> true
            false -> false
            null -> !current.userPaused
        }
        // Explicit user pause must bypass the navigation pause guard.
        if (pause) {
            current.userPaused = true
            suppressPauseUntil = 0L
        }
        if (invokeNative(current, if (pause) "pause" else "play")) {
            current.userPaused = pause
            current.resumeAfterDetails = !pause
            button.text = if (pause) "▶" else "Ⅱ"
            button.contentDescription = if (pause) "继续播放" else "暂停播放"
            LogUtil.info("应用内小窗：用户" + if (pause) "暂停" else "继续播放")
            LogUtil.incr("inAppMiniUserPlaybackAction")
        } else {
            if (pause) current.userPaused = false
            LogUtil.warn("应用内小窗：未找到当前引擎的播放控制方法")
        }
    }

    /** Closing a native-style mini player dismisses it AND pauses the
     * pinned video. Merely returning Home uses restore() without pausing. */
    private fun dismiss(current: Session) {
        if (session !== current) return
        val wasPlaying = nativeEnginePlaying(current.nativeEngine.get()) != false &&
            !current.userPaused
        restore()
        if (wasPlaying) invokeNative(current, "pause")
        LogUtil.info("应用内小窗：用户关闭，已归位并暂停当前视频=" + wasPlaying)
        LogUtil.incr("inAppMiniDismiss")
    }

    /** One single tap returns to the native playing page; dragging the
     * entire video area moves the window. The tiny drag-only handle is gone.
     * Close/pause controls are independent, so taps on them never expand. */
    private fun addControls(current: Session) {
        val activity = current.activity
        val container = current.overlay
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        val gestures = View(activity).apply {
            isClickable = true
            contentDescription = "应用内小窗，点击返回播放页，拖动移动位置"
            setOnClickListener {
                if (session === current) returnToVideo()
            }
        }
        container.addView(gestures, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        var downX = 0f
        var downY = 0f
        var startRight = 0
        var startBottom = 0
        var moving = false
        gestures.setOnTouchListener { view, event ->
            val lp = container.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    current.snapAnimation?.cancel()
                    downX = event.rawX
                    downY = event.rawY
                    startRight = lp.rightMargin
                    startBottom = lp.bottomMargin
                    moving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moving && dx * dx + dy * dy > touchSlop * touchSlop)
                        moving = true
                    if (moving && session === current) {
                        val limits = dragLimits(current)
                        lp.rightMargin = (startRight - dx.toInt())
                            .coerceIn(limits.minRight, limits.maxRight)
                        lp.bottomMargin = (startBottom - dy.toInt())
                            .coerceIn(limits.minBottom, limits.maxBottom)
                        container.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moving) {
                        snapToEdge(current)
                        LogUtil.incr("inAppMiniDragged")
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moving) snapToEdge(current)
                    true
                }
                else -> false
            }
        }

        val controlSize = dp(activity, 34f)
        val close = textButton(activity, "×", "关闭小窗").apply {
            setOnClickListener { dismiss(current) }
        }
        container.addView(close, FrameLayout.LayoutParams(
            controlSize, controlSize, Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(activity, 3f)
            rightMargin = dp(activity, 3f)
        })
        // Native transport is optional. Do not display a dead pause button
        // if the actual playing engine was not observed at docking time.
        if (current.nativeEngine.get() != null) {
            val paused = nativeEnginePlaying(current.nativeEngine.get()) == false
            val pause = textButton(activity,
                if (paused) "▶" else "Ⅱ",
                if (paused) "继续播放" else "暂停播放")
            current.playbackButton = WeakReference(pause)
            pause.setOnClickListener { togglePlayback(current, pause) }
            container.addView(pause, FrameLayout.LayoutParams(
                controlSize, controlSize, Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(activity, 3f)
                rightMargin = dp(activity, 3f)
            })
        }
    }

    /** Called BEFORE a native Home tab click, or by mini expand. */
    fun restore(): Boolean {
        val old = session ?: return false
        session = null
        restoreInProgress = true
        suppressPauseUntil = 0L
        old.snapAnimation?.cancel()
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
        val active = session ?: return
        if (active.activity === activity ||
            active.presentedActivity === activity) {
            LogUtil.info("应用内小窗：宿主页面关闭，恢复原视频布局")
            restore()
        }
    }

    /** Back from details returns to the SAME native video, not a new one. */
    private fun recoverAfterDetails(active: Session) {
        if (!active.resumeAfterDetails || active.userPaused) return
        val engine = active.nativeEngine.get() ?: return
        val host = active.activity
        host.window?.decorView?.postDelayed({
            if (!host.isFinishing &&
                SinglePlaybackOwner.isOwner(engine) &&
                nativeEnginePlaying(engine) == false) {
                if (invokeNative(active, "play")) {
                    LogUtil.info("应用内小窗：详情返回复用原引擎继续播放")
                    LogUtil.incr("inAppMiniDetailsResume")
                }
            }
        }, 220L)
    }

    fun onSourceActivityResumed(activity: Activity) {
        val active = session ?: return
        if (active.activity === activity &&
            active.presentedActivity !== activity) {
            restore()
            recoverAfterDetails(active)
            LogUtil.incr("inAppMiniDetailsReturned")
        }
    }

    /** Opens Home using the target app's original bottom-tab click handler. */
    var openHomeTab: ((Activity) -> Unit)? = null

    fun returnToVideo() {
        val active = session ?: return
        val host = active.activity
        val details = active.presentedActivity
            .takeIf { it !== host && isDetailsActivity(it) }
        if (returningHome) return
        returningHome = true
        try {
            restore()
            if (details != null) {
                details.finish()
                recoverAfterDetails(active)
            } else if (host.javaClass.name ==
                "com.dragon.read.pages.main.MainFragmentActivity")
                openHomeTab?.invoke(host)
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗：返回播放页失败 " + e.message)
        } finally { returningHome = false }
    }
}
