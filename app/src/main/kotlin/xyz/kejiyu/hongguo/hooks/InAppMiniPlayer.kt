package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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
import android.widget.Toast
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
        var minimizedSeries: Boolean = false,
        var controls: List<View> = emptyList(), var playButton: IconButton? = null,
        var controlsGeneration: Int = 0,
        var desiredPlaying: Boolean = true,
        var userPaused: Boolean = false,
        var transportPending: Boolean = false,
        var transportGeneration: Int = 0
    )
    @Volatile private var session: Session? = null
    @Volatile private var engineRef = WeakReference<Any>(null)
    @Volatile private var engineActivity = WeakReference<Activity>(null)
    @Volatile private var controllerRef = WeakReference<Any>(null)
    @Volatile private var transitionUntil = 0L
    @Volatile private var transitionPause = false
    @Volatile private var manualCommand = false
    private var mainPage = WeakReference<Activity>(null)
    private data class Handoff(val old: WeakReference<Any>, val deadline: Long)
    @Volatile private var pendingHandoff: Handoff? = null
    @Volatile private var returningSeriesUntil = 0L
    private const val SERIES_MINI_TAG = "XHONGGUO_SERIES_MINI_ENTRY"

    fun ownsTransportCommand(): Boolean = manualCommand

    // Only a previously docked mini window can require duplicate cleanup.
    // A page launch is NOT proof of decoder replacement: the first series
    // episode can reuse the feed engine. Never pre-pause or blacklist it.
    private fun rememberHandoff(old: Any, reason: String) {
        pendingHandoff = Handoff(WeakReference(old), SystemClock.uptimeMillis() + 5000L)
        LogUtil.info("应用内小窗: 等待原生播放器交接 reason=$reason")
    }

    private fun confirmHandoff(candidate: Any) {
        val handoff = pendingHandoff ?: return
        val old = handoff.old.get()
        if (old == null || old === candidate || SystemClock.uptimeMillis() > handoff.deadline) {
            if (pendingHandoff === handoff) pendingHandoff = null
            if (old === candidate) LogUtil.info("应用内小窗: 原生复用同一引擎，不暂停")
            return
        }
        for (delay in longArrayOf(300L, 850L, 1600L)) {
            main.postDelayed({
                if (!enabled || pendingHandoff !== handoff ||
                    SystemClock.uptimeMillis() > handoff.deadline ||
                    engineRef.get() !== candidate || session?.engine === old) return@postDelayed
                if (isPlaying(candidate) == true && isPlaying(old) == true) {
                    pendingHandoff = null
                    val sent = command(old, false)
                    LogUtil.info("应用内小窗: 不同引擎已播放，暂停旧小窗 pauseSent=$sent")
                    LogUtil.incr("inAppMiniHandoff")
                }
            }, delay)
        }
    }

    fun beforeEnginePlay(candidate: Any?, activity: Activity?): Boolean {
        if (!enabled || candidate == null || manualCommand) return false
        val s = session
        val now = SystemClock.uptimeMillis()
        if (s != null && candidate === s.engine) {
            if (s.userPaused && !SystemMediaBridge.userTransportActive()) {
                LogUtil.incr("inAppMiniUserPausePreserved")
                return true
            }
            return false
        }
        // Native Home may autoplay immediately as a minimized series
        // returns to the existing Main Activity. Don't replace the series
        // with that startup playback. Never guard indefinitely.
        if (s?.minimizedSeries == true && activity?.javaClass?.name == MAIN &&
            now < returningSeriesUntil) {
            LogUtil.incr("inAppMiniReturnAutoplayIgnored")
            return true
        }
        if (s != null) {
            // Allow the official play call first. Only clean up a genuinely
            // different old mini engine after the new one reports playing.
            if (onMain()) {
                restore()
                rememberHandoff(s.engine, "new-native-play")
            } else {
                rememberHandoff(s.engine, "new-native-play-worker")
                main.post { if (session === s) restore() }
            }
        }
        // Without a docked mini session, leave native feed/series ownership
        // to the host. Activity transitions do not identify decoder ownership.
        return false
    }

    private fun endForNewPage(reason: String) {
        val s = session ?: return
        restore()
        rememberHandoff(s.engine, reason)
    }

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
    private fun command(engine: Any, play: Boolean, startOnly: Boolean = false): Boolean {
        transitionUntil = 0
        transitionPause = false
        manualCommand = true
        return try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == (if (play && startOnly) "start" else if (play) "play" else "pause") &&
                    it.parameterCount == 0 && it.returnType == Void.TYPE
            } ?: return false
            method.invoke(engine)
            LogUtil.info("应用内小窗: 控制指令 method=" + method.name +
                " nativeState=" + playbackState(engine))
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
        if (action == "play" || action == "start") {
            if (current != null && current.engine === engine) {
                if (!manualCommand && SystemMediaBridge.userTransportActive()) {
                    current.userPaused = false
                    current.desiredPlaying = true
                }
                main.post { updatePlayButton(current) }
            }
            if (current == null) {
                // Play can be dispatched during the Splash-to-player handoff.
                // Verify the visible owner when docking, not at play dispatch.
                engineRef = WeakReference(engine)
                engineActivity = WeakReference(activity)
                LogUtil.info("应用内小窗: 原生引擎已观察 page=" +
                    (activity?.javaClass?.simpleName ?: "none") +
                    " state=" + playbackState(engine))
            } else if (current.engine !== engine) {
                // beforeEnginePlay has already retired the old engine;
                // the UI restore can still be queued from a worker thread.
                engineRef = WeakReference(engine)
                engineActivity = WeakReference(activity)
            }
            if (!manualCommand) confirmHandoff(engine)
        } else if (current != null && current.engine === engine) {
            if (action == "pause") {
                if (SystemClock.uptimeMillis() <= transitionUntil && !manualCommand &&
                    !SystemMediaBridge.userTransportActive()) transitionPause = true
                else {
                    transitionPause = false
                    if (!manualCommand && SystemMediaBridge.userTransportActive()) {
                        current.userPaused = true
                        current.desiredPlaying = false
                    }
                }
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
        val shared = SystemMediaBridge.currentMiniEngine(activity)
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
        if (session?.let { !it.userPaused && isPlaying(it.engine) == true } != true) return
        transitionUntil = SystemClock.uptimeMillis() + 1800L
        transitionPause = false
    }
    fun shouldKeepPlaying(controller: Any?): Boolean {
        val s = session ?: return false
        if (!enabled || !isAlive(s.activity) || s.userPaused || manualCommand ||
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
        if (!enabled || !isAlive(s.activity) || s.userPaused || !transitionPause ||
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
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) return
        if (target.className in setOf(MAIN, SERIES)) {
            val s = session
            if (s != null && (s.returnRequested ||
                s.minimizedSeries && target.className == MAIN &&
                SystemClock.uptimeMillis() < returningSeriesUntil)) return
            if (s != null) endForNewPage("launch-player-page")
            // No mini session: do not touch normal feed-to-series playback.
            return
        }
        // The full-series Activity has an independent Surface lifecycle.
        // Do not attempt cross-Activity reparenting for series navigation.
        if (activity.javaClass.name == SERIES) return
        if (session == null && canDock(activity)) dock(activity, host())
        else if (activeFor(activity)) armTransition()
    }
    fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name == MAIN) mainPage = WeakReference(activity)
        val s = session ?: return
        if (!enabled || !isAlive(s.activity)) { restore(); return }
        if (activity.packageName != s.activity.packageName || activity.taskId != s.activity.taskId) return
        if (activity === s.activity && (s.returnRequested ||
                s.minimizedSeries && s.display !== activity)) {
            val home = activity.javaClass.name == MAIN
            restore()
            if (home) openHomeTab?.invoke(activity)
            return
        }
        if (activity !== s.activity && activity.javaClass.name in setOf(MAIN, SERIES) &&
            !(s.minimizedSeries && activity === mainPage.get())) {
            endForNewPage("resume-player-page")
            return
        }
        if (s.minimizedSeries && activity.javaClass.name == MAIN)
            returningSeriesUntil = SystemClock.uptimeMillis() + 2200L
        if (!attach(s, activity)) {
            restore()
            return
        }
        main.postDelayed({ if (session === s) resumeAfterTabSwitch() }, 320L)
    }
    fun onActivityClosed(activity: Activity) {
        SeriesPipPresentation.onActivityDestroyed(activity)
        if (mainPage.get() === activity) mainPage.clear()
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
    /** Presentation only, like YHongguo: never move a live full-series
     * Surface into a different Activity or start/reorder Main to minimize. */
    fun minimizeSeries(activity: Activity, host: () -> View?): Boolean {
        if (!enabled || !onMain() || activity.javaClass.name != SERIES ||
            !SeriesPipPresentation.supported(activity)) return false
        if (activity.isInPictureInPictureMode) return false
        val shared = SystemMediaBridge.currentMiniEngine(activity)
        val local = engineRef.get()?.takeIf { engineActivity.get() === activity }
        val engine = shared ?: local ?: return false
        if (NativeMetadataAccess.call(engine, "isReleased") == true) return false
        val state = playbackState(engine)
        if (state !in setOf(1, 2) && isPlaying(engine) != true) return false
        val existing = session
        if (existing != null) {
            // Do not enter a second presentation while an old mini still
            // owns its renderer. For the same owner restore the full view.
            if (existing.activity !== activity) endForNewPage("series-pip-entry")
            else restore()
        }
        val accepted = SeriesPipPresentation.enter(activity, engine, host(), isPlaying(engine) == true)
        if (!accepted) LogUtil.warn("全集小窗: 保留原播放页，未进入系统画中画")
        return accepted
    }

    fun installSeriesEntry(activity: Activity, host: () -> View?) {
        if (activity.javaClass.name != SERIES || !isAlive(activity)) return
        val root = content(activity) ?: return
        val old = root.findViewWithTag<View>(SERIES_MINI_TAG)
        val available = enabled && SeriesPipPresentation.supported(activity) &&
            !activity.isInPictureInPictureMode
        if (!available) {
            old?.let { root.removeView(it) }
            return
        }
        if (old != null) { old.bringToFront(); return }
        val entry = button(activity, Glyph.MINI, "缩小到画中画").apply {
            tag = SERIES_MINI_TAG
            setOnClickListener {
                if (!minimizeSeries(activity, host))
                    android.widget.Toast.makeText(activity, "未能进入画中画，请检查系统权限与播放状态",
                        android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(entry, FrameLayout.LayoutParams(dp(activity, 38f), dp(activity, 38f),
            Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(activity, 14f)
            topMargin = dp(activity, 78f)
        })
    }

    private enum class Glyph { PLAY, PAUSE, EXPAND, CLOSE, MINI }

    /** Resolution-independent Material-style glyphs, no Unicode/font dependency. */
    private class IconButton(a: Activity, initial: Glyph, description: String) : View(a) {
        var glyph: Glyph = initial
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        init {
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xB8202020.toInt())
                }, null)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height) * 0.54f
            val save = canvas.save()
            canvas.translate((width - side) / 2f, (height - side) / 2f)
            canvas.scale(side / 24f, side / 24f)
            paint.style = Paint.Style.FILL
            when (glyph) {
                Glyph.PLAY -> {
                    val triangle = Path().apply {
                        moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close()
                    }
                    canvas.drawPath(triangle, paint)
                }
                Glyph.PAUSE -> {
                    canvas.drawRoundRect(RectF(5.5f, 4.5f, 10f, 19.5f), 1f, 1f, paint)
                    canvas.drawRoundRect(RectF(14f, 4.5f, 18.5f, 19.5f), 1f, 1f, paint)
                }
                Glyph.CLOSE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f
                    canvas.drawLine(6f, 6f, 18f, 18f, paint)
                    canvas.drawLine(18f, 6f, 6f, 18f, paint)
                }
                Glyph.EXPAND -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    val corners = Path().apply {
                        moveTo(4f, 10f); lineTo(4f, 4f); lineTo(10f, 4f)
                        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
                        moveTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f)
                        moveTo(10f, 20f); lineTo(4f, 20f); lineTo(4f, 14f)
                    }
                    canvas.drawPath(corners, paint)
                }
                Glyph.MINI -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.8f
                    canvas.drawRoundRect(RectF(2.5f, 4.5f, 21.5f, 19.5f), 2f, 2f, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(RectF(12f, 11f, 19f, 17f), 1f, 1f, paint)
                }
            }
            canvas.restoreToCount(save)
        }
    }
    private fun button(a: Activity, glyph: Glyph, description: String) =
        IconButton(a, glyph, description)

    private fun updatePlayButton(s: Session) {
        if (session !== s) return
        val playing = if (s.transportPending || s.userPaused) s.desiredPlaying
            else isPlaying(s.engine) ?: s.desiredPlaying
        s.playButton?.glyph = if (playing) Glyph.PAUSE else Glyph.PLAY
        s.playButton?.contentDescription = if (playing) "暂停" else "播放"
    }

    /**
     * A pause command can complete before getPlaybackState changes. Keep
     * user intent independent of that stale snapshot; never toggle the
     * opposite command because of an async state read. Poll for confirmation
     * only while this exact session and generation still own the decoder.
     */
    private fun requestMiniTransport(s: Session, play: Boolean) {
        if (session !== s || !enabled) return
        val generation = ++s.transportGeneration
        s.desiredPlaying = play
        s.userPaused = !play
        s.transportPending = true
        transitionUntil = 0
        transitionPause = false
        updatePlayButton(s)
        val sent = command(s.engine, play, startOnly = play)
        if (!sent && play) command(s.engine, true)
        val startedAt = SystemClock.uptimeMillis()
        var triedPlayFallback = !sent
        fun confirm() {
            if (session !== s || s.transportGeneration != generation) return
            val actual = isPlaying(s.engine)
            val elapsed = SystemClock.uptimeMillis() - startedAt
            if (actual == play && elapsed >= 250L) {
                s.transportPending = false
                updatePlayButton(s)
                LogUtil.info("应用内小窗: 控制已确认 play=$play state=" + playbackState(s.engine))
                LogUtil.incr("inAppMiniTransportConfirmed")
                return
            }
            if (play && !triedPlayFallback && actual == false && elapsed >= 850L &&
                NativeMetadataAccess.call(s.engine, "isReleased") != true) {
                triedPlayFallback = true
                command(s.engine, true)
                LogUtil.incr("inAppMiniResumeFallback")
            }
            if (elapsed >= 2800L) {
                s.transportPending = false
                updatePlayButton(s)
                LogUtil.warn("应用内小窗: 控制未确认 play=$play state=" + playbackState(s.engine))
                LogUtil.incr("inAppMiniTransportUnconfirmed")
                // Leave the button in the native observed state, or offer
                // another explicit play attempt if the engine is paused.
                if (play && actual != true) Toast.makeText(s.display ?: s.activity,
                    "播放尚未恢复，请再次点击播放", Toast.LENGTH_SHORT).show()
                return
            }
            main.postDelayed({ confirm() }, 180L)
        }
        main.postDelayed({ confirm() }, 250L)
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
        val expand = button(a, Glyph.EXPAND, "返回播放页")
        val close = button(a, Glyph.CLOSE, "关闭并暂停")
        val play = button(a, Glyph.PAUSE, "暂停")
        s.playButton = play
        val size = dp(a, 34f)
        overlay.addView(expand, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(a, 6f); topMargin = dp(a, 6f)
        })
        overlay.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(a, 6f); topMargin = dp(a, 6f)
        })
        overlay.addView(play, FrameLayout.LayoutParams(dp(a, 46f), dp(a, 46f), Gravity.CENTER))
        s.controls = listOf(expand, close, play)
        s.controls.forEach { it.visibility = View.GONE }
        expand.setOnClickListener { returnToVideo() }
        close.setOnClickListener {
            ++s.transportGeneration
            s.userPaused = true
            s.desiredPlaying = false
            command(s.engine, false)
            restore()
        }
        play.setOnClickListener {
            val currentlyPlaying = if (s.transportPending || s.userPaused) s.desiredPlaying
                else isPlaying(s.engine) ?: s.desiredPlaying
            requestMiniTransport(s, !currentlyPlaying)
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
        returningSeriesUntil = 0L
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
