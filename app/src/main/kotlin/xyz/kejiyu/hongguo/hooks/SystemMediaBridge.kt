package xyz.kejiyu.hongguo.hooks

import android.app.Activity
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
        val next = requested && validTarget
        if (!next) {
            enabled = false
            stop()
            return
        }
        enabled = true
        appContext = ctx.applicationContext
        ensureSession()
    }

    fun rememberController(candidate: Any?) {
        if (candidate != null && candidate.javaClass.name.contains("VideoController"))
            controller = WeakReference(candidate)
    }

    fun rememberLayer(candidate: Any?) {
        if (candidate != null) layer = WeakReference(candidate)
    }

    fun onNativePlayback(playing: Boolean, source: String, candidate: Any? = null) {
        if (!enabled) return
        if (candidate != null) {
            if (candidate.javaClass.name.contains("VideoController")) rememberController(candidate)
            else rememberLayer(candidate)
        }
        knownPlaying = playing
        if (playing) {
            resumeAttempts = 0
            requestedBackgroundPlayback = requestedBackgroundPlayback &&
                SystemClock.uptimeMillis() - leaveAt < 30_000L
            updateMediaNotification(true)
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
            updateMediaNotification(false)
        }
        LogUtil.info("系统媒体播放状态=" + (if (playing) "playing" else "paused") +
            " source=" + source + " background=" + requestedBackgroundPlayback)
    }

    fun onUserLeave(activity: Activity?) {
        if (!enabled || activity == null || !knownPlaying) return
        if (!activity.javaClass.name.startsWith("com.dragon.read.")) return
        requestedBackgroundPlayback = true
        resumeAttempts = 0
        leaveAt = SystemClock.uptimeMillis()
        LogUtil.info("系统媒体: 离开红果，准备保持原视频音频播放 | " +
            activity.javaClass.simpleName)
        LogUtil.incr("systemMediaEnterBackground")
        updateMediaNotification(true)
    }

    fun onActivityResumed(activity: Activity?) {
        if (!enabled || activity == null ||
            !activity.javaClass.name.startsWith("com.dragon.read.")) return
        if (requestedBackgroundPlayback) {
            LogUtil.info("系统媒体: 返回红果，结束后台暂停保护")
            requestedBackgroundPlayback = false
            leaveAt = 0L
            resumeAttempts = 0
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
        if (active == null || candidate !== active) return false
        LogUtil.info("系统媒体: 忽略后台切换引发的 VideoController.pause")
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
        val targets = listOfNotNull(controller.get(), layer.get()).distinct()
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
                        knownPlaying = play
                        if (!play) requestedBackgroundPlayback = false
                        updateMediaNotification(play)
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
    }
}
