package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * System media controls for the EXISTING Hongguo video player.
 *
 * The session and media notification run inside com.phoenix.read, so Android
 * sees the original app as the playback owner. No extra playback source,
 * screen overlay, media decoder, cross-app service or target manifest edits.
 *
 * The host app's real "listen mode" mediaPlayback FGS is used by its own
 * audio player. This adapter does not pretend to start that mode or that FGS:
 * for ordinary video the native player may still release its surface or engine
 * when the app is backgrounded. Logs distinguish these cases.
 */
internal object SystemMediaBridge {
    @Volatile var enabled: Boolean = false
        private set

    private const val CHANNEL = "xhongguo_system_media"
    private const val NOTIFICATION_ID = 790112
    private const val TOGGLE_ACTION = "xyz.kejiyu.hongguo.SYSTEM_MEDIA_TOGGLE"
    private const val BACKGROUND_PAUSE_GUARD_MS = 6500L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var notificationManager: NotificationManager? = null
    private var session: MediaSession? = null
    private var receiver: BroadcastReceiver? = null
    private var controller = WeakReference<Any>(null)
    private var layer = WeakReference<Any>(null)
    private var videoContext = WeakReference<Any>(null)
    private var videoEngine = WeakReference<Any>(null)
    private var foregroundActivity = WeakReference<Activity>(null)
    private var lastDiscoveryAt = 0L
    private var lastNativeEngineAt = 0L
    // The native VideoShop API (confirmed in 7.3.5.32) exposes
    // VideoContext.play/pause/isPlaying and LayerHostMediaLayout.setCanPlayBackground.
    private var lastNativeEventAt = 0L
    private var backgroundFlagApplied = false
    private var originalBackgroundFlag: Boolean? = null
    private var originalBackgroundHost = WeakReference<Any>(null)
    @Volatile private var settingBackgroundInternally = false

    fun rememberOriginalBackgroundSetting(candidate: Any?, value: Boolean?) {
        if (candidate == null || value == null || settingBackgroundInternally) return
        if (originalBackgroundHost.get() !== candidate) {
            originalBackgroundHost = WeakReference(candidate)
        }
        originalBackgroundFlag = value
    }

    @Volatile private var knownPlaying = false
    @Volatile private var requestedBackgroundPlayback = false
    @Volatile private var leaveAt = 0L
    @Volatile private var userCommandInProgress = false
    private var resumeAttempts = 0
    private var reportedCard = false
    private var lastPauseAt = 0L

    fun configure(ctx: Context, requested: Boolean) {
        val validTarget = ctx.packageName == TargetNames.CN_PACKAGE ||
            ctx.packageName == TargetNames.OVERSEA_PACKAGE
        val mainProcess = try { Application.getProcessName() == ctx.packageName }
            catch (_: Throwable) { true }
        val next = requested && validTarget && mainProcess
        if (!next) {
            enabled = false
            stop()
            return
        }
        enabled = true
        appContext = ctx.applicationContext
        // Do not advertise an active MediaSession before real playback is observed.
        // Home/splash startup is not evidence of a playing video.
        LogUtil.info("系统媒体: 启用原播放器探测; process=" +
            (try { Application.getProcessName() } catch (_: Throwable) { "unknown" }))
    }

    fun rememberController(candidate: Any?) {
        if (candidate?.javaClass?.name ==
            "com.ss.android.videoshop.controller.VideoController") {
            controller = WeakReference(candidate)
        }
    }

    fun rememberLayer(candidate: Any?) {
        if (candidate?.javaClass?.name ==
            "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") {
            layer = WeakReference(candidate)
        }
    }

    fun rememberVideoContext(candidate: Any?) {
        if (candidate?.javaClass?.name ==
            "com.ss.android.videoshop.context.VideoContext") {
            videoContext = WeakReference(candidate)
        }
    }

    /**
     * 7.3.5.32 can use TTVideoEngine without triggering VideoContext's
     * onVideoPlay. Capture the real lower-level play/pause invocation, but
     * never confuse a hook *installation* with a runtime playback event.
     */
    fun onNativeEngineCommand(candidate: Any?, action: String) {
        if (!enabled || candidate == null) return
        if (candidate.javaClass.name != "com.ss.ttvideoengine.TTVideoEngine") return
        if (action == "play") {
            videoEngine = WeakReference(candidate)
            lastNativeEngineAt = SystemClock.uptimeMillis()
        } else if (videoEngine.get() !== candidate) {
            // Preloading / old video engines may be torn down in parallel.
            // Do not pause the active media session for a different instance.
            LogUtil.info("系统媒体: 忽略非当前 TTVideoEngine." + action)
            return
        }
        val playbackState = try {
            candidate.javaClass.getMethod("getPlaybackState").invoke(candidate)
        } catch (_: Throwable) { null }
        LogUtil.info("系统媒体: TTVideoEngine.$action invoked; state=" +
            (playbackState?.toString() ?: "?"))
        LogUtil.incr("systemMediaNativeEngineCommand")
        if (action == "play") {
            onNativePlayback(true, "TTVideoEngine#play", candidate)
        } else if (action == "pause" || action == "stop") {
            onNativePlayback(false, "TTVideoEngine#$action", candidate)
        } else if (action.startsWith("release") && videoEngine.get() === candidate) {
            videoEngine.clear()
            onNativePlayback(false, "TTVideoEngine#$action", null)
        }
    }

    /**
     * A VideoContext's lifecycle hooks fire in this app but its onVideoPlay
     * callbacks do not. Query the *existing* context from the active Activity,
     * without creating an independent decoder or invoking play().
     */
    fun discoverPlayingVideo(activity: Activity?, reason: String) {
        if (!enabled || activity == null) return
        if (activity.packageName != appContext?.packageName) return
        val now = SystemClock.uptimeMillis()
        if (reason == "periodic" && now - lastDiscoveryAt < 900L) return
        lastDiscoveryAt = now
        val candidate = try {
            val c = Class.forName("com.ss.android.videoshop.context.VideoContext",
                false, activity.classLoader)
            val resumed = try {
                c.getMethod("getResumedVideoContext").invoke(null)
            } catch (_: Throwable) { null }
            resumed ?: c.getMethod("getVideoContext", Context::class.java)
                .invoke(null, activity)
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 取得原生 VideoContext 失败: " +
                e.javaClass.simpleName + ": " + e.message)
            null
        }
        if (candidate != null) {
            rememberVideoContext(candidate)
            val playing = nativeIsPlaying(candidate)
            val boundLayer = try {
                candidate.javaClass.getMethod("getLayerHostMediaLayout").invoke(candidate)
            } catch (_: Throwable) { null }
            if (boundLayer != null) rememberLayer(boundLayer)
            LogUtil.info("系统媒体: 播放器快照 $reason context=" +
                candidate.javaClass.simpleName + " isPlaying=" +
                (playing?.toString() ?: "unknown") + " layer=" +
                (boundLayer?.javaClass?.simpleName ?: "null") +
                " nativeEngineRecent=" + (now - lastNativeEngineAt < 30_000L))
            LogUtil.incr("systemMediaContextProbe")
            if (playing == true) onNativePlayback(true, "VideoContext#probe:$reason",
                candidate)
        } else {
            LogUtil.info("系统媒体: 播放器快照 $reason VideoContext=null" +
                " engine=" + (videoEngine.get()?.javaClass?.simpleName ?: "null"))
            LogUtil.incr("systemMediaContextMissing")
        }
    }

    fun onActivityPaused(activity: Activity?) {
        if (!enabled || activity == null) return
        if (activity.packageName != appContext?.packageName) return
        discoverPlayingVideo(activity, "beforeActivityPause")
        val name = activity.javaClass.simpleName
        LogUtil.info("系统媒体: Activity.onPause $name knownPlaying=$knownPlaying")
        handler.postDelayed({
            if (!enabled || foregroundActivity.get() !== activity) return@postDelayed
            val state = ActivityManager.RunningAppProcessInfo()
            try { ActivityManager.getMyMemoryState(state) } catch (_: Throwable) {
                return@postDelayed
            }
            val isBackground = state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            LogUtil.info("系统媒体: Activity 暂停后进程 importance=" +
                state.importance + " background=$isBackground activity=$name")
            if (isBackground) onBackgroundTransition(activity, "Activity.onPause")
        }, 500L)
    }

    fun onActivityStopped(activity: Activity?) {
        if (!enabled || activity == null) return
        if (activity.packageName != appContext?.packageName) return
        LogUtil.info("系统媒体: Activity.onStop " + activity.javaClass.simpleName)
        discoverPlayingVideo(activity, "beforeActivityStop")
    }

    private fun onBackgroundTransition(activity: Activity, reason: String) {
        if (!enabled) return
        val native = nativeIsPlaying(activeNativePlayer())
        if (!knownPlaying && native != true) {
            LogUtil.info("系统媒体: $reason 未检测到真实视频播放，不发布虚假媒体卡片")
            return
        }
        requestedBackgroundPlayback = true
        leaveAt = SystemClock.uptimeMillis()
        resumeAttempts = 0
        setNativeBackgroundAllowance(true)
        updateMediaNotification(true)
        LogUtil.info("系统媒体: $reason 后台媒体已激活，native=" +
            (native?.toString() ?: "?"))
        LogUtil.incr("systemMediaEnterBackground")
    }

    /** Called by the exact public VideoShop callbacks, not by obfuscated holder IDs. */
    fun onNativeVideoEvent(candidate: Any?, event: String, expectedPlaying: Boolean?) {
        if (!enabled || candidate == null) return
        rememberVideoContext(candidate)
        rememberLayer(candidate)
        rememberController(candidate)
        val playing = nativeIsPlaying(candidate)
        val effective = playing ?: expectedPlaying
        LogUtil.info("系统媒体: VideoShop事件 $event class=" +
            candidate.javaClass.simpleName + " isPlaying=" + (playing?.toString() ?: "?"))
        LogUtil.incr("systemMediaNativeEvent")
        if (effective != null) onNativePlayback(effective, event, candidate)
    }

    fun onNativeVideoStart(candidate: Any?, reason: String) {
        if (!enabled || candidate == null) return
        rememberVideoContext(candidate)
        rememberController(candidate)
        rememberLayer(candidate)
        // play() may be called before the engine has rendered its first frame.
        // Probe later and announce PLAYING only when isPlaying() confirms it.
        for (delay in longArrayOf(160L, 650L)) {
            handler.postDelayed({
                if (!enabled) return@postDelayed
                val actual = nativeIsPlaying(candidate)
                LogUtil.info("系统媒体: 原生播放探测 $reason delay=$delay" +
                    " isPlaying=" + (actual?.toString() ?: "unknown"))
                if (actual == true) onNativePlayback(true, reason, candidate)
            }, delay)
        }
    }

    private fun nativeIsPlaying(candidate: Any?): Boolean? {
        if (candidate == null) return null
        val obj = if (candidate.javaClass.name ==
            "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") {
            try {
                val ctx = appContext ?: return null
                candidate.javaClass.getMethod("getVideoContext", Context::class.java)
                    .invoke(candidate, ctx) ?: candidate
            } catch (_: Throwable) { candidate }
        } else candidate
        return try {
            (obj.javaClass.methods.firstOrNull {
                it.name == "isPlaying" && it.parameterCount == 0
            }?.invoke(obj)) as? Boolean
        } catch (_: Throwable) { null }
    }

    private fun activeNativePlayer(): Any? {
        for (c in listOf(videoContext.get(), controller.get(), layer.get())) {
            if (nativeIsPlaying(c) == true) return c
        }
        return videoContext.get() ?: controller.get() ?: layer.get() ?: videoEngine.get()
    }

    private fun setNativeBackgroundAllowance(allow: Boolean) {
        val host = layer.get() ?: try {
            videoContext.get()?.javaClass?.getMethod("getLayerHostMediaLayout")
                ?.invoke(videoContext.get())
        } catch (_: Throwable) { null } ?: return
        try {
            val method = host.javaClass.getMethod("setCanPlayBackground",
                Boolean::class.javaPrimitiveType)
            settingBackgroundInternally = true
            try {
                method.invoke(host, allow)
            } finally { settingBackgroundInternally = false }
            backgroundFlagApplied = allow
            LogUtil.info("系统媒体: 原生 setCanPlayBackground($allow) 已调用" +
                " original=" + (originalBackgroundFlag?.toString() ?: "not observed"))
            LogUtil.incr("systemMediaNativeBackgroundFlag")
        } catch (e: Throwable) {
            settingBackgroundInternally = false
            LogUtil.warn("系统媒体: 原生后台播放开关不可用: " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun onNativePlayback(playing: Boolean, source: String, candidate: Any? = null) {
        if (!enabled) return
        if (candidate != null) {
            rememberVideoContext(candidate)
            rememberController(candidate)
            rememberLayer(candidate)
        }
        val stateChanged = knownPlaying != playing
        knownPlaying = playing
        if (playing) {
            resumeAttempts = 0
            requestedBackgroundPlayback = requestedBackgroundPlayback &&
                SystemClock.uptimeMillis() - leaveAt < 30_000L
            if (stateChanged || !reportedCard) updateMediaNotification(true)
        } else {
            lastPauseAt = SystemClock.uptimeMillis()
            if (requestedBackgroundPlayback && resumeAttempts < 2 &&
                lastPauseAt - leaveAt < 4000L && !userCommandInProgress) {
                resumeAttempts++
                LogUtil.warn("系统媒体: 检测到切后台暂停; native=$source; 尝试恢复 #$resumeAttempts")
                handler.postDelayed({
                    if (enabled && requestedBackgroundPlayback && !knownPlaying) {
                        requestTransport(true, "background-auto-resume")
                    }
                }, if (resumeAttempts == 1) 180L else 650L)
            }
            if (stateChanged || reportedCard) updateMediaNotification(false)
        }
        if (stateChanged) LogUtil.info("系统媒体播放状态=" +
            (if (playing) "playing" else "paused") + " source=" + source +
            " background=" + requestedBackgroundPlayback)
    }

    fun onUserLeave(activity: Activity?) {
        if (!enabled || activity == null) return
        if (!activity.javaClass.name.startsWith("com.dragon.read.")) return
        discoverPlayingVideo(activity, "onUserLeaveHint")
        val nativePlaying = nativeIsPlaying(activeNativePlayer())
        LogUtil.info("系统媒体: onUserLeaveHint activity=" +
            activity.javaClass.simpleName + " knownPlaying=$knownPlaying" +
            " nativePlaying=" + (nativePlaying?.toString() ?: "?"))
        if (!knownPlaying && nativePlaying != true) return
        onBackgroundTransition(activity, "onUserLeaveHint")
    }

    fun onActivityResumed(activity: Activity?) {
        if (!enabled || activity == null ||
            !activity.javaClass.name.startsWith("com.dragon.read.")) return
        foregroundActivity = WeakReference(activity)
        // A running TTVideoEngine/VideoContext can start before the Activity
        // reaches onResume; inspect again after its UI and video surface attach.
        for (delay in longArrayOf(500L, 1700L, 3800L)) {
            handler.postDelayed({
                if (enabled && foregroundActivity.get() === activity &&
                    !activity.isFinishing) discoverPlayingVideo(activity, "periodic")
            }, delay)
        }
        if (requestedBackgroundPlayback) {
            LogUtil.info("系统媒体: 返回红果，结束后台暂停保护")
            requestedBackgroundPlayback = false
            leaveAt = 0L
            resumeAttempts = 0
            if (backgroundFlagApplied && originalBackgroundFlag != null)
                setNativeBackgroundAllowance(originalBackgroundFlag == true)
            backgroundFlagApplied = false
        }
    }

    /**
     * Intercept only a *no-argument native pause* on the known video controller
     * during the few seconds after the user pressed Home. Explicit media-card
     * pause is never blocked, nor is regular foreground/user pause.
     */
    fun shouldSuppressLifecyclePause(candidate: Any?): Boolean {
        if (!enabled || candidate == null || userCommandInProgress ||
            !requestedBackgroundPlayback || !knownPlaying) return false
        val now = SystemClock.uptimeMillis()
        if (now - leaveAt !in 0L..BACKGROUND_PAUSE_GUARD_MS) return false
        val active = controller.get()
        val context = videoContext.get()
        if (candidate !== active && candidate !== context) return false
        LogUtil.info("系统媒体: 忽略后台切换引发的 " +
            candidate.javaClass.simpleName + ".pause")
        LogUtil.incr("systemMediaLifecyclePauseGuard")
        return true
    }

    fun onActivityDestroyed(activity: Activity?) {
        if (activity == null || !enabled) return
        if (activity.isFinishing && requestedBackgroundPlayback &&
            activity.javaClass.name.contains("ShortSeries")) {
            requestedBackgroundPlayback = false
            LogUtil.warn("系统媒体: 全集 Activity 已销毁，原播放器可能已释放")
        }
    }

    private fun ensureSession() {
        if (!enabled || session != null) return
        val ctx = appContext ?: return
        try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            notificationManager = manager
            LogUtil.info("系统媒体: 红果通知权限=" +
                (if (manager.areNotificationsEnabled()) "已允许" else "未允许") +
                " SDK=" + Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "红果后台媒体播放",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        description = "系统媒体播放控制"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            val media = MediaSession(ctx, "XhongguoVideo")
            media.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            media.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    handler.post { requestTransport(true, "system-control") }
                }
                override fun onPause() {
                    handler.post { requestTransport(false, "system-control") }
                }
                override fun onStop() {
                    handler.post { requestTransport(false, "system-stop") }
                }
            }, handler)
            launchIntent(ctx)?.let { intent ->
                media.setSessionActivity(
                    PendingIntent.getActivity(ctx, 790113, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            media.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "红果短剧")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "正在播放的视频")
                .build())
            media.setActive(true)
            session = media
            installActionReceiver(ctx)
            updateSessionState(knownPlaying)
            LogUtil.info("系统媒体: Android MediaSession 已初始化")
            LogUtil.incr("systemMediaSessionCreated")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 创建 MediaSession 失败: " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun launchIntent(ctx: Context): Intent? =
        try {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                // Return to the existing playback task; CLEAR_TOP could
                // destroy the active full-series Activity and stop the video.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("xhongguo_return_to_video", true)
            }
        } catch (_: Throwable) { null }

    private fun installActionReceiver(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TOGGLE_ACTION) {
                    requestTransport(!knownPlaying, "notification-action")
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, IntentFilter(TOGGLE_ACTION),
                    Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(r, IntentFilter(TOGGLE_ACTION))
            }
            receiver = r
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 注册通知按键失败 " + e.message)
        }
    }

    private fun requestTransport(play: Boolean, reason: String) {
        if (!enabled) return
        val latestEngine = videoEngine.get()
        val targets = if (latestEngine != null &&
            SystemClock.uptimeMillis() - lastNativeEngineAt < 180_000L) {
            listOfNotNull(latestEngine, videoContext.get(), controller.get(), layer.get())
                .distinct()
        } else listOfNotNull(videoContext.get(), controller.get(),
            layer.get(), latestEngine).distinct()
        LogUtil.info("系统媒体: 控制请求 reason=$reason play=$play targets=" +
            targets.joinToString { it.javaClass.simpleName })
        val methodNames = if (play) {
            listOf("resume", "resumeVideo", "play", "start", "startVideo")
        } else {
            listOf("pause", "pauseVideo", "pausePlayback")
        }
        userCommandInProgress = true
        try {
            for (target in targets) {
                for (name in methodNames) {
                    try {
                        val method = target.javaClass.methods.firstOrNull {
                            it.name == name && it.parameterCount == 0
                        } ?: target.javaClass.declaredMethods.firstOrNull {
                            it.name == name && it.parameterCount == 0
                        } ?: continue
                        method.isAccessible = true
                        method.invoke(target)
                        // A successful reflective invocation means command sent, not
                        // necessarily that the native engine actually changed state.
                        // Native onVideoPlay/onVideoPause and isPlaying will confirm.
                        if (!play) requestedBackgroundPlayback = false
                        val observed = nativeIsPlaying(target)
                        if (observed != null) {
                            knownPlaying = observed
                            updateMediaNotification(observed)
                        }
                        handler.postDelayed({
                            val state = nativeIsPlaying(target)
                            if (state != null) onNativePlayback(state,
                                "transport-confirm:$reason", target)
                        }, 280L)
                        LogUtil.info("系统媒体: 原播放器控制成功 $reason " +
                            target.javaClass.simpleName + "." + name)
                        LogUtil.incr("systemMediaControl")
                        return
                    } catch (_: Throwable) {}
                }
            }
            LogUtil.warn("系统媒体: 没有可用的原播放器 " +
                (if (play) "play/resume" else "pause") + " 方法; $reason")
            LogUtil.incr("systemMediaControlUnavailable")
        } finally {
            userCommandInProgress = false
        }
    }

    private fun updateSessionState(playing: Boolean) {
        val media = session ?: return
        try {
            val state = PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                ).build()
            media.setPlaybackState(state)
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: PlaybackState 更新失败: " + e.message)
        }
    }

    private fun updateMediaNotification(playing: Boolean) {
        if (!enabled) return
        ensureSession()
        val ctx = appContext ?: return
        val media = session ?: return
        updateSessionState(playing)
        try {
            val action = Intent(TOGGLE_ACTION).setPackage(ctx.packageName)
            val toggleIntent = PendingIntent.getBroadcast(ctx, 790114, action,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val icon = if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            val notification = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("红果短剧")
                .setContentText(if (playing) "正在播放 · 点击返回红果"
                    else "已暂停 · 可从系统媒体卡片继续")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setShowWhen(false)
                .setContentIntent(
                    PendingIntent.getActivity(ctx, 790115,
                        launchIntent(ctx) ?: Intent(Intent.ACTION_MAIN)
                            .setPackage(ctx.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
                .addAction(Notification.Action.Builder(
                    icon, if (playing) "暂停" else "播放", toggleIntent).build())
                .setStyle(Notification.MediaStyle()
                    .setMediaSession(media.sessionToken)
                    .setShowActionsInCompactView(0))
                .build()
            notificationManager?.notify(NOTIFICATION_ID, notification)
            if (!reportedCard) {
                reportedCard = true
                LogUtil.info("系统媒体: MediaStyle 通知已发布 | pkg=" + ctx.packageName)
                LogUtil.incr("systemMediaNotificationPosted")
            }
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 通知发布失败（检查红果通知权限）: " +
                e.javaClass.simpleName + ": " + e.message)
            LogUtil.incr("systemMediaNotificationFailed")
        }
    }

    fun stop() {
        if (backgroundFlagApplied && originalBackgroundFlag != null) {
            setNativeBackgroundAllowance(originalBackgroundFlag == true)
        }
        backgroundFlagApplied = false
        requestedBackgroundPlayback = false
        leaveAt = 0L
        resumeAttempts = 0
        knownPlaying = false
        reportedCard = false
        try { notificationManager?.cancel(NOTIFICATION_ID) } catch (_: Throwable) {}
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        session = null
        receiver?.let {
            try { appContext?.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        receiver = null
        controller.clear()
        layer.clear()
        videoContext.clear()
        videoEngine.clear()
        foregroundActivity.clear()
        originalBackgroundFlag = null
        originalBackgroundHost.clear()
    }
}
