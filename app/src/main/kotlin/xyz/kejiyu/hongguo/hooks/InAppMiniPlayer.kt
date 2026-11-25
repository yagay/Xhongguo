package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs
import xyz.kejiyu.hongguo.LogUtil

/**
 * One native video host, one movable in-app PiP-style window. A display
 * Activity is not the owner of the decoder. Keep the original owner alive
 * only as long as the native lifecycle allows; never suppress release.
 */
internal object InAppMiniPlayer {
    @Volatile var enabled = false
    private val main = Handler(Looper.getMainLooper())
    private const val FLOAT_TAG = "XHONGGUO_IN_APP_VIDEO_WINDOW"
    private const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private data class Session(
        val activity: Activity, val source: View,
        val originalParent: ViewGroup, val originalIndex: Int,
        val originalLayoutParams: ViewGroup.LayoutParams,
        val overlay: FrameLayout, val engine: Any,
        var display: Activity? = null,
        var x: Float = 1f, var y: Float = 0.78f, var large: Boolean = false,
        var returnRequested: Boolean = false,
        var controls: List<View> = emptyList(), var playButton: TextView? = null,
        var controlsGeneration: Int = 0
    )
    @Volatile private var session: Session? = null
    @Volatile private var engineRef = WeakReference<Any>(null)
    @Volatile private var engineActivity = WeakReference<Activity>(null)
    @Volatile private var controllerRef = WeakReference<Any>(null)
    @Volatile private var transitionUntil = 0L
    @Volatile private var transitionPause = false
    @Volatile private var manualCommand = false

    private fun dp(a: Activity, n: Float) = (a.resources.displayMetrics.density * n + 0.5f).toInt()
    private fun shape(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
    private fun isAlive(a: Activity) = !a.isFinishing && !a.isDestroyed
    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()
    private fun playbackState(engine: Any?): Int? =
        (NativeMetadataAccess.call(engine, "getPlaybackState") as? Number)?.toInt()

    private fun isPlaying(engine: Any?): Boolean? {
        if (engine == null || NativeMetadataAccess.call(engine, "isReleased") == true)
            return false
        val state = playbackState(engine)
        if (state == 1) return true
        if (state == 2 || state == 0) return false
        return NativeMetadataAccess.call(engine, "isPlaying") as? Boolean
    }
    private fun command(engine: Any, play: Boolean): Boolean {
        transitionUntil = 0
        transitionPause = false
        manualCommand = true
        return try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == (if (play) "play" else "pause") && it.parameterCount == 0
            } ?: return false
            method.invoke(engine)
            true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗: 播放控制失败 " + e.javaClass.simpleName)
            false
        } finally { manualCommand = false }
    }

    fun rememberPlayingController(controller: Any?) {
        if (controller != null) controllerRef = WeakReference(controller)
    }

    /** Observed even when the optional system-media feature is disabled. */
    fun onEngineCommand(engine: Any?, action: String, activity: Activity?) {
        if (!enabled || engine == null) return
        val current = session
        if (action == "play") {
            if (current == null) {
                // Play can be dispatched during the Splash-to-player handoff.
                // Verify the visible owner when docking, not at play dispatch.
                engineRef = WeakReference(engine)
                engineActivity = WeakReference(activity)
                LogUtil.info("应用内小窗: 原生引擎已观察 page=" +
                    (activity?.javaClass?.simpleName ?: "none") +
                    " state=" + playbackState(engine))
            } else if (current != null && current.engine !== engine) {
                // A different decoder actually playing supersedes the mini
                // window. Ignore mere preparation; never start a second one.
                main.postDelayed({
                    if (session === current && isPlaying(engine) == true) {
                        if (isPlaying(current.engine) == true) command(current.engine, false)
                        restore()
                        if (activity != null && isAlive(activity)) {
                            engineRef = WeakReference(engine)
                            engineActivity = WeakReference(activity)
                        }
                        LogUtil.incr("inAppMiniSuperseded")
                    }
                }, 250L)
            }
        } else if (current != null && current.engine === engine) {
            if (action == "pause") {
                if (SystemClock.uptimeMillis() <= transitionUntil && !manualCommand &&
                    !SystemMediaBridge.userTransportActive()) transitionPause = true
                else transitionPause = false
                main.post { updatePlayButton(current) }
            } else if (action == "stop" || action.startsWith("release")) {
                transitionUntil = 0
                transitionPause = false
                main.post {
                    if (session === current) restore()
                }
            }
        }
        if (action.startsWith("release") && engineRef.get() === engine) {
            engineRef.clear()
            engineActivity.clear()
        }
    }

    fun canDock(activity: Activity): Boolean {
        if (!enabled || !onMain() || !isAlive(activity) ||
            activity.javaClass.name !in setOf(MAIN, SERIES)) return false
        val confirmed = NativePlaybackSession.engine()
        if (confirmed != null && NativePlaybackSession.currentFor(activity) !== confirmed) return false
        val shared = NativePlaybackSession.currentFor(activity) ?: SystemMediaBridge.currentMiniEngine(activity)
        val local = engineRef.get()
        val owner = engineActivity.get()
        val localAllowed = owner === activity || owner == null ||
            (owner.packageName == activity.packageName && owner.taskId == activity.taskId &&
                (owner.isDestroyed || owner.isFinishing ||
                    owner.javaClass.name !in setOf(MAIN, SERIES)))
        val engine = shared?.takeIf { isPlaying(it) == true } ?:
            local?.takeIf { localAllowed && isPlaying(it) == true }
        LogUtil.info("应用内小窗: 启动校验 page=" + activity.javaClass.simpleName +
            " shared=" + (shared != null) + " local=" + (local != null) +
            " owner=" + (owner?.javaClass?.simpleName ?: "none") +
            " localState=" + playbackState(local) + " sharedState=" + playbackState(shared) +
            " ownerAllowed=$localAllowed accepted=" + (engine != null))
        if (engine == null) return false
        engineRef = WeakReference(engine)
        engineActivity = WeakReference(activity)
        return true
    }

    private fun armTransition() {
        if (session?.let { isPlaying(it.engine) } != true) return
        transitionUntil = SystemClock.uptimeMillis() + 1800L
        transitionPause = false
    }
    fun shouldKeepPlaying(controller: Any?): Boolean {
        val s = session ?: return false
        if (!enabled || !isAlive(s.activity) || manualCommand ||
            SystemMediaBridge.userTransportActive() ||
            SystemClock.uptimeMillis() > transitionUntil) return false
        if (controller == null) return false
        if (controller !== s.engine) {
            if (controller !== controllerRef.get()) return false
            val boundEngine = runCatching {
                controller.javaClass.methods.firstOrNull {
                    it.name == "getVideoEngine" && it.parameterCount == 0
                }?.invoke(controller)
            }.getOrNull()
            if (boundEngine !== s.engine) return false
        }
        LogUtil.incr("inAppMiniPauseIntercept")
        return true
    }

    fun resumeAfterTabSwitch() {
        val s = session ?: return
        // A single recovery for a recorded transition pause, never a
        // repeating play loop or resume after a deliberate manual pause.
        if (!enabled || !isAlive(s.activity) || !transitionPause ||
            SystemClock.uptimeMillis() > transitionUntil ||
            SystemMediaBridge.userTransportActive()) return
        transitionPause = false
        if (isPlaying(s.engine) == false) {
            command(s.engine, true)
            LogUtil.incr("inAppMiniResumeAttempt")
        }
        updatePlayButton(s)
    }

    fun hasWindow() = session != null
    fun activeFor(activity: Activity) = session?.display === activity
    fun ownerIs(activity: Activity) = session?.activity === activity

    fun hasVideoRenderer(root: View?, maxDepth: Int = 9): Boolean {
        if (root == null || maxDepth < 0) return false
        if (root is android.view.TextureView || root is android.view.SurfaceView) return true
        if (root.javaClass.name == "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") return true
        if (root is ViewGroup) for (i in 0 until root.childCount)
            if (hasVideoRenderer(root.getChildAt(i), maxDepth - 1)) return true
        return false
    }

    private fun content(a: Activity): FrameLayout? =
        a.findViewById<View>(android.R.id.content) as? FrameLayout

    fun dock(activity: Activity, videoHost: View?): Boolean {
        if (!canDock(activity)) return false
        if (session != null) return activeFor(activity)
        val engine = engineRef.get() ?: return false
        val video = videoHost ?: return false
        if (!video.isShown || !video.isAttachedToWindow ||
            video.width < dp(activity, 80f) || video.height < dp(activity, 100f) ||
            !hasVideoRenderer(video)) return false
        val parent = video.parent as? ViewGroup ?: return false
        val root = content(activity) ?: return false
        if (parent === root) return false
        val index = parent.indexOfChild(video)
        val original = video.layoutParams ?: return false
        if (index < 0) return false
        val overlay = FrameLayout(activity).apply {
            tag = FLOAT_TAG
            clipChildren = true
            clipToOutline = true
            background = shape(Color.BLACK, dp(activity, 16f).toFloat())
            elevation = dp(activity, 22f).toFloat()
            isClickable = true
        }
        val current = Session(activity, video, parent, index, original, overlay, engine)
        session = current
        armTransition()
        return try {
            parent.removeView(video)
            overlay.addView(video, FrameLayout.LayoutParams(-1, -1))
            addControls(current)
            if (!attach(current, activity)) { restore(); false }
            else {
                LogUtil.incr("inAppMiniDock")
                LogUtil.info("应用内小窗: 原生播放会话已绑定")
                true
            }
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗: 挂载失败 " + e.javaClass.simpleName)
            restore()
            false
        }
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private fun bounds(s: Session, root: FrameLayout, w: Int, h: Int): Bounds {
        val a = s.display ?: s.activity
        val margin = dp(a, 8f)
        val inset = if (android.os.Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            else null
        val l = margin + (inset?.left ?: 0)
        val t = margin + (inset?.top ?: 0)
        val r = (root.width - w - margin - (inset?.right ?: 0)).coerceAtLeast(l)
        val b = (root.height - h - margin - (inset?.bottom ?: 0)).coerceAtLeast(t)
        return Bounds(l, t, r, b)
    }
    private fun layout(s: Session) {
        val root = s.overlay.parent as? FrameLayout ?: return
        val a = s.display ?: return
        if (root.width <= 0 || root.height <= 0) return
        val w = dp(a, if (s.large) 176f else 124f).coerceAtMost((root.width - dp(a, 32f)).coerceAtLeast(1))
        val h = (w * 16f / 9f).toInt().coerceAtMost((root.height - dp(a, 48f)).coerceAtLeast(1))
        val b = bounds(s, root, w, h)
        s.overlay.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = b.left + ((b.right - b.left) * s.x).toInt()
            topMargin = b.top + ((b.bottom - b.top) * s.y).toInt()
        }
    }
    private fun attach(s: Session, activity: Activity): Boolean {
        if (!isAlive(s.activity) || !isAlive(activity) ||
            activity.taskId != s.activity.taskId || activity.packageName != s.activity.packageName) return false
        val root = content(activity) ?: return false
        if (s.overlay.parent === root) { s.display = activity; return true }
        return try {
            armTransition()
            (s.overlay.parent as? ViewGroup)?.removeView(s.overlay)
            s.display = activity
            root.addView(s.overlay, FrameLayout.LayoutParams(dp(activity, 124f), dp(activity, 220f),
                Gravity.BOTTOM or Gravity.RIGHT))
            root.post { if (session === s && s.display === activity) layout(s) }
            s.overlay.bringToFront()
            LogUtil.incr("inAppMiniPageAttach")
            LogUtil.info("应用内小窗: 跟随页面 " + activity.javaClass.simpleName)
            true
        } catch (e: Throwable) {
            LogUtil.warn("应用内小窗: 跨页面挂载失败 " + e.javaClass.simpleName)
            false
        }
    }

    fun beforePageChange(activity: Activity, intent: Intent?, host: () -> View?) {
        if (!enabled || !onMain() || intent == null || !isAlive(activity)) return
        val target = intent.component ?: runCatching {
            intent.resolveActivity(activity.packageManager)
        }.getOrNull() ?: return
        if (target.packageName != activity.packageName ||
            target.className in setOf(MAIN, SERIES) ||
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) return
        if (session == null && canDock(activity)) dock(activity, host())
        else if (activeFor(activity)) armTransition()
    }
    fun onActivityResumed(activity: Activity) {
        val s = session ?: return
        if (!enabled || !isAlive(s.activity)) { restore(); return }
        if (activity.packageName != s.activity.packageName || activity.taskId != s.activity.taskId) return
        if (s.returnRequested && activity === s.activity) {
            val home = activity.javaClass.name == MAIN
            restore()
            if (home) openHomeTab?.invoke(activity)
            return
        }
        if (activity !== s.activity && activity.javaClass.name in setOf(MAIN, SERIES)) {
            // A new video page owns its own playback. Never cover it with
            // the previous session while it is creating a new decoder.
            restore()
            return
        }
        if (!attach(s, activity)) {
            restore()
            return
        }
        main.postDelayed({ if (session === s) resumeAfterTabSwitch() }, 320L)
    }
    fun onActivityClosed(activity: Activity) {
        val s = session ?: return
        if (s.activity === activity) {
            // Native owner is going away: no forced retention or release suppression.
            session = null
            transitionUntil = 0
            transitionPause = false
            (s.overlay.parent as? ViewGroup)?.removeView(s.overlay)
            LogUtil.incr("inAppMiniOwnerDestroyed")
        } else if (s.display === activity) {
            (s.overlay.parent as? ViewGroup)?.removeView(s.overlay)
            s.display = null
        }
    }
    private fun button(a: Activity, label: String, description: String): TextView = TextView(a).apply {
        text = label; textSize = 20f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; contentDescription = description
        background = shape(Color.argb(180, 16, 17, 19), dp(a, 20f).toFloat())
        isClickable = true; isFocusable = true
    }
    private fun updatePlayButton(s: Session) {
        if (session !== s) return
        val playing = isPlaying(s.engine) == true
        s.playButton?.text = if (playing) "Ⅱ" else "▶"
        s.playButton?.contentDescription = if (playing) "暂停" else "播放"
    }
    private fun showControls(s: Session, visible: Boolean) {
        if (session !== s) return
        s.controls.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
        val generation = ++s.controlsGeneration
        if (visible) {
            updatePlayButton(s)
            main.postDelayed({
                if (session === s && generation == s.controlsGeneration)
                    showControls(s, false)
            }, 3000L)
        }
    }
    private fun addControls(s: Session) {
        val a = s.activity
        val overlay = s.overlay
        val gesture = View(a).apply { isClickable = true; contentDescription = "点击显示控制，拖动小窗" }
        overlay.addView(gesture, FrameLayout.LayoutParams(-1, -1))
        val expand = button(a, "⤢", "返回播放页")
        val close = button(a, "×", "关闭并暂停")
        val play = button(a, "Ⅱ", "暂停")
        s.playButton = play
        val size = dp(a, 34f)
        overlay.addView(expand, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
        overlay.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.RIGHT))
        overlay.addView(play, FrameLayout.LayoutParams(dp(a, 44f), dp(a, 44f), Gravity.CENTER))
        s.controls = listOf(expand, close, play)
        s.controls.forEach { it.visibility = View.GONE }
        expand.setOnClickListener { returnToVideo() }
        close.setOnClickListener { command(s.engine, false); restore() }
        play.setOnClickListener {
            command(s.engine, isPlaying(s.engine) != true)
            main.postDelayed({ updatePlayButton(s) }, 160L)
            showControls(s, true)
        }
        var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0
        var dragging = false; var lastTap = 0L
        val slop = ViewConfiguration.get(a).scaledTouchSlop
        gesture.setOnClickListener { showControls(s, s.controls.none { it.visibility == View.VISIBLE }) }
        gesture.setOnTouchListener { view, event ->
            val root = overlay.parent as? FrameLayout ?: return@setOnTouchListener false
            val params = overlay.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            val b = bounds(s, root, overlay.width, overlay.height)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    baseX = params.leftMargin; baseY = params.topMargin
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        params.leftMargin = (baseX + dx.toInt()).coerceIn(b.left, b.right)
                        params.topMargin = (baseY + dy.toInt()).coerceIn(b.top, b.bottom)
                        params.gravity = Gravity.TOP or Gravity.LEFT
                        overlay.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        s.x = if (params.leftMargin - b.left < (b.right - b.left) / 2) 0f else 1f
                        s.y = ((params.topMargin - b.top).toFloat() / (b.bottom - b.top).coerceAtLeast(1)).coerceIn(0f, 1f)
                        layout(s)
                        lastTap = 0L
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (lastTap != 0L && now - lastTap <= ViewConfiguration.getDoubleTapTimeout()) {
                            s.large = !s.large
                            layout(s)
                            lastTap = 0L
                        } else {
                            lastTap = now
                            view.performClick()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; layout(s); true }
                else -> true
            }
        }
    }

    fun restore(): Boolean {
        val old = session ?: return false
        session = null
        transitionUntil = 0
        transitionPause = false
        return try {
            (old.source.parent as? ViewGroup)?.removeView(old.source)
            if (isAlive(old.activity) && old.source.parent == null) {
                old.originalParent.addView(old.source,
                    old.originalIndex.coerceIn(0, old.originalParent.childCount), old.originalLayoutParams)
                old.source.requestLayout()
            }
            (old.overlay.parent as? ViewGroup)?.removeView(old.overlay)
            LogUtil.incr("inAppMiniRestore")
            true
        } catch (e: Throwable) {
            runCatching { (old.overlay.parent as? ViewGroup)?.removeView(old.overlay) }
            LogUtil.warn("应用内小窗: 恢复原始容器失败 " + e.javaClass.simpleName)
            false
        }
    }
    var openHomeTab: ((Activity) -> Unit)? = null
    fun returnToVideo() {
        val s = session ?: return
        val display = s.display ?: return
        if (display === s.activity) {
            restore()
            if (display.javaClass.name == MAIN) openHomeTab?.invoke(display)
            return
        }
        if (!isAlive(s.activity) || display.taskId != s.activity.taskId) return
        s.returnRequested = true
        try {
            display.startActivity(Intent(display, s.activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            main.postDelayed({ if (session === s) s.returnRequested = false }, 3000L)
        } catch (e: Throwable) {
            s.returnRequested = false
            LogUtil.warn("应用内小窗: 返回原页面失败 " + e.javaClass.simpleName)
        }
    }
}
