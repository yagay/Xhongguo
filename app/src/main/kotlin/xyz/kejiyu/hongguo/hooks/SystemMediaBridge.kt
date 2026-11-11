package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
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
import java.lang.reflect.Proxy
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
    private var lastHomePauseAt = 0L
    private var lastHomePauseEngine = WeakReference<Any>(null)
    private var lastHomePauseWasPlaying = false
    private var lastHomeUserLeaveAt = 0L
    private var lastHomeUserLeaveWasPlaying = false
    private var lastHomeUserLeaveEngine = WeakReference<Any>(null)
    // Prevent the 2026-11-11 07:32:50 home playback feedback loop:
    // every automatic play used to reset resumeAttempts to zero.
    private var backgroundResumeBudget = 0
    private var feedPauseGuardEngine = WeakReference<Any>(null)
    private var feedPauseGuardUntil = 0L
    private var feedPauseSuppressed = 0

    /** Only suppress repeated lifecycle pause on the SAME current feed engine
     * after background importance has actually been verified. Never block a
     * user-requested media-session pause, foreground pause or preloading.
     */
    fun shouldSuppressFeedEnginePause(candidate: Any?): Boolean {
        if (!enabled || candidate == null ||
            !requestedBackgroundPlayback || !knownPlaying ||
            feedPauseGuardEngine.get() !== candidate ||
            videoEngine.get() !== candidate ||
            SystemClock.uptimeMillis() > feedPauseGuardUntil) return false
        feedPauseSuppressed++
        if (feedPauseSuppressed <= 4 || feedPauseSuppressed % 20 == 0) {
            LogUtil.info("系统媒体: 已保护首页后台播放器 pause #" +
                feedPauseSuppressed)
        }
        LogUtil.incr("systemMediaFeedPauseSuppressed")
        return true
    }
    private var lastVisibleInfoAt = 0L
    private var lastVisibleInfoIdentity = ""
    private var lastVisibleInfoSource = ""
    private var currentEngineVideoIdentity: String? = null
    private var lastNativeMetaProbeAt = 0L
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

    // Native TTVideoEngine values are milliseconds. Unknown duration must NOT
    // be invented: the Android system only shows a usable seek bar when
    // MediaMetadata.DURATION + PlaybackState position are actually supplied.
    private var durationMs = -1L
    private var positionMs = -1L
    private var bufferedMs = -1L
    private var actualPlaybackSpeed = 1f
    private var seriesTitle: String? = null
    private var episodeTitle: String? = null
    private var coverArt: Bitmap? = null
    private var artworkRequestUrl: String? = null
    private var artworkRequestedAt = 0L
    private var artworkGeneration = 0L
    private var lastArtworkDiagnosticAt = 0L
    private var lastMetadataSignature: String? = null
    private var lastMetadataArt: Bitmap? = null
    private var progressPolling = false
    private var lastProgressDiagnosticAt = 0L

    private val timelineTicker = object : Runnable {
        override fun run() {
            progressPolling = false
            if (!enabled || session == null) return
            readCurrentTimeline()
            captureNativePlayEntity("periodic")
            updateSessionMetadata()
            updateSessionState(knownPlaying)
            val activeActivity = foregroundActivity.get()
            val now = SystemClock.uptimeMillis()
            if (knownPlaying && activeActivity?.hasWindowFocus() == true &&
                now - lastVisibleInfoAt >= 3200L) {
                lastVisibleInfoAt = now
                captureVisibleMediaInfo(activeActivity)
            }
            if (knownPlaying) scheduleTimelinePoll(1400L)
        }
    }

    private fun scheduleTimelinePoll(delay: Long) {
        if (progressPolling || !enabled) return
        progressPolling = true
        handler.postDelayed(timelineTicker, delay)
    }

    private fun reflectNumber(target: Any, name: String): Number? = try {
        (target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        }?.invoke(target)) as? Number
    } catch (_: Throwable) { null }

    /** Always use the most recently *playing* engine, not preloaded decoders. */
    private fun readCurrentTimeline() {
        val engine = videoEngine.get() ?: return
        if (engine.javaClass.name != "com.ss.ttvideoengine.TTVideoEngine") return
        try {
            val released = engine.javaClass.methods.firstOrNull {
                it.name == "isReleased" && it.parameterCount == 0
            }?.invoke(engine) as? Boolean
            if (released == true) return

            val nativeDuration = reflectNumber(engine, "getDuration")?.toLong()
            val nativePosition = (
                reflectNumber(engine, "getCurrentPlaybackTimeAsync")
                    ?: reflectNumber(engine, "getCurrentPlaybackTime")
                )?.toLong()
            val loaded = reflectNumber(engine, "getLoadedProgress")?.toInt()
            val speed = reflectNumber(engine, "getPlaybackSpeed")?.toFloat()

            if (nativeDuration != null && nativeDuration in 1000L..86_400_000L)
                durationMs = nativeDuration
            if (nativePosition != null && nativePosition >= 0 &&
                nativePosition <= 86_405_000L &&
                (durationMs <= 0 || nativePosition <= durationMs + 5000L)) {
                positionMs = if (durationMs > 0)
                    nativePosition.coerceAtMost(durationMs) else nativePosition
            }
            if (durationMs > 0 && loaded != null && loaded in 0..100)
                bufferedMs = durationMs * loaded / 100L
            if (speed != null && speed.isFinite() && speed in 0.25f..4f)
                actualPlaybackSpeed = speed

            val now = SystemClock.uptimeMillis()
            if (now - lastProgressDiagnosticAt > 15000L) {
                LogUtil.info("系统媒体: 播放进度采样 durationMs=$durationMs" +
                    " positionMs=$positionMs bufferedMs=$bufferedMs")
                LogUtil.incr("systemMediaTimelineSample")
                lastProgressDiagnosticAt = now
            }
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 读取 TTVideoEngine 进度失败: " +
                e.javaClass.simpleName + ": " + e.message)
            LogUtil.incr("systemMediaTimelineReadFailed")
        }
    }

    /**
     * Covers come from the currently bound native Saas detail / PlayEntity,
     * not random recommendation ImageViews or video-frame screenshots.
     * Guard each asynchronous bitmap with both engine identity and generation:
     * a previous drama must never overwrite the active media notification.
     */
    private fun requestNativeArtwork(raw: String?, reason: String) {
        if (!enabled || raw.isNullOrBlank()) return
        val ctx = appContext ?: return
        val url = MediaArtworkLoader.normalize(raw)
        if (url == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastArtworkDiagnosticAt > 15000L) {
                lastArtworkDiagnosticAt = now
                LogUtil.info("系统媒体: 原生封面字段存在，但并非可识别的 HTTPS 图片地址" +
                    " reason=$reason")
                LogUtil.incr("systemMediaArtworkUrlUnsupported")
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (url == artworkRequestUrl && (coverArt != null ||
            now - artworkRequestedAt < 90_000L)) return
        artworkRequestUrl = url
        artworkRequestedAt = now
        val generation = artworkGeneration
        val ownerEngine = videoEngine.get()
        LogUtil.info("系统媒体: 已发现原生剧集封面，准备异步读取 reason=$reason")
        LogUtil.incr("systemMediaArtworkRequested")
        MediaArtworkLoader.load(ctx, url) { bitmap, source ->
            if (!enabled || generation != artworkGeneration ||
                ownerEngine !== videoEngine.get() ||
                artworkRequestUrl != url) return@load
            if (bitmap == null) {
                LogUtil.warn("系统媒体: 封面加载失败 source=$source")
                LogUtil.incr("systemMediaArtworkUnavailable")
            } else if (!bitmap.isRecycled) {
                coverArt = bitmap
                updateSessionMetadata()
                updateMediaNotification(knownPlaying)
                LogUtil.info("系统媒体: 媒体封面已写入 MediaSession source=$source" +
                    " size=" + bitmap.width + "x" + bitmap.height)
                LogUtil.incr("systemMediaArtworkApplied")
            }
        }
    }

    private fun updateSessionMetadataDirect(media: MediaSession) {
        // Called before assigning session = media.
        try {
            val builder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, seriesTitle ?: "红果短剧")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, seriesTitle ?: "红果短剧")
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                    episodeTitle ?: "正在播放的视频")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    episodeTitle ?: "正在播放的视频")
            if (durationMs > 0L) builder.putLong(
                MediaMetadata.METADATA_KEY_DURATION, durationMs)
            media.setMetadata(builder.build())
            lastMetadataSignature = null // Let the next snapshot fill artwork.
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 初始媒体信息发布失败: " + e.message)
        }
    }

    private fun updateSessionMetadata() {
        val media = session ?: return
        val artwork = coverArt
        val signature = (seriesTitle ?: "") + "|" + (episodeTitle ?: "") +
            "|" + durationMs
        if (signature == lastMetadataSignature && artwork === lastMetadataArt) return
        try {
            val title = seriesTitle ?: "红果短剧"
            val subtitle = episodeTitle ?: "正在播放的视频"
            val builder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "红果短剧")
            if (durationMs > 0) builder.putLong(
                MediaMetadata.METADATA_KEY_DURATION, durationMs)
            if (artwork != null && !artwork.isRecycled) {
                builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
            }
            media.setMetadata(builder.build())
            lastMetadataSignature = signature
            lastMetadataArt = artwork
            LogUtil.info("系统媒体: 媒体信息已更新 title=" +
                (seriesTitle != null) + " episode=" + (episodeTitle != null) +
                " cover=" + (artwork != null) + " durationMs=$durationMs")
            LogUtil.incr("systemMediaMetadataUpdate")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 更新媒体信息失败: " + e.message)
        }
    }

    /**
     * Capture the live VideoShop PlayEntity, matched to the TTVideoEngine by
     * getVideoID() or exact engine identity. Unlike UI text heuristics, this
     * cannot accidentally read a recommendation for another video.
     */
    private fun selectEpisodeLabel(fullSeries: String?, other: String?): String? {
        if (fullSeries == null) return other
        val hasCurrent = fullSeries.trim().startsWith("第") ||
            fullSeries.trim().startsWith("EP", ignoreCase = true)
        if (hasCurrent) return fullSeries
        // A verified total ("全80集") must not obscure an exact episode
        // found on the same active player via its UI or PlayEntity.
        val alternative = other?.trim()
        if (alternative != null && (alternative.startsWith("第") ||
                alternative.startsWith("EP", ignoreCase = true))) {
            return if (fullSeries.startsWith("全") &&
                !alternative.contains("全")) alternative + " · " + fullSeries
                else alternative
        }
        return fullSeries
    }

    private fun captureNativePlayEntity(reason: String) {
        if (!enabled) return
        val engine = videoEngine.get() ?: return
        val now = SystemClock.uptimeMillis()
        if (reason == "periodic" && now - lastNativeMetaProbeAt < 4000L) return
        lastNativeMetaProbeAt = now

        val videoId = try {
            engine.javaClass.getMethod("getVideoID").invoke(engine)?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        if (videoId != null && currentEngineVideoIdentity != null &&
            videoId != currentEngineVideoIdentity) {
            seriesTitle = null
            episodeTitle = null
            coverArt = null
            artworkGeneration++
            artworkRequestUrl = null
            artworkRequestedAt = 0L
            durationMs = -1L
            positionMs = -1L
            bufferedMs = -1L
            lastMetadataSignature = null
            LogUtil.info("系统媒体: 同一引擎切换视频ID，清空旧剧名与集数")
        }
        if (videoId != null) currentEngineVideoIdentity = videoId
        val playEntity = NativePlayEntityInfo.readForEngine(
            engine, videoContext.get(), controller.get(), layer.get())
        val fullSeries = FullSeriesMediaInfo.read(foregroundActivity.get(), engine)
        // FullSeriesActivity has its own SaasVideoData/DetailModel pipeline.
        // Prefer it when available; do not replace its verified episode with
        // a generic PlayEntity title or a different preloaded episode.
        val title = fullSeries.title ?: playEntity.title
        val episode = selectEpisodeLabel(fullSeries.episode, playEntity.episode)
        val artworkUrl = fullSeries.artworkUrl ?: playEntity.artworkUrl
        requestNativeArtwork(artworkUrl, "native:$reason")
        var changed = false
        if (title != null && title != seriesTitle) {
            seriesTitle = title
            changed = true
        }
        if (episode != null && episode != episodeTitle) {
            episodeTitle = episode
            changed = true
        }
        if (changed) {
            LogUtil.info("系统媒体: 原生播放器媒体信息已绑定 reason=$reason" +
                " source=" + (if (fullSeries.title != null ||
                    fullSeries.episode != null) fullSeries.source else playEntity.source) +
                " title=" + (title != null) + " episode=" + (episode != null))
            updateSessionMetadata()
            if (reportedCard) updateMediaNotification(knownPlaying)
        }
    }

    private fun captureVisibleMediaInfo(activity: Activity?) {
        if (!enabled || activity == null) return
        val isFullSeries = activity.javaClass.name ==
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        if (!isFullSeries && !activity.hasWindowFocus()) return
        // Series data objects remain available even when playback controls
        // are hidden and the window has lost focus during backgrounding.
        val fullSeries = if (isFullSeries)
            FullSeriesMediaInfo.read(activity, videoEngine.get())
        else FullSeriesMediaInfo.Info()
        val ui = if (activity.hasWindowFocus()) NativeMediaInfo.read(activity)
            else NativeMediaInfo.Info()
        val title = fullSeries.title ?: ui.title
        val episode = selectEpisodeLabel(fullSeries.episode, ui.episode)
        requestNativeArtwork(fullSeries.artworkUrl, "fullseries-visible")
        var changed = false
        if (title != null && title != seriesTitle) {
            seriesTitle = title
            changed = true
        }
        if (episode != null && episode != episodeTitle) {
            episodeTitle = episode
            changed = true
        }
        if (ui.artwork != null && ui.artwork !== coverArt) {
            coverArt = ui.artwork
            changed = true
        }
        val source = if (fullSeries.title != null || fullSeries.episode != null)
            fullSeries.source else ui.source
        val identity = activity.javaClass.simpleName + "|" +
            (title ?: "") + "|" + (episode ?: "")
        if (identity != lastVisibleInfoIdentity || source != lastVisibleInfoSource) {
            lastVisibleInfoIdentity = identity
            lastVisibleInfoSource = source
            LogUtil.info("系统媒体: 当前播放信息采集 page=" +
                activity.javaClass.simpleName + " source=$source" +
                " title=" + (title != null) +
                " episode=" + (episode != null) +
                " cover=" + (ui.artwork != null))
            LogUtil.incr("systemMediaVisibleInfoScan")
        }
        if (changed) {
            updateSessionMetadata()
            if (reportedCard) updateMediaNotification(knownPlaying)
        }
    }

    private fun requestSeek(position: Long, reason: String) {
        if (!enabled || durationMs <= 0L) {
            LogUtil.warn("系统媒体: 进度跳转未启用（总时长未知）")
            return
        }
        val engine = videoEngine.get() ?: return
        val target = position.coerceIn(0L, durationMs).toInt()
        try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == "seekTo" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1].isInterface
            } ?: engine.javaClass.methods.firstOrNull {
                it.name == "seekTo" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: run {
                LogUtil.warn("系统媒体: TTVideoEngine 未找到可用的 seekTo")
                return
            }
            val arguments: Array<Any?> = if (method.parameterCount == 2) {
                val listenerType = method.parameterTypes[1]
                val listener = Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, callback, args ->
                    if (callback.name == "onCompletion") {
                        LogUtil.info("系统媒体: 跳转完成 success=" +
                            args?.firstOrNull()?.toString())
                    }
                    null
                }
                arrayOf(target, listener)
            } else arrayOf(target)
            method.invoke(engine, *arguments)
            positionMs = target.toLong()
            updateSessionState(knownPlaying)
            handler.postDelayed({
                readCurrentTimeline()
                updateSessionState(knownPlaying)
            }, 350L)
            LogUtil.info("系统媒体: 系统进度跳转 reason=$reason posMs=$target")
            LogUtil.incr("systemMediaSeek")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 原生 seekTo 调用失败: " +
                e.javaClass.simpleName + ": " + e.message)
            LogUtil.incr("systemMediaSeekFailed")
        }
    }

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
            if (videoEngine.get() !== candidate) {
                durationMs = -1L
                positionMs = -1L
                bufferedMs = -1L
                actualPlaybackSpeed = 1f
                seriesTitle = null
                episodeTitle = null
                coverArt = null
                artworkGeneration++
                artworkRequestUrl = null
                artworkRequestedAt = 0L
                lastMetadataSignature = null
                lastMetadataArt = null
                lastProgressDiagnosticAt = 0L
                lastVisibleInfoAt = 0L
                lastVisibleInfoIdentity = ""
                lastVisibleInfoSource = ""
                currentEngineVideoIdentity = null
                lastNativeMetaProbeAt = 0L
                if (feedPauseGuardEngine.get() !== candidate) {
                    feedPauseGuardEngine.clear()
                    feedPauseGuardUntil = 0L
                    feedPauseSuppressed = 0
                }
                LogUtil.info("系统媒体: 发现新的原生播放器实例，清空旧剧集进度")
            }
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
        if (action == "pause" && foregroundActivity.get()
                ?.javaClass?.name?.contains("MainFragmentActivity") == true) {
            lastHomePauseAt = SystemClock.uptimeMillis()
            lastHomePauseEngine = WeakReference(candidate)
            lastHomePauseWasPlaying = knownPlaying
            LogUtil.info("系统媒体: 首页原生暂停，之前播放=" + lastHomePauseWasPlaying)
        }
        if (action == "play") {
            lastHomePauseWasPlaying = false
            onNativePlayback(true, "TTVideoEngine#play", candidate)
            captureNativePlayEntity("engine-play")
            readCurrentTimeline()
            updateSessionMetadata()
            updateSessionState(true)
            scheduleTimelinePoll(600L)
            // The native Activity can still be drawing its title/episode overlay.
            val act = foregroundActivity.get()
            for (delay in longArrayOf(600L, 1600L, 3000L)) {
                handler.postDelayed({
                    if (foregroundActivity.get() === act && act != null &&
                        !act.isFinishing && act.hasWindowFocus())
                        captureVisibleMediaInfo(act)
                }, delay)
            }
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
            val playEntity = try {
                candidate.javaClass.getMethod("getPlayEntity").invoke(candidate)
            } catch (_: Throwable) { null }
            NativePlayEntityInfo.remember(playEntity)
            val playing = nativeIsPlaying(candidate)
            val boundLayer = try {
                candidate.javaClass.getMethod("getLayerHostMediaLayout").invoke(candidate)
            } catch (_: Throwable) { null }
            if (boundLayer != null) {
                rememberLayer(boundLayer)
                val boundEntity = try {
                    boundLayer.javaClass.getMethod("getBindPlayEntity").invoke(boundLayer)
                } catch (_: Throwable) { null }
                NativePlayEntityInfo.remember(boundEntity)
            }
            captureNativePlayEntity("context-discovery")
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
        captureVisibleMediaInfo(activity)
        val name = activity.javaClass.simpleName
        val recentHomeLeave = activity.javaClass.name.contains("MainFragmentActivity") &&
            SystemClock.uptimeMillis() - lastHomeUserLeaveAt in 0L..3000L &&
            lastHomeUserLeaveWasPlaying &&
            lastHomeUserLeaveEngine.get() === videoEngine.get()
        val wasPlayingBeforePause = knownPlaying || recentHomeLeave
        LogUtil.info("系统媒体: Activity.onPause $name knownPlaying=$knownPlaying" +
            " recentHomeLeave=$recentHomeLeave")
        handler.postDelayed({
            if (!enabled || foregroundActivity.get() !== activity) return@postDelayed
            val state = ActivityManager.RunningAppProcessInfo()
            try { ActivityManager.getMyMemoryState(state) } catch (_: Throwable) {
                return@postDelayed
            }
            val isBackground = state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            LogUtil.info("系统媒体: Activity 暂停后进程 importance=" +
                state.importance + " background=$isBackground activity=$name")
            if (isBackground) {
                val isHome = activity.javaClass.name.contains("MainFragmentActivity")
                val recentPause = SystemClock.uptimeMillis() - lastHomePauseAt in 0L..1900L
                val sameEngine = lastHomePauseEngine.get() === videoEngine.get() &&
                    videoEngine.get() != null
                val shouldRecoverFeed = isHome && wasPlayingBeforePause &&
                    !knownPlaying && lastHomePauseWasPlaying &&
                    recentPause && sameEngine && !userCommandInProgress
                if (shouldRecoverFeed) {
                    LogUtil.warn("系统媒体: 首页后台检测晚于原生 pause，恢复原播放器" +
                        " elapsedMs=" + (SystemClock.uptimeMillis() - lastHomePauseAt))
                    LogUtil.incr("systemMediaFeedBackgroundRecovery")
                    // Genuine process background confirmed; this is not an
                    // in-app navigation to fullscreen/series Activities.
                    requestedBackgroundPlayback = true
                    leaveAt = SystemClock.uptimeMillis()
                    resumeAttempts = 0
                    backgroundResumeBudget = 0 // one explicit recovery, no retry
                    feedPauseGuardEngine = WeakReference(videoEngine.get())
                    feedPauseGuardUntil = leaveAt + 10L * 60_000L
                    feedPauseSuppressed = 0
                    setNativeBackgroundAllowance(true)
                    LogUtil.info("系统媒体: 首页后台只恢复一次，后续生命周期 pause" +
                        " 将针对当前播放器阻止；用户暂停仍允许")
                    requestTransport(true, "feed-background-recovery")
                    lastHomePauseWasPlaying = false
                } else {
                    onBackgroundTransition(activity, "Activity.onPause")
                    LogUtil.info("系统媒体: 首页恢复判定 background=$isBackground" +
                        " home=$isHome wasPlaying=$wasPlayingBeforePause" +
                        " knownPlaying=$knownPlaying recentPause=$recentPause" +
                        " sameEngine=$sameEngine")
                }
            }
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
        backgroundResumeBudget = 1
        captureVisibleMediaInfo(activity)
        readCurrentTimeline()
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
            scheduleTimelinePoll(900L)
            if (!requestedBackgroundPlayback) resumeAttempts = 0
            // Do not expire background mode after 30 seconds of successful
            // playback. The callback is the live player, not a new session.
            if (stateChanged || !reportedCard) updateMediaNotification(true)
        } else {
            readCurrentTimeline()
            updateSessionMetadata()
            updateSessionState(false)
            lastPauseAt = SystemClock.uptimeMillis()
            if (requestedBackgroundPlayback && backgroundResumeBudget > 0 &&
                lastPauseAt - leaveAt in 0L..4000L &&
                !userCommandInProgress && feedPauseGuardEngine.get() == null) {
                backgroundResumeBudget--
                resumeAttempts++
                LogUtil.warn("系统媒体: 后台暂停，单次恢复尝试 #" + resumeAttempts)
                handler.postDelayed({
                    if (enabled && requestedBackgroundPlayback && !knownPlaying &&
                        feedPauseGuardEngine.get() == null) {
                        requestTransport(true, "background-auto-resume")
                    }
                }, 350L)
            } else if (requestedBackgroundPlayback && !userCommandInProgress &&
                feedPauseGuardEngine.get() != null) {
                LogUtil.warn("系统媒体: 首页后台播放器仍被暂停；不进行重复 play 循环")
                LogUtil.incr("systemMediaFeedPlaybackStopped")
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
        if (activity.javaClass.name.contains("MainFragmentActivity")) {
            // Hint can fire during an in-app transition. Do NOT activate
            // background recovery until Activity.onPause confirms the process
            // actually became background. Avoids a premature play/pause loop.
            lastHomeUserLeaveAt = SystemClock.uptimeMillis()
            lastHomeUserLeaveWasPlaying = true
            lastHomeUserLeaveEngine = WeakReference(videoEngine.get())
            LogUtil.info("系统媒体: 首页 onUserLeaveHint 已记录，等待真实后台确认")
            return
        }
        onBackgroundTransition(activity, "onUserLeaveHint")
    }

    fun onActivityResumed(activity: Activity?) {
        if (!enabled || activity == null ||
            !activity.javaClass.name.startsWith("com.dragon.read.")) return
        foregroundActivity = WeakReference(activity)
        if (!activity.javaClass.name.contains("MainFragmentActivity")) {
            lastHomeUserLeaveAt = 0L
            lastHomeUserLeaveWasPlaying = false
            lastHomeUserLeaveEngine.clear()
        }
        if (feedPauseGuardEngine.get() != null) {
            feedPauseGuardEngine.clear()
            feedPauseGuardUntil = 0L
            feedPauseSuppressed = 0
            backgroundResumeBudget = 0
            LogUtil.info("系统媒体: 红果恢复前台，解除首页播放器暂停保护")
        }
        for (delay in longArrayOf(650L, 2100L)) {
            handler.postDelayed({
                if (enabled && foregroundActivity.get() === activity && !activity.isFinishing)
                    captureVisibleMediaInfo(activity)
            }, delay)
        }
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
                override fun onSeekTo(pos: Long) {
                    handler.post { requestSeek(pos, "system-seek") }
                }
            }, handler)
            launchIntent(ctx)?.let { intent ->
                media.setSessionActivity(
                    PendingIntent.getActivity(ctx, 790113, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            updateSessionMetadataDirect(media)
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
        if (!play && reason.startsWith("system") ||
            !play && reason == "notification-action") {
            feedPauseGuardEngine.clear()
            feedPauseGuardUntil = 0L
            backgroundResumeBudget = 0
            requestedBackgroundPlayback = false
            LogUtil.info("系统媒体: 用户主动暂停，解除首页后台保护")
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
            val canSeek = durationMs > 0L && positionMs >= 0L &&
                videoEngine.get() != null
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            if (canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
            val builder = PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    if (positionMs >= 0L) positionMs
                        else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) actualPlaybackSpeed else 0f,
                    SystemClock.elapsedRealtime()
                )
            if (bufferedMs >= 0L) builder.setBufferedPosition(bufferedMs)
            media.setPlaybackState(builder.build())
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
                .setContentTitle(seriesTitle ?: "红果短剧")
                .setContentText(episodeTitle ?: if (playing)
                    "正在播放 · 点击返回红果" else "已暂停 · 点击返回红果")
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
                .apply {
                    // Fallback for older/expanded notifications; Android 13+
                    // media cards use ART/DISPLAY_ICON in MediaMetadata.
                    coverArt?.let { art ->
                        if (!art.isRecycled) setLargeIcon(art)
                    }
                }
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
        handler.removeCallbacks(timelineTicker)
        progressPolling = false
        durationMs = -1L
        positionMs = -1L
        bufferedMs = -1L
        actualPlaybackSpeed = 1f
        seriesTitle = null
        episodeTitle = null
        coverArt = null
        artworkGeneration++
        artworkRequestUrl = null
        artworkRequestedAt = 0L
        lastMetadataSignature = null
        lastMetadataArt = null
        lastHomePauseAt = 0L
        lastHomePauseWasPlaying = false
        lastHomeUserLeaveAt = 0L
        lastHomeUserLeaveWasPlaying = false
        lastHomeUserLeaveEngine.clear()
        backgroundResumeBudget = 0
        feedPauseGuardEngine.clear()
        feedPauseGuardUntil = 0L
        feedPauseSuppressed = 0
        lastHomePauseEngine.clear()
        lastVisibleInfoAt = 0L
        lastVisibleInfoIdentity = ""
        lastVisibleInfoSource = ""
        currentEngineVideoIdentity = null
        lastNativeMetaProbeAt = 0L
        controller.clear()
        layer.clear()
        videoContext.clear()
        videoEngine.clear()
        foregroundActivity.clear()
        originalBackgroundFlag = null
        originalBackgroundHost.clear()
    }
}
