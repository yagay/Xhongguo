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
import android.os.Bundle
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.FeatureTrace

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
    // x05.w is the official full-series business player. Its completion
    // can be received without the background ViewPager2 advancing.
    private var nativeSeriesPlayer = WeakReference<Any>(null)
    private var nativeEofEngine = WeakReference<Any>(null)
    private var nativeEofAt = 0L
    private var nativeEofPauseEpoch = 0L
    private var nativeEofGeneration = 0L
    private var nativeEofRecoveryScheduled = false
    private var nativeEofRecoveryAttempted = false
    // No feature writes directly to a second MediaSession. All pages pass
    // through this common refresh and publication lifecycle.
    private val mediaPresentation = MediaPresentationState()
    private val mediaRefresh = MediaRefreshCoordinator(handler,
        isActive = { enabled && videoEngine.get() != null },
        refresh = { reason ->
            captureNativePlayEntity(reason)
            updateSessionMetadata()
            if (reportedCard) updateMediaNotification(knownPlaying)
        })
    @Volatile private var mediaFlow = 0L
    private var foregroundActivity = WeakReference<Activity>(null)
    private var lastDiscoveryAt = 0L
    private var lastNativeEngineAt = 0L
    private var lastLifecyclePauseAt = 0L
    private var lastPlayerActivityPauseAt = 0L
    private var lastLifecyclePauseEngine = WeakReference<Any>(null)
    private var lastLifecyclePauseWasPlaying = false
    private var lastUserLeaveAt = 0L
    private var lastUserLeaveWasPlaying = false
    private var lastUserLeaveEngine = WeakReference<Any>(null)
    /**
     * Android does not guarantee onUserLeaveHint when another application
     * becomes foreground (recents, links, notifications, multi-tasking).
     * Capture a candidate BEFORE native Activity.onPause pauses the engine.
     * Only a later, VERIFIED background/stopped transition may recover it.
     */
    private data class ExitSnapshot(
        val activity: WeakReference<Activity>,
        val engine: WeakReference<Any>,
        val at: Long,
        val wasPlaying: Boolean,
        val nativeAtCapture: Boolean?,
        val reason: String,
        val explicitPauseEpochAtCapture: Long,
        val videoContext: WeakReference<Any>,
        var stopped: Boolean = false
    )
    @Volatile private var pendingExit: ExitSnapshot? = null
    private val backgroundTransition = BackgroundTransitionState()
    private const val EXIT_SNAPSHOT_WINDOW_MS = 4800L
    // Explicit media-card pause after a snapshot cancels pending auto-resume.
    @Volatile private var explicitPauseEpoch = 0L
    private var prePauseApp: Application? = null
    private var prePauseListener: Application.ActivityLifecycleCallbacks? = null

    /**
     * Framework Activity.onPause hooks can execute AFTER a host subclass has
     * already paused TTVideoEngine (because it calls super.onPause last).
     * Application.onActivityPrePaused fires BEFORE the subclass onPause and
     * is the reliable counterpart to Home-only onUserLeaveHint.
     */
    private fun ensurePrePauseObserver(ctx: Context) {
        val application = ctx.applicationContext as? Application ?: return
        if (prePauseApp === application) return
        val listener = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityPrePaused(activity: Activity) {
                if (!enabled || !isVideoActivity(activity)) return
                try {
                    val snapshot = rememberExitSnapshot(
                        activity, "Application.prePause")
                    val engine = snapshot?.engine?.get()
                    if (snapshot != null && engine != null &&
                        snapshot.wasPlaying && !activity.isFinishing &&
                        foregroundActivity.get() === activity &&
                        snapshot.explicitPauseEpochAtCapture == explicitPauseEpoch) {
                        // All exits share this provisional guard: no reliance
                        // on Android's optional onUserLeaveHint callback.
                        backgroundTransition.begin(activity, engine, true,
                            explicitPauseEpoch, SystemClock.uptimeMillis())
                        // The APK's VideoPatchLayout.playInternal reads its
                        // own canPlayBackground field. Setting it only after
                        // Activity.onStop is too late for native lifecycle
                        // and the next episode. Preserve the original value
                        // and restore it if this is only in-app navigation.
                        setNativeBackgroundAllowance(true)
                        FeatureTrace.event("background", "pre_pause_candidate",
                            "captured", "hint=" + lastUserLeaveWasPlaying +
                                ",nativeKnown=" +
                                (snapshot.nativeAtCapture != null),
                            flow = mediaFlow)
                    }
                } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: PrePause 快照失败 " +
                        e.javaClass.simpleName)
                }
            }
        }
        try {
            application.registerActivityLifecycleCallbacks(listener)
            prePauseApp = application
            prePauseListener = listener
            LogUtil.info("系统媒体: 已监听 ActivityPrePaused，支持最近任务切换")
            LogUtil.incr("systemMediaPrePauseObserverInstalled")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: ActivityPrePaused 监听失败 " +
                e.javaClass.simpleName)
        }
    }
    // Prevent the 2026-11-11 07:32:50 home playback feedback loop:
    // every automatic play used to reset resumeAttempts to zero.
    private var backgroundResumeBudget = 0
    private var backgroundPauseGuardEngine = WeakReference<Any>(null)
    private var backgroundPauseGuardUntil = 0L
    private var backgroundPauseSuppressed = 0

    /** A short provisional guard is armed by ActivityPrePaused, not by
     * the optional onUserLeaveHint. It protects only the pinned native engine
     * and the matching VideoShop owner. Real background is confirmed later.
     */
    private fun shouldHoldOriginalOnLeave(candidateEngine: Any?): Boolean {
        // Once native EOF was observed, pause/release belongs to the host's
        // NEXT episode handoff, not Android background lifecycle.
        if (episodeMonitor.hasNativeCompletion()) return false
        val hold = backgroundTransition.canHold(
            ownerActivity = foregroundActivity.get(),
            ownerEngine = candidateEngine,
            explicitPauseEpoch = explicitPauseEpoch,
            explicitCommand = userCommandInProgress,
            ownershipSwitch = SinglePlaybackOwner.isOwnershipTransition(),
            nowMs = SystemClock.uptimeMillis())
        if (hold) backgroundTransition.recordHeld()
        return hold
    }

    /** VideoShop is used on some pages, but this APK's full-series
     * playback is x05.w, independent of VideoShop. Never protect an old
     * or inactive VideoContext while the actual series TTVideoEngine belongs
     * to x05.w; doing so can retain unrelated prepared decoders.
     */
    private fun videoShopOwnsPlayingEngine(candidate: Any?): Boolean {
        if (candidate == null) return false
        val actual = videoEngine.get() ?: return false
        return try {
            val engine = candidate.javaClass.methods.firstOrNull {
                it.name == "getVideoEngine" && it.parameterCount == 0
            }?.invoke(candidate)
            engine != null && engine === actual
        } catch (_: Throwable) { false }
    }

    /**
     * Targeted APK hook: VideoContext.onLifeCycleOnStop() invokes
     * releaseAllPreparedVideoControllers(). Keep that exact prepared-video
     * owner during a provisional external-app exit rather than skipping the
     * ENTIRE VideoContext lifecycle. This leaves native completion listeners,
     * normal pause bookkeeping and app-internal navigation intact.
     *
     * This hold is provisional until Activity.onStop + process importance
     * confirm a real background transition. The short guard is scoped to
     * the same VideoContext instance and pinned video engine.
     */
    fun shouldHoldPreparedControllerRelease(candidate: Any?): Boolean {
        if (!enabled || candidate == null || candidate !== videoContext.get() ||
            !videoShopOwnsPlayingEngine(candidate)) return false
        val hold = backgroundTransition.canKeepPreparedOnStop(
            ownerActivity = foregroundActivity.get(),
            ownerEngine = videoEngine.get(),
            explicitPauseEpoch = explicitPauseEpoch,
            explicitCommand = userCommandInProgress,
            ownershipSwitch = SinglePlaybackOwner.isOwnershipTransition(),
            nowMs = SystemClock.uptimeMillis())
        if (!hold) return false
        backgroundTransition.recordHeld()
        LogUtil.info("系统媒体: 保护原生下一集预备播放器（仅当前后台过渡）")
        LogUtil.incr("systemMediaPreparedControllersHeld")
        FeatureTrace.event("background", "prepared_controller_release",
            "held", "scope=same_video_context", flow = mediaFlow,
            minIntervalMs = 1000L)
        return true
    }

    // The official 7.3.5.32 short-series player is x05.w (x05.f), NOT
    // VideoShop VideoContext. The real next-episode route is:
    // x05.w.onCompletion(TTVideoEngine) -> vm4.a.U1(String) ->
    // v2.view.adapter.a.U1(String) -> G4().
    // Protect that native business player, rather than just its decoder.
    private const val SERIES_NATIVE_PLAYER = "x05.w"
    private const val SERIES_NATIVE_ADAPTER =
        "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"

    private fun seriesPlayerEngine(player: Any?): Any? {
        if (player?.javaClass?.name != SERIES_NATIVE_PLAYER) return null
        return try {
            // APK-confirmed x05.w.H(): TTVideoEngine (reads field h).
            player.javaClass.getMethod("H").invoke(player)
        } catch (_: Throwable) { null }
    }

    /** Protect exactly the current native series player while the same
     * Activity is leaving for background. An in-app page switch cancels the
     * provisional handoff; a system media pause invalidates its pause epoch.
     */
    private fun seriesBusinessHandoffAllowed(player: Any?): Boolean {
        if (episodeMonitor.hasNativeCompletion()) return false
        if (!enabled || foregroundActivity.get()?.javaClass?.name !=
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
            return false
        val engine = seriesPlayerEngine(player) ?: return false
        if (videoEngine.get() !== engine) return false
        return backgroundTransition.canKeepPreparedOnStop(
            ownerActivity = foregroundActivity.get(),
            ownerEngine = engine,
            explicitPauseEpoch = explicitPauseEpoch,
            explicitCommand = userCommandInProgress,
            ownershipSwitch = SinglePlaybackOwner.isOwnershipTransition(),
            nowMs = SystemClock.uptimeMillis())
    }

    /** APK-native business pause (x05.w.pause) can be called from
     * ShortSeriesSingleFragment.onStop -> adapter.x. It updates the
     * host player's own state even if TTVideoEngine.pause is suppressed.
     */
    fun shouldHoldSeriesBusinessPause(player: Any?): Boolean {
        if (!seriesBusinessHandoffAllowed(player)) return false
        backgroundTransition.recordHeld()
        LogUtil.info("系统媒体: 保留官方全集播放器 x05.w.pause" +
            "（防止业务连播控制器提前暂停）")
        LogUtil.incr("systemMediaSeriesBusinessPauseHeld")
        FeatureTrace.event("background", "series_business_pause", "held",
            "sameNativeEngine=true", flow = mediaFlow, minIntervalMs = 500L)
        return true
    }

    /** x05.w.f(vm4.a) removes an observer from its weak listener list,
     * whereas x05.w.v(vm4.a) registers it. Keep only the existing
     * ShortSeries adapter observer during the same verified exit.
     * Never fabricate an observer or synthesize a next-episode command.
     */
    fun shouldHoldSeriesListenerRemoval(player: Any?, listener: Any?): Boolean {
        if (listener?.javaClass?.name != SERIES_NATIVE_ADAPTER ||
            !seriesBusinessHandoffAllowed(player)) return false
        backgroundTransition.recordHeld()
        LogUtil.info("系统媒体: 保留当前全集自动续集监听器 x05.w.f")
        LogUtil.incr("systemMediaSeriesListenerHeld")
        FeatureTrace.event("background", "series_listener_remove", "held",
            "adapter=true,sameNativeEngine=true", flow = mediaFlow,
            minIntervalMs = 500L)
        return true
    }

    /** A narrow observation at the actual native business callback, not
     * a guess based on the unused VideoShop completion hooks.
     */
    fun onSeriesNativeCallback(stage: String, player: Any?, listener: Any? = null) {
        if (!enabled) return
        val engine = seriesPlayerEngine(player)
        val sameEngine = engine != null && engine === videoEngine.get()
        val count: Int?
        val adapters: Int?
        if (player?.javaClass?.name == SERIES_NATIVE_PLAYER) {
            val list = try {
                val field = player.javaClass.getDeclaredField("v")
                field.isAccessible = true
                field.get(player) as? List<*>
            } catch (_: Throwable) { null }
            count = list?.size
            adapters = list?.count { entry ->
                (entry as? WeakReference<*>)?.get()?.javaClass?.name ==
                    SERIES_NATIVE_ADAPTER
            }
        } else {
            count = null
            adapters = null
        }
        val adapterListener = listener?.javaClass?.name == SERIES_NATIVE_ADAPTER
        val relevant = sameEngine || adapterListener
        if (!relevant) return
        if (sameEngine && player != null)
            nativeSeriesPlayer = WeakReference(player)
        LogUtil.info("系统媒体: 官方全集业务回调 stage=$stage" +
            " nativeOwner=$sameEngine listeners=" + (count ?: -1) +
            " seriesAdapters=" + (adapters ?: -1) +
            " adapterListener=$adapterListener" +
            " background=$requestedBackgroundPlayback")
        FeatureTrace.event("background", "series_native_callback", "observed",
            "stage=" + stage + ",nativeOwner=" + sameEngine +
                ",listeners=" + (count ?: -1) +
                ",adapters=" + (adapters ?: -1) +
                ",adapterListener=" + adapterListener +
                ",background=" + requestedBackgroundPlayback,
            flow = mediaFlow, minIntervalMs = 100L)
    }

    /** The actual series next-episode handler is the native adapter's
     * U1(String) -> G4(), not VideoContext.onVideoCompleted().
     * Record both entry and return to identify a missing listener vs
     * an adapter which receives EOF but declines to move the episode list.
     */
    /**
     * APK's adapter.a.U1(String) emits the host's completion callback, but
     * a.U1 only conditionally invokes G4(): G4 plays the CURRENT holder,
     * not the next episode. Never force G4() when the host is backgrounded.
     *
     * If the host did not advance by itself after EOF, a bounded recovery
     * may select the existing next episode through its OWN ViewPager2.
     */
    fun onSeriesAdapterCallback(method: String, phase: String, adapter: Any? = null) {
        if (!enabled) return
        val currentPage = foregroundActivity.get()?.javaClass?.simpleName
        if (method == "U1" && phase == "before" && adapter != null) {
            // Bytecode confirms J3 and b4 are read-only condition getters.
            // A missing G4 is not sufficient proof of failed autoplay.
            val gate = try {
                val m = adapter.javaClass.getMethod("J3")
                m.invoke(adapter) as? Number
            } catch (_: Throwable) { null }
            val specialReplay = try {
                adapter.javaClass.getMethod("b4").invoke(adapter) as? Boolean
            } catch (_: Throwable) { null }
            LogUtil.info("系统媒体: 官方 U1 条件 probe" +
                " J3IsOne=" + (gate?.toInt() == 1) +
                " b4=" + (specialReplay?.toString() ?: "unknown"))
            val engine = videoEngine.get()
            val owner = nativeSeriesPlayer.get()
            if (engine != null && owner != null &&
                seriesPlayerEngine(owner) === engine &&
                nativeEofEngine.get() === engine &&
                episodeMonitor.hasNativeCompletion() &&
                requestedBackgroundPlayback && !nativeEofRecoveryScheduled &&
                currentPage == "ShortSeriesActivity") {
                nativeEofRecoveryScheduled = true
                val epoch = nativeEofPauseEpoch
                val generation = nativeEofGeneration
                val originalActivity = foregroundActivity.get()
                // Pin the current adapter for just one delayed diagnostic /
                // recovery operation; no permanent strong listener or
                // copied video URL/episode model.
                handler.postDelayed({
                    maybeRecoverSeriesNativePager(adapter, engine,
                        originalActivity, epoch, generation)
                }, 5500L)
            }
        }
        LogUtil.info("系统媒体: 官方全集适配器事件 method=$method" +
            " phase=$phase background=$requestedBackgroundPlayback" +
            " page=" + (currentPage ?: "none"))
        FeatureTrace.event("background", "series_adapter", "observed",
            "method=" + method + ",phase=" + phase +
                ",background=" + requestedBackgroundPlayback,
            flow = mediaFlow, minIntervalMs = 100L)
    }

    /** A conservative last resort after native EOF, not an automatic loop.
     * Only advance the existing APK ViewPager2 when the current item/index,
     * engine identity, native EOF, explicit pause epoch and actual loaded
     * next-item count all agree. Last episode is never advanced.
     */
    private fun maybeRecoverSeriesNativePager(
        adapter: Any, completedEngine: Any, activity: Activity?,
        pauseEpoch: Long, generation: Long
    ) {
        if (!enabled || nativeEofRecoveryAttempted ||
            generation != nativeEofGeneration ||
            nativeEofEngine.get() !== completedEngine ||
            videoEngine.get() !== completedEngine ||
            !episodeMonitor.hasNativeCompletion() ||
            lastNativeEngineAt > nativeEofAt ||
            activity == null || foregroundActivity.get() !== activity ||
            activity.isFinishing || userCommandInProgress ||
            explicitPauseEpoch != pauseEpoch || !requestedBackgroundPlayback)
            return

        try {
            readCurrentTimeline()
            val current = adapter.javaClass.getDeclaredField("D").apply {
                isAccessible = true
            }.getInt(adapter)
            val pager = adapter.javaClass.getDeclaredField("k").apply {
                isAccessible = true
            }.get(adapter) ?: return
            val pagerPosition = (pager.javaClass.getMethod("getCurrentItem")
                .invoke(pager) as? Number)?.toInt() ?: return
            val pagerAdapter = pager.javaClass.getMethod("getAdapter")
                .invoke(pager) ?: return
            val pagerCount = (pagerAdapter.javaClass.getMethod("getItemCount")
                .invoke(pagerAdapter) as? Number)?.toInt() ?: return
            // gp4.f is the official RecyclerView adapter base; h contains
            // the actual available SaasVideoDetailModel items.
            var inherited: Class<*>? = adapter.javaClass
            var dataCount = -1
            while (inherited != null && inherited != Any::class.java) {
                try {
                    val field = inherited.getDeclaredField("h")
                    field.isAccessible = true
                    val list = field.get(adapter) as? List<*>
                    if (list != null) dataCount = list.size
                    break
                } catch (_: NoSuchFieldException) {
                    inherited = inherited.superclass
                }
            }
            val count = if (dataCount > 0) minOf(dataCount, pagerCount) else -1
            val evidence = SeriesAutoplayRecoveryPolicy.Evidence(
                background = requestedBackgroundPlayback,
                eofObserved = episodeMonitor.hasNativeCompletion(),
                sameNativeEngine = videoEngine.get() === completedEngine,
                sameSeriesActivity = activity.javaClass.name ==
                    "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
                explicitPauseUnchanged = explicitPauseEpoch == pauseEpoch,
                noNewVideoStarted = lastNativeEngineAt <= nativeEofAt,
                completionAgeMs = SystemClock.uptimeMillis() - nativeEofAt,
                currentIndex = current, pagerIndex = pagerPosition,
                itemCount = count,
                nearRealNativeEnd = durationMs > 5000L &&
                    positionMs >= durationMs - 350L,
                alreadyAttempted = nativeEofRecoveryAttempted
            )
            val next = SeriesAutoplayRecoveryPolicy.nextIndex(evidence)
            if (next == null) {
                LogUtil.info("系统媒体: 后台自然连播未恢复；原生选集校验未通过" +
                    " current=$current pager=$pagerPosition" +
                    " available=$count eofNear=" + evidence.nearRealNativeEnd)
                FeatureTrace.event("background", "series_pager_recovery",
                    "degraded", "reason=safety_guard,indexAligned=" +
                        (current == pagerPosition) +
                        ",hasNext=" + (count > current + 1),
                    flow = mediaFlow)
                return
            }
            nativeEofRecoveryAttempted = true
            // Native ViewPager2 triggers the host's own page-selection
            // callbacks. Do NOT call adapter.G4, native TTVideoEngine.play,
            // or invent a next-video URL/ID.
            pager.javaClass.getMethod("setCurrentItem",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).invoke(pager, next, false)
            LogUtil.info("系统媒体: 后台结尾停滞，已请求官方选集切换" +
                " current=$current next=$next total=$count")
            LogUtil.incr("systemMediaSeriesPagerRecovery")
            FeatureTrace.event("background", "series_pager_recovery",
                "requested", "route=native_viewpager2,hasNext=true",
                flow = mediaFlow)
        } catch (e: Throwable) {
            nativeEofRecoveryAttempted = true
            LogUtil.warn("系统媒体: 原生选集补救失败 " +
                e.javaClass.simpleName + ":" + (e.message ?: "unknown"))
            FeatureTrace.event("background", "series_pager_recovery", "fail",
                "errorType=" + e.javaClass.simpleName, flow = mediaFlow)
        }
    }

    /** Explicit notification/media-control pause must never be swallowed by
     * the in-app mini player's short navigation guard. */
    fun isExplicitMediaCommandInProgress(): Boolean = userCommandInProgress

    /** Both home and full-series: protect ONLY the active native engine after
     * process importance confirms a genuine background transition. Never
     * suppress user-requested pause, foreground pause or a preload engine.
     */
    fun shouldSuppressBackgroundEnginePause(candidate: Any?): Boolean {
        // The host can issue TTVideoEngine.pause() while advancing an
        // episode. A blanket ten-minute background guard blocks that native
        // transition and strands the player at duration == position.
        if (episodeMonitor.hasNativeCompletion()) return false
        if (shouldHoldOriginalOnLeave(candidate)) {
            LogUtil.info("系统媒体: 在 Activity.onPause 前保留原生播放引擎")
            LogUtil.incr("systemMediaEarlyEnginePauseHeld")
            FeatureTrace.event("background", "engine_pause", "held",
                "reason=pre-pause", flow = mediaFlow, minIntervalMs = 1000L)
            return true
        }
        if (!enabled || candidate == null || userCommandInProgress ||
            SinglePlaybackOwner.isOwnershipTransition() ||
            !requestedBackgroundPlayback ||
            backgroundPauseGuardEngine.get() !== candidate ||
            videoEngine.get() !== candidate ||
            SystemClock.uptimeMillis() > backgroundPauseGuardUntil) return false
        backgroundPauseSuppressed++
        if (backgroundPauseSuppressed <= 4 || backgroundPauseSuppressed % 20 == 0) {
            LogUtil.info("系统媒体: 已保护当前后台播放器 pause #" +
                backgroundPauseSuppressed)
        }
        LogUtil.incr("systemMediaFeedPauseSuppressed")
        return true
    }
    private var lastVisibleInfoAt = 0L
    private var lastVisibleInfoIdentity = ""
    private var lastVisibleInfoSource = ""
    private var currentEngineVideoIdentity: String? = null
    private var lastNativeMetaProbeAt = 0L
    /** Host DTO updates may arrive in bursts or after autoplay. The shared
     * coordinator never drops the last event inside an arbitrary throttle. */
    fun onNativeSeriesMetadataAvailable() {
        if (!enabled) return
        FeatureTrace.event("media", "native_dto", "observed",
            flow = mediaFlow, minIntervalMs = 2500L)
        mediaRefresh.onNativeDataChanged()
    }
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
    // One logical metadata identity for both feed and full-series playback.
    // Keep artwork/title across episodes only if the native series ID matches.
    private var currentSeriesIdentity: String? = null
    private var currentMetadataPage: String? = null
    private var coverArt: Bitmap? = null
    private var artworkRequestUrl: String? = null
    private var artworkRequestedAt = 0L
    private var artworkFailedUrl: String? = null
    private var artworkFailedAt = 0L
    private var artworkGeneration = 0L
    private var lastArtworkDiagnosticAt = 0L
    private var lastMetadataSignature: String? = null
    private var lastMetadataArt: Bitmap? = null
    private var metadataSyncGeneration = 0L
    private val delayedMetadataSync = Runnable {
        if (!enabled || session == null) return@Runnable
        if (seriesTitle == null && episodeTitle == null && coverArt == null)
            return@Runnable
        // The ShortSeriesActivity's model often arrives AFTER the initial
        // engine.play / notification. Publish the settled metadata once more,
        // keeping the same MediaSession and notification ID.
        updateSessionMetadata(force = true)
        updateSessionState(knownPlaying)
        if (reportedCard) updateMediaNotification(knownPlaying)
        try {
            val snapshot = session?.controller?.metadata
            val publishedTitle = snapshot?.getString(
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val publishedEpisode = snapshot?.getString(
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            LogUtil.info("系统媒体: 媒体信息回读" +
                " titleMatches=" + (publishedTitle ==
                    mediaPresentation.title(seriesTitle)) +
                " episodeMatches=" + (publishedEpisode ==
                    mediaPresentation.subtitle(episodeTitle, knownPlaying)) +
                " artwork=" + (coverArt != null) +
                " mediaActive=" + (session?.isActive == true))
            LogUtil.incr("systemMediaMetadataReadback")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: MediaSession 元数据回读失败: " + e.message)
        }
    }
    private var progressPolling = false
    private var lastProgressDiagnosticAt = 0L
    private val episodeMonitor = BackgroundEpisodeMonitor()

    /**
     * APK-verified completion stages:
     * VideoController.onCompletion(engine) ->
     * LayerHostMediaLayout.onVideoCompleted(inquirer, entity) ->
     * VideoContext.onVideoCompleted(inquirer, entity) ->
     * IVideoEngineStateListener.onVideoCompleted.
     * Log AFTER chain.proceed() so native autoplay behavior stays untouched.
     */
    fun onHostCompletionStage(stage: String, candidate: Any?) {
        if (!enabled || candidate == null) return
        val matched = when (stage) {
            "controller" -> candidate === controller.get()
            "layer" -> candidate === layer.get()
            "context" -> candidate === videoContext.get()
            else -> false
        }
        val engine = try {
            candidate.javaClass.methods.firstOrNull { method ->
                method.name == "getVideoEngine" && method.parameterCount == 0
            }?.invoke(candidate)
        } catch (_: Throwable) { null }
        val sameEngine = engine != null && engine === videoEngine.get()
        val currentPage = foregroundActivity.get()
        LogUtil.info("系统媒体: APK 原生完成事件阶段 stage=$stage" +
            " matched=$matched sameEngine=$sameEngine" +
            " background=$requestedBackgroundPlayback" +
            " page=" + (currentPage?.javaClass?.simpleName ?: "none"))
        FeatureTrace.event("background", "completion_stage", "observed",
            "stage=" + stage + ",owner=" + matched +
                ",sameEngine=" + sameEngine +
                ",background=" + requestedBackgroundPlayback,
            flow = mediaFlow)
    }

    /** Observe the host's registrations without changing any listener.
     * This distinguishes an absent completion callback from a listener
     * unregistered by the ShortSeriesActivity lifecycle.
     */
    fun onHostVideoListener(action: String, host: Any?, listener: Any?) {
        if (!enabled || host == null || listener == null) return
        if (host !== videoContext.get()) return
        val ownerName = listener.javaClass.name.take(100)
        LogUtil.info("系统媒体: 原生短剧播放监听器 $action" +
            " listenerClass=$ownerName background=$requestedBackgroundPlayback")
        FeatureTrace.event("background", "host_listener", "observed",
            "action=" + action + ",shortSeries=" +
                ownerName.contains("shortvideo") +
                ",background=" + requestedBackgroundPlayback,
            flow = mediaFlow, minIntervalMs = 200L)
    }

    fun onSeriesFragmentStopped(fragment: Any?) {
        if (!enabled || fragment == null) return
        val currentIndex = reflectNumber(fragment, "getCurrentPosition")?.toLong()
        val listSize = try {
            (fragment.javaClass.methods.firstOrNull {
                it.name == "getDataList" && it.parameterCount == 0
            }?.invoke(fragment) as? List<*>)?.size
        } catch (_: Throwable) { null }
        FeatureTrace.event("background", "series_fragment_stop", "observed",
            "hasIndex=" + (currentIndex != null) +
                ",hasItems=" + (listSize != null && listSize > 0) +
                ",prePauseCandidate=" +
                backgroundTransition.isProvisional() +
                ",background=" + requestedBackgroundPlayback,
            flow = mediaFlow)
    }

    /** Called AFTER the host's original TTVideoEngine callback proceeds.
     * A completion event is evidence only; never replay the video here.
     */
    fun onNativeCompletion(candidate: Any?) {
        if (!enabled || candidate == null || videoEngine.get() !== candidate ||
            episodeMonitor.hasNativeCompletion()) return
        episodeMonitor.onNativeCompletion()
        nativeEofEngine = WeakReference(candidate)
        nativeEofAt = SystemClock.uptimeMillis()
        nativeEofPauseEpoch = explicitPauseEpoch
        nativeEofGeneration++
        nativeEofRecoveryScheduled = false
        nativeEofRecoveryAttempted = false
        LogUtil.info("系统媒体: 宿主原生 onCompletion 已触发" +
            " background=$requestedBackgroundPlayback")
        FeatureTrace.event("background", "native_completion", "observed",
            "sameEngine=true", flow = mediaFlow)
    }

    private val timelineTicker = object : Runnable {
        override fun run() {
            progressPolling = false
            if (!enabled || session == null) return
            readCurrentTimeline()
            val stuck = episodeMonitor.onSample(positionMs, durationMs,
                backgroundActive = requestedBackgroundPlayback,
                playing = knownPlaying, nowMs = SystemClock.uptimeMillis())
            if (stuck != null) {
                LogUtil.warn("系统媒体: 后台播放到结尾但宿主未切下一集" +
                    " reason=$stuck durationMs=$durationMs")
                LogUtil.incr("systemMediaEpisodeEndStall")
                FeatureTrace.event("background", "episode_end_stall", "fail",
                    "reason=" + stuck + ",nativeDuration=true", flow = mediaFlow)
            }
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
     * Artwork MUST come from official *series-level* Saas fields
     * (episodesCover/smallEpisodesCover), never from a playing video frame,
     * episodeCover, generic cover or UI ImageViews.
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
        if (url == artworkFailedUrl &&
            now - artworkFailedAt in 0L..15_000L) return
        if (url == artworkRequestUrl && (coverArt != null ||
            now - artworkRequestedAt < 90_000L)) return
        artworkRequestUrl = url
        artworkRequestedAt = now
        val generation = artworkGeneration
        val ownerEngine = videoEngine.get()
        LogUtil.info("系统媒体: 已发现原生系列海报，准备异步读取 reason=$reason")
        LogUtil.incr("systemMediaArtworkRequested")
        val requestFlow = mediaFlow
        FeatureTrace.event("artwork", "request", "started",
            "origin=native,hasUrl=true", flow = requestFlow)
        MediaArtworkLoader.load(ctx, url) { bitmap, source ->
            if (!enabled || generation != artworkGeneration ||
                ownerEngine !== videoEngine.get() ||
                artworkRequestUrl != url) {
                FeatureTrace.event("artwork", "callback", "stale",
                    flow = requestFlow, minIntervalMs = 1500L)
                return@load
            }
            if (bitmap == null) {
                artworkFailedUrl = url
                artworkFailedAt = SystemClock.uptimeMillis()
                // A failed request must be retried after bounded backoff,
                // not suppressed for 90 seconds as if it had succeeded.
                artworkRequestUrl = null
                LogUtil.warn("系统媒体: 封面加载失败 source=$source")
                LogUtil.incr("systemMediaArtworkUnavailable")
                FeatureTrace.event("artwork", "decode", "fail",
                    "source=" + source, flow = requestFlow)
            } else if (!bitmap.isRecycled) {
                artworkFailedUrl = null
                artworkFailedAt = 0L
                coverArt = bitmap
                updateSessionMetadata()
                updateMediaNotification(knownPlaying)
                LogUtil.info("系统媒体: 媒体封面已写入 MediaSession source=$source" +
                    " size=" + bitmap.width + "x" + bitmap.height)
                LogUtil.incr("systemMediaArtworkApplied")
                FeatureTrace.event("artwork", "commit", "ok",
                    "source=" + source + ",width=" + bitmap.width +
                        ",height=" + bitmap.height, flow = requestFlow)
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

    private fun updateSessionMetadata(force: Boolean = false) {
        val media = session ?: return
        val artwork = coverArt
        val snapshot = MediaCardSnapshot(
            mediaPresentation.title(seriesTitle),
            mediaPresentation.subtitle(episodeTitle, knownPlaying),
            durationMs, artwork)
        val signature = snapshot.signature
        if (!force && signature == lastMetadataSignature &&
            artwork === lastMetadataArt) return
        try {
            media.setMetadata(snapshot.toPlatformMetadata())
            FeatureTrace.event("media", "session_metadata", "committed",
                "title=" + (seriesTitle != null) +
                ",episode=" + (episodeTitle != null) +
                ",poster=" + (artwork != null) +
                ",duration=" + (durationMs > 0L), flow = mediaFlow)
            lastMetadataSignature = signature
            lastMetadataArt = artwork
            if (!force && (seriesTitle != null ||
                episodeTitle != null || artwork != null)) {
                metadataSyncGeneration++
                handler.removeCallbacks(delayedMetadataSync)
                handler.postDelayed(delayedMetadataSync, 850L)
            }
            LogUtil.info("系统媒体: 媒体信息已更新 title=" +
                (seriesTitle != null) + " episode=" + (episodeTitle != null) +
                " cover=" + (artwork != null) + " durationMs=$durationMs" +
                " delayed=$force")
            if (!force) LogUtil.incr("systemMediaMetadataUpdate")
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 更新媒体信息失败: " + e.message)
        }
    }

    /**
     * A final-episode autoplay can switch to the NEXT drama in the SAME
     * Activity and sometimes the SAME native TTVideoEngine. Old metadata must
     * disappear before the new DTO is available; otherwise Android continues
     * showing the previous poster/title indefinitely.
     *
     * Only preserve series artwork when the new video is positively matched
     * to the exact same series ID. A missing/unknown ID is not evidence.
     */
    private fun invalidateForVideoTransition(sameSeries: Boolean, reason: String) {
        episodeMonitor.reset()
        nativeEofGeneration++
        nativeEofEngine.clear()
        nativeEofAt = 0L
        nativeEofRecoveryScheduled = false
        nativeEofRecoveryAttempted = false
        mediaFlow = FeatureTrace.newFlow("media", reason)
        FeatureTrace.event("playback", "video_transition", "started",
            "sameSeries=" + sameSeries, flow = mediaFlow)
        mediaPresentation.onVideoTransition(sameSeries)
        episodeTitle = null
        if (!sameSeries) {
            seriesTitle = null
            coverArt = null
            currentSeriesIdentity = null
        }
        durationMs = -1L
        positionMs = -1L
        bufferedMs = -1L
        actualPlaybackSpeed = 1f
        artworkGeneration++
        artworkRequestUrl = null
        artworkRequestedAt = 0L
        lastMetadataArt = null
        lastMetadataSignature = null
        metadataSyncGeneration++
        handler.removeCallbacks(delayedMetadataSync)
        lastVisibleInfoAt = 0L
        lastVisibleInfoIdentity = ""
        lastVisibleInfoSource = ""
        lastNativeMetaProbeAt = 0L

        // Publish the cleared record NOW: neither notification nor MediaSession
        // should retain last drama's title/poster while the new drama loads.
        updateSessionMetadata(force = true)
        if (reportedCard) updateMediaNotification(knownPlaying)
        mediaRefresh.onPlaybackTransition(reason)
        LogUtil.info("系统媒体: 剧集切换已清理旧元数据" +
            " reason=$reason sameSeries=$sameSeries" +
            " retainedPoster=" + (coverArt != null))
        LogUtil.incr("systemMediaDramaTransitionInvalidated")
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

    /**
     * A single metadata resolution pipeline serves home feed and full-series.
     * The two screens only contribute different sources; MediaSession,
     * notification, artwork and timeline are ALWAYS published identically.
     * Read UI labels only from the focused currently playing Activity.
     */
    private fun resolveCurrentMedia(
        activity: Activity?, engine: Any, reason: String, includeUi: Boolean
    ): FullSeriesMediaInfo.Info {
        val isFullSeries = activity?.javaClass?.name ==
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        val full = if (isFullSeries) FullSeriesMediaInfo.read(activity, engine)
            else FullSeriesMediaInfo.Info()
        val home = if (!isFullSeries) HomeFeedMediaInfo.read(activity, engine)
            else FullSeriesMediaInfo.Info()
        val observed = FullSeriesMediaInfo.observedForEngine(engine)
        val play = NativePlayEntityInfo.readForEngine(engine,
            videoContext.get(), controller.get(), layer.get())
        val ui = if (includeUi && activity?.hasWindowFocus() == true)
            NativeMediaInfo.read(activity) else NativeMediaInfo.Info()
        // The old ShortSeriesActivity's TextViews/PlayEntity can lag behind
        // the new engine after final-episode autoplay. FullSeriesMediaInfo
        // explicitly marks an engine video ID with no matching Saas model.
        // In that state NONE of those unrelated old UI fallbacks are trusted.
        val seriesUnverified = isFullSeries &&
            full.source == "series-video-unverified"
        val videoIdOnly = isFullSeries &&
            full.source == "series-video-id-only"
        val observedSameSeries = !isFullSeries ||
            full.seriesId == null || observed.seriesId == full.seriesId
        // In full-series playback, the old Activity's on-screen text can
        // linger for one frame (or indefinitely in the background) after
        // final-episode autoplay. Only media tied to the playing video/series
        // is safe: do not re-import that stale UI label when the new engine
        // already has a verified series identity.
        val trustedObservedTitle =
            if (observedSameSeries) observed.title else null
        val trustedObservedPoster =
            if (observedSameSeries) observed.artworkUrl else null
        val uiAllowed = !isFullSeries || full.seriesId == null
        val title = when {
            seriesUnverified -> null
            videoIdOnly -> full.title
            isFullSeries -> full.title ?: trustedObservedTitle ?:
                play.title ?: if (uiAllowed) ui.title else null
            else -> home.title ?: observed.title ?: play.title ?: ui.title
        }
        val episode = when {
            seriesUnverified -> null
            videoIdOnly -> full.episode
            else -> selectEpisodeLabel(full.episode, play.episode ?:
                if (uiAllowed) ui.episode else null)
        }
        // Only true native series posters: never use a playing frame still or
        // the video Surface as Android MediaMetadata art.
        val artworkUrl = when {
            seriesUnverified -> null
            videoIdOnly -> full.artworkUrl
            else -> full.artworkUrl ?: home.artworkUrl ?:
                trustedObservedPoster ?: play.artworkUrl
        }
        val resolvedSeriesId = if (seriesUnverified || videoIdOnly) null
            else full.seriesId ?: home.seriesId ?: observed.seriesId
        if (resolvedSeriesId != null && currentSeriesIdentity != null &&
            resolvedSeriesId != currentSeriesIdentity) {
            // Only the current playing model may confirm a changed series.
            invalidateForVideoTransition(false, "verified-series-change")
        }
        if (resolvedSeriesId != null) currentSeriesIdentity = resolvedSeriesId
        if (activity != null) currentMetadataPage =
            if (isFullSeries) "series" else "feed"
        requestNativeArtwork(artworkUrl, "unified:$reason")
        var changed = false
        if (title != null && title != seriesTitle) {
            seriesTitle = title
            changed = true
        }
        if (episode != null && episode != episodeTitle) {
            episodeTitle = episode
            changed = true
        }
        val source = when {
            seriesUnverified || videoIdOnly -> full.source
            full.title != null || full.episode != null ||
                full.artworkUrl != null -> full.source
            home.title != null || home.artworkUrl != null -> home.source
            observed.title != null || observed.artworkUrl != null ->
                observed.source
            play.title != null || play.episode != null ||
                play.artworkUrl != null -> play.source
            else -> ui.source
        }
        val identity = (activity?.javaClass?.simpleName ?: "?") + "|" +
            (title ?: "") + "|" + (episode ?: "")
        if (includeUi && (identity != lastVisibleInfoIdentity ||
            source != lastVisibleInfoSource)) {
            lastVisibleInfoIdentity = identity
            lastVisibleInfoSource = source
            LogUtil.info("系统媒体: 统一媒体信息采集" +
                " page=" + (currentMetadataPage ?: "?") +
                " source=$source title=" + (title != null) +
                " episode=" + (episode != null) +
                " seriesId=" + (resolvedSeriesId != null) +
                " observedTitle=" + (observed.title != null) +
                " homeCurrentItem=" + (home.seriesId != null) +
                " homePosterUrl=" + (home.artworkUrl != null) +
                " officialPoster=" + (coverArt != null) +
                " nativePosterUrl=" + (artworkUrl != null))
            LogUtil.incr("systemMediaUnifiedSnapshot")
            FeatureTrace.event("media", "resolution",
                if (title != null) "ok" else "degraded",
                "page=" + (currentMetadataPage ?: "unknown") +
                    ",source=" + source +
                    ",title=" + (title != null) +
                    ",episode=" + (episode != null) +
                    ",series=" + (resolvedSeriesId != null) +
                    ",posterUrl=" + (artworkUrl != null),
                flow = mediaFlow)
        }
        mediaPresentation.onResolved(
            verified = !seriesUnverified &&
                (resolvedSeriesId != null || source == "series-video-id-only" ||
                    source == "home-native-current-item" ||
                    source == "native-model-video-id"),
            title = title)
        if (changed) {
            LogUtil.info("系统媒体: 统一媒体元数据已绑定 reason=$reason" +
                " source=$source title=" + (title != null) +
                " episode=" + (episode != null))
            updateSessionMetadata()
            if (reportedCard) updateMediaNotification(knownPlaying)
        }
        return full
    }

    private fun captureNativePlayEntity(reason: String) {
        if (!enabled) return
        val engine = videoEngine.get() ?: return
        val now = SystemClock.uptimeMillis()
        if (reason == "periodic" && now - lastNativeMetaProbeAt < 4000L) return
        lastNativeMetaProbeAt = now
        val activity = foregroundActivity.get()
        val videoId = try {
            engine.javaClass.getMethod("getVideoID").invoke(engine)?.toString()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        if (videoId != null && currentEngineVideoIdentity != null &&
            videoId != currentEngineVideoIdentity) {
            episodeMonitor.reset()
            // A reused TTVideoEngine can switch to the next episode or drama
            // without replacing the engine instance.
            FullSeriesMediaInfo.beginEngineTransition(
                activity, engine, currentSeriesIdentity)
            val incoming = when (activity?.javaClass?.name) {
                "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity" ->
                    FullSeriesMediaInfo.read(activity, engine)
                "com.dragon.read.pages.main.MainFragmentActivity" ->
                    HomeFeedMediaInfo.read(activity, engine)
                else -> FullSeriesMediaInfo.observedForEngine(engine)
            }
            val sameSeries = incoming.seriesId != null &&
                incoming.seriesId == currentSeriesIdentity
            invalidateForVideoTransition(sameSeries, "same-engine-video-id")
            LogUtil.info("系统媒体: 同引擎切换视频 ID" +
                " sameSeries=$sameSeries posterRetained=" + (coverArt != null))
        }
        if (videoId != null) currentEngineVideoIdentity = videoId
        resolveCurrentMedia(activity, engine, "native:$reason",
            includeUi = reason != "periodic")
    }

    private fun captureVisibleMediaInfo(activity: Activity?) {
        if (!enabled || activity == null) return
        val isFullSeries = activity.javaClass.name ==
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        if (!isFullSeries && !activity.hasWindowFocus()) return
        val engine = videoEngine.get() ?: return
        resolveCurrentMedia(activity, engine, "visible", includeUi = true)
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
        ensurePrePauseObserver(ctx)
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
                // Switching episodes can replace TTVideoEngine even within
                // the SAME drama. Preserve its official poster and title
                // until a native series ID actually changes.
                val activity = foregroundActivity.get()
                // Register the engine handoff BEFORE probing native DTOs.
                // Later host-consumer callbacks can then be correlated with
                // this new engine without trusting recycled presenter models.
                FullSeriesMediaInfo.beginEngineTransition(
                    activity, candidate, currentSeriesIdentity)
                val incomingSeries = when (activity?.javaClass?.name) {
                    "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity" ->
                        FullSeriesMediaInfo.read(activity, candidate).seriesId
                    "com.dragon.read.pages.main.MainFragmentActivity" ->
                        HomeFeedMediaInfo.read(activity, candidate).seriesId
                    else -> FullSeriesMediaInfo.observedForEngine(candidate).seriesId
                }
                val sameSeries = incomingSeries != null &&
                    incomingSeries == currentSeriesIdentity
                invalidateForVideoTransition(sameSeries, "new-native-engine")
                lastProgressDiagnosticAt = 0L
                currentEngineVideoIdentity = null
                if (backgroundPauseGuardEngine.get() !== candidate) {
                    backgroundPauseGuardEngine.clear()
                    backgroundPauseGuardUntil = 0L
                    backgroundPauseSuppressed = 0
                }
                LogUtil.info("系统媒体: 原生播放器实例切换" +
                    " sameSeries=$sameSeries posterRetained=" + (coverArt != null))
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
        FeatureTrace.event("playback", "engine_command", "observed",
            "action=" + action + ",page=" + (currentMetadataPage ?: "unknown"),
            flow = mediaFlow, minIntervalMs = 250L)
        // Track the actual active engine's pause on BOTH player pages.
        // Recovery is allowed only after ActivityManager confirms background.
        val playerPage = foregroundActivity.get()?.javaClass?.name
        if (action == "pause" && (playerPage?.contains("MainFragmentActivity") == true ||
            playerPage?.contains("ShortSeriesActivity") == true)) {
            lastLifecyclePauseAt = SystemClock.uptimeMillis()
            lastLifecyclePauseEngine = WeakReference(candidate)
            lastLifecyclePauseWasPlaying = knownPlaying
            LogUtil.info("系统媒体: 原生播放器生命周期暂停" +
                " page=" + (currentMetadataPage ?: "?") +
                " wasPlaying=" + lastLifecyclePauseWasPlaying)
        }
        if (action == "play") {
            lastLifecyclePauseWasPlaying = false
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

    private fun isVideoActivity(activity: Activity?): Boolean =
        activity?.javaClass?.name in setOf(
            "com.dragon.read.pages.main.MainFragmentActivity",
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        )

    private fun rememberExitSnapshot(activity: Activity, reason: String): ExitSnapshot? {
        if (!enabled || !isVideoActivity(activity) ||
            foregroundActivity.get() !== activity) return null
        val engine = videoEngine.get() ?: return null
        val now = SystemClock.uptimeMillis()
        val recent = pendingExit?.takeIf {
            it.activity.get() === activity && it.engine.get() === engine &&
                now - it.at in 0L..EXIT_SNAPSHOT_WINDOW_MS
        }
        val native = nativeIsPlaying(engine)
        val recentNativePause = lastLifecyclePauseEngine.get() === engine &&
            lastLifecyclePauseWasPlaying &&
            now - lastLifecyclePauseAt in 0L..2000L
        // A confirmed native=false with no pending/recorded lifecycle pause
        // means the user may already have paused: DO NOT auto-play.
        val wasPlaying = native == true ||
            (native == null && knownPlaying) ||
            recentNativePause || recent?.wasPlaying == true ||
            (lastUserLeaveEngine.get() === engine &&
                lastUserLeaveWasPlaying &&
                now - lastUserLeaveAt in 0L..3000L)
        val snapshot = ExitSnapshot(
            WeakReference(activity), WeakReference(engine),
            recent?.at ?: now, wasPlaying,
            native ?: recent?.nativeAtCapture, reason,
            recent?.explicitPauseEpochAtCapture ?: explicitPauseEpoch,
            WeakReference(videoContext.get()),
            recent?.stopped == true
        )
        pendingExit = snapshot
        LogUtil.info("系统媒体: 离开播放器快照 reason=$reason" +
            " engine=true native=$native playing=$wasPlaying" +
            " hint=" + (recent != null || lastUserLeaveWasPlaying))
        LogUtil.incr("systemMediaExitSnapshot")
        FeatureTrace.event("background", "exit_snapshot", "captured",
            "wasPlaying=" + wasPlaying + ",nativeKnown=" + (native != null),
            flow = mediaFlow, minIntervalMs = 1000L)
        return snapshot
    }

    private fun queueBackgroundChecks(snapshot: ExitSnapshot, reason: String) {
        for (delay in longArrayOf(190L, 490L, 950L, 1750L, 3150L)) {
            handler.postDelayed({
                verifyBackgroundExit(snapshot, reason + "/" + delay)
            }, delay)
        }
    }

    /**
     * Do not use Home-key hints to infer background. Android can deliver
     * onPause and onStop in different orders while opening a full-series
     * Activity inside Hongguo. A new Hongguo onResume cancels this snapshot.
     */
    private fun verifyBackgroundExit(snapshot: ExitSnapshot, source: String) {
        if (!enabled || pendingExit !== snapshot) return
        val activity = snapshot.activity.get() ?: run {
            pendingExit = null
            return
        }
        if (foregroundActivity.get() !== activity) {
            pendingExit = null
            backgroundTransition.cancel()
            LogUtil.info("系统媒体: 已进入其他红果页面，不启动后台恢复")
            return
        }
        val state = ActivityManager.RunningAppProcessInfo()
        try { ActivityManager.getMyMemoryState(state) } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 无法读取进程重要性 " + e.javaClass.simpleName)
            return
        }
        val noWindowFocus = !activity.hasWindowFocus()
        val elapsed = SystemClock.uptimeMillis() - snapshot.at
        // A native full-series Activity may pause/stop before the next
        // in-app Activity reaches onResume. Wait for the task animation to
        // finish before accepting onStop alone as proof of app background.
        val stoppedBackground = snapshot.stopped && noWindowFocus &&
            elapsed >= 1050L &&
            state.importance >=
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        val isBackground =
            state.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ||
                stoppedBackground
        LogUtil.info("系统媒体: 多路径后台检测 source=$source" +
            " importance=" + state.importance +
            " stopped=" + snapshot.stopped +
            " elapsed=$elapsed focused=" + !noWindowFocus +
            " confirmed=$isBackground")
        if (!isBackground) return
        pendingExit = null  // Exactly one resolution, even if multiple callbacks fire.
        val engine = snapshot.engine.get()
        if (!snapshot.wasPlaying || engine == null || videoEngine.get() !== engine ||
            snapshot.explicitPauseEpochAtCapture != explicitPauseEpoch ||
            !isVideoActivity(activity)) {
            LogUtil.info("系统媒体: 已后台但未恢复，无有效的离开前播放快照" +
                " wasPlaying=" + snapshot.wasPlaying +
                " sameEngine=" + (engine != null && videoEngine.get() === engine))
            LogUtil.incr("systemMediaBackgroundSkip")
            return
        }
        if (SinglePlaybackOwner.isOwnershipTransition()) {
            backgroundTransition.cancel()
            LogUtil.info("系统媒体: 播放所有权正在切换，取消后台恢复")
            return
        }
        val now = SystemClock.uptimeMillis()
        val transitionConfirmed = backgroundTransition.confirm(
            activity, engine, explicitPauseEpoch, now)
        FeatureTrace.event("background", "transition", "confirmed",
            "prePause=" + transitionConfirmed +
                ",hint=" + lastUserLeaveWasPlaying +
                ",heldCallbacks=" + backgroundTransition.heldCount(),
            flow = mediaFlow)
        requestedBackgroundPlayback = true
        leaveAt = now
        resumeAttempts = 0
        backgroundResumeBudget = 0
        backgroundPauseGuardEngine = WeakReference(engine)
        // Only suppress pauses associated with the actual app-leave,
        // NOT normal episode transitions throughout background playback.
        backgroundPauseGuardUntil = now + BACKGROUND_PAUSE_GUARD_MS
        backgroundPauseSuppressed = 0
        setNativeBackgroundAllowance(true)
        val current = nativeIsPlaying(engine)
        LogUtil.info("系统媒体: 已确认其他 App 前台，保护离开前原生播放器" +
            " via=$source native=$current")
        LogUtil.incr("systemMediaBackgroundConfirmed")
        captureVisibleMediaInfo(activity)
        readCurrentTimeline()
        val lifecyclePaused = lastLifecyclePauseEngine.get() === engine &&
            lastLifecyclePauseWasPlaying &&
            now - lastLifecyclePauseAt in 0L..EXIT_SNAPSHOT_WINDOW_MS &&
            snapshot.explicitPauseEpochAtCapture == explicitPauseEpoch
        val hostHeld = BackgroundHandoffPolicy.shouldTreatUnknownNativeStateAsRunning(
            confirmedBackground = true,
            heldNativePauseOrLifecycle = transitionConfirmed &&
                backgroundTransition.heldFor(engine),
            sameEngine = videoEngine.get() === engine,
            explicitPauseUnchanged =
                snapshot.explicitPauseEpochAtCapture == explicitPauseEpoch,
            hasVerifiedNativePause = lifecyclePaused)
        if (current == null && hostHeld) {
            // Native isPlaying() is missing for this TTVideoEngine build.
            // If the real original engine was not paused, do NOT call the
            // low-level play() again: that path leaves the host's autoplay
            // controller behind and stalls forever on the last video frame.
            LogUtil.info("系统媒体: 宿主播放器完整保活，不重复低层 play()" +
                " heldEvents=" + backgroundTransition.heldCount())
            FeatureTrace.event("background", "native_lifecycle_handoff",
                "preserved", "engine=true,playReplay=false", flow = mediaFlow)
            updateMediaNotification(true)
            scheduleTimelinePoll(400L)
            // Even with native lifecycle protection, some host versions may
            // pause audio through a second route. Check measured progress
            // instead of assuming TTVideoEngine.isPlaying() (unsupported here).
            // One bounded host-level resume is safer than replaying the bare
            // engine with no autoplay controller attached.
            val checkpoint = reflectNumber(engine,
                "getCurrentPlaybackTimeAsync")?.toLong()
            val originalContext = videoContext.get()
            handler.postDelayed({
                if (!enabled || !requestedBackgroundPlayback ||
                    videoEngine.get() !== engine ||
                    videoContext.get() !== originalContext ||
                    snapshot.explicitPauseEpochAtCapture != explicitPauseEpoch ||
                    !knownPlaying || userCommandInProgress) return@postDelayed
                val after = reflectNumber(engine,
                    "getCurrentPlaybackTimeAsync")?.toLong()
                val progressed = checkpoint != null && after != null &&
                    after - checkpoint >= 400L
                if (progressed) {
                    FeatureTrace.event("background", "native_progress", "ok",
                        "elapsedCheckMs=2200", flow = mediaFlow)
                    return@postDelayed
                }
                val nearEnd = durationMs in 5000L..86_400_000L &&
                    (after ?: positionMs) >= durationMs - 2000L
                if (nearEnd || checkpoint == null || after == null ||
                    originalContext == null) {
                    FeatureTrace.event("background", "native_progress",
                        "degraded", "nearEnd=" + nearEnd +
                            ",hasContext=" + (originalContext != null),
                        flow = mediaFlow)
                    return@postDelayed
                }
                try {
                    val hostPlay = originalContext.javaClass.methods.firstOrNull {
                        it.name == "play" && it.parameterCount == 0 &&
                            it.returnType == Void.TYPE
                    }
                    if (hostPlay != null) {
                        hostPlay.invoke(originalContext)
                        LogUtil.warn("系统媒体: 宿主播放器未前进，已通过原 VideoContext 恢复一次")
                        FeatureTrace.event("background", "native_progress_recovery",
                            "requested", "route=video_context", flow = mediaFlow)
                    }
                } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: 宿主级恢复失败 " +
                        e.javaClass.simpleName)
                    FeatureTrace.event("background", "native_progress_recovery",
                        "fail", "errorType=" + e.javaClass.simpleName,
                        flow = mediaFlow)
                }
            }, 2200L)
            return
        }
        if (current == true) {
            // A successful Home-key-like transition: keep the engine alive.
            updateMediaNotification(true)
            LogUtil.info("系统媒体: 原生视频未暂停，无需重复启动")
            return
        }
        // TTVideoEngine in 7.3.5.32 has no reliable public isPlaying():
        // diagnostic shows native=null even while it is audibly playing.
        // The engine's intercepted pause event is definitive evidence
        // that the exact snapshot engine was stopped by Activity lifecycle.
        // Recover once ONLY for that verified pause, not generic unknown.
        if (current == null && !lifecyclePaused) {
            LogUtil.warn("系统媒体: 引擎状态未知且无同引擎生命周期暂停证据，取消恢复")
            LogUtil.incr("systemMediaBackgroundStateUnknown")
            FeatureTrace.event("background", "native_state", "degraded",
                "lifecyclePause=false", flow = mediaFlow, minIntervalMs = 2000L)
            return
        }
        LogUtil.info("系统媒体: 后台恢复依据" +
            " native=$current lifecyclePause=$lifecyclePaused" +
            " beforeLeave=${snapshot.wasPlaying} sameEngine=true")
        // The full-series native business player is x05.w, not VideoShop.
        // Its resume() re-enters the same host-owned controller and preserves
        // vm4.a.U1 / adapter.G4() autoplay. Fallback to VideoContext.play()
        // only if it ACTUALLY wraps this exact TTVideoEngine instance.
        val nativeSeries = nativeSeriesPlayer.get()?.takeIf { player ->
            seriesPlayerEngine(player) === engine &&
                activity.javaClass.name ==
                    "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        }
        val candidateContext = snapshot.videoContext.get()
        val contextEngine = try {
            candidateContext?.javaClass?.methods?.firstOrNull {
                it.name == "getVideoEngine" && it.parameterCount == 0
            }?.invoke(candidateContext)
        } catch (_: Throwable) { null }
        val nativeContext = candidateContext?.takeIf {
            it === videoContext.get() && contextEngine === engine
        }
        val host = nativeSeries ?: nativeContext
        val hostMethod = if (nativeSeries != null) "resume" else "play"
        val hostPlay = host?.javaClass?.methods?.firstOrNull {
            it.name == hostMethod && it.parameterCount == 0 &&
                it.returnType == Void.TYPE
        }
        if (hostPlay == null) {
            LogUtil.warn("系统媒体: 已确认后台但无匹配的原生业务恢复入口;" +
                " 不调用裸 TTVideoEngine.play() 避免损坏自动续集")
            LogUtil.incr("systemMediaBackgroundNoHostPlay")
            FeatureTrace.event("background", "resume_command", "degraded",
                "hasNativeSeriesPlayer=" + (nativeSeries != null) +
                    ",hasMatchingVideoContext=" + (nativeContext != null),
                flow = mediaFlow)
            return
        }
        try {
            hostPlay.invoke(host)
            LogUtil.info("系统媒体: 经原生业务播放器恢复同一视频控制链" +
                " route=" + (if (nativeSeries != null) "x05.w.resume" else
                    "VideoContext.play"))
            LogUtil.incr("systemMediaAppSwitchRecovery")
            FeatureTrace.event("background", "resume_command", "sent",
                "sameEngine=true,route=" +
                    (if (nativeSeries != null) "x05.w" else "video_context"),
                flow = mediaFlow)
            handler.postDelayed({
                if (enabled && requestedBackgroundPlayback &&
                    videoEngine.get() === engine &&
                    snapshot.explicitPauseEpochAtCapture == explicitPauseEpoch) {
                    val confirmed = nativeIsPlaying(host)
                    LogUtil.info("系统媒体: 原生业务播放器恢复校验 playing=$confirmed")
                    FeatureTrace.event("background", "resume_check",
                        if (confirmed == true) "ok" else "degraded",
                        "nativeKnown=" + (confirmed != null),
                        flow = mediaFlow)
                    if (confirmed != null) onNativePlayback(
                        confirmed, "app-switch-host-recovery-check",
                        host)
                }
            }, 430L)
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 原生 VideoContext 恢复失败 " +
                e.javaClass.simpleName)
            LogUtil.incr("systemMediaAppSwitchRecoveryFailed")
            FeatureTrace.event("background", "resume_command", "fail",
                "errorType=" + e.javaClass.simpleName, flow = mediaFlow)
        }
    }

    fun onActivityPaused(activity: Activity?) {
        if (!enabled || activity == null ||
            activity.packageName != appContext?.packageName) return
        if (isVideoActivity(activity)) lastPlayerActivityPauseAt =
            SystemClock.uptimeMillis()
        // Must precede discoverPlayingVideo (which can itself report a pause).
        val snapshotBefore = rememberExitSnapshot(activity, "Activity.onPause:before")
        discoverPlayingVideo(activity, "beforeActivityPause")
        captureVisibleMediaInfo(activity)
        val snapshot = snapshotBefore ?: rememberExitSnapshot(
            activity, "Activity.onPause:after")
        LogUtil.info("系统媒体: Activity.onPause " +
            activity.javaClass.simpleName + " knownPlaying=$knownPlaying" +
            " snapshotPlaying=" + (snapshot?.wasPlaying == true) +
            " engine=" + (snapshot?.engine?.get() != null))
        if (snapshot != null) queueBackgroundChecks(snapshot, "onPause")
    }

    fun onActivityStopped(activity: Activity?) {
        if (!enabled || activity == null ||
            activity.packageName != appContext?.packageName) return
        LogUtil.info("系统媒体: Activity.onStop " + activity.javaClass.simpleName)
        val snapshot = pendingExit?.takeIf {
            it.activity.get() === activity &&
                SystemClock.uptimeMillis() - it.at in 0L..EXIT_SNAPSHOT_WINDOW_MS
        }
        if (snapshot != null) {
            snapshot.stopped = true
            // onStop can arrive after the 500ms importance probe, or after
            // a recent-app animation; the next main-loop tick confirms it.
            handler.post { verifyBackgroundExit(snapshot, "onStop/post") }
            handler.postDelayed({
                verifyBackgroundExit(snapshot, "onStop/250")
            }, 250L)
        }
        discoverPlayingVideo(activity, "beforeActivityStop")
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

    private fun readNativeBackgroundAllowance(host: Any): Boolean? {
        var type: Class<*>? = host.javaClass
        while (type != null && type != Any::class.java) {
            try {
                val flag = type.getDeclaredField("canPlayBackground")
                flag.isAccessible = true
                return flag.get(host) as? Boolean
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    private fun setNativeBackgroundAllowance(allow: Boolean) {
        val host = layer.get() ?: try {
            videoContext.get()?.javaClass?.getMethod("getLayerHostMediaLayout")
                ?.invoke(videoContext.get())
        } catch (_: Throwable) { null } ?: run {
            FeatureTrace.event("background", "native_allowance", "degraded",
                "hasLayer=false", flow = mediaFlow, minIntervalMs = 4000L)
            return
        }
        try {
            if (!backgroundFlagApplied ||
                originalBackgroundHost.get() !== host) {
                // APK verified VideoPatchLayout.canPlayBackground, including
                // the inherited field exposed by LayerHostMediaLayout.
                val original = readNativeBackgroundAllowance(host)
                if (original != null) {
                    originalBackgroundFlag = original
                    originalBackgroundHost = WeakReference(host)
                }
            }
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
        // Full-series versions sometimes signal pause through VideoShop
        // instead of TTVideoEngine.pause. Record the same verified candidate
        // only around a real Activity leave/pause, never for user media actions.
        if (!playing && knownPlaying && !userCommandInProgress &&
            videoEngine.get() != null &&
            (SystemClock.uptimeMillis() - lastPlayerActivityPauseAt in 0L..1500L ||
             SystemClock.uptimeMillis() - lastUserLeaveAt in 0L..1500L)) {
            lastLifecyclePauseAt = SystemClock.uptimeMillis()
            lastLifecyclePauseEngine = WeakReference(videoEngine.get())
            lastLifecyclePauseWasPlaying = true
            LogUtil.info("系统媒体: 已记录原生播放器暂停事件 source=$source" +
                " page=" + (currentMetadataPage ?: "?"))
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
                !userCommandInProgress && backgroundPauseGuardEngine.get() == null) {
                backgroundResumeBudget--
                resumeAttempts++
                LogUtil.warn("系统媒体: 后台暂停，单次恢复尝试 #" + resumeAttempts)
                handler.postDelayed({
                    if (enabled && requestedBackgroundPlayback && !knownPlaying &&
                        backgroundPauseGuardEngine.get() == null) {
                        requestTransport(true, "background-auto-resume")
                    }
                }, 350L)
            } else if (requestedBackgroundPlayback && !userCommandInProgress &&
                backgroundPauseGuardEngine.get() != null) {
                LogUtil.warn("系统媒体: 当前后台播放器仍被暂停；不进行重复 play 循环")
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
        // A hint is useful evidence, but not required for recents/app switches.
        // Real background confirmation happens after onPause / onStop.
        lastUserLeaveAt = SystemClock.uptimeMillis()
        lastUserLeaveWasPlaying = true
        lastUserLeaveEngine = WeakReference(videoEngine.get())
        rememberExitSnapshot(activity, "onUserLeaveHint")
        LogUtil.info("系统媒体: 页面离开事件已记录，等待真实后台确认" +
            " page=" + activity.javaClass.simpleName)
    }

    /** A newly constructed Hongguo Activity signals IN-APP navigation,
     * never a task background exit. Cancel the provisional pre-pause hold
     * before its onResume, so navigating feed/series/details is not affected.
     */
    fun onActivityStarting(activity: Activity?) {
        if (!enabled || activity == null || !isVideoActivity(foregroundActivity.get()))
            return
        val previous = foregroundActivity.get() ?: return
        if (activity === previous || activity.packageName != previous.packageName)
            return
        backgroundTransition.cancel()
        if (pendingExit?.activity?.get() === previous &&
            !requestedBackgroundPlayback) pendingExit = null
        if (!requestedBackgroundPlayback && backgroundFlagApplied) {
            if (originalBackgroundFlag != null)
                setNativeBackgroundAllowance(originalBackgroundFlag == true)
            backgroundFlagApplied = false
        }
        FeatureTrace.event("background", "transition", "cancelled",
            "reason=in_app_activity", flow = mediaFlow,
            minIntervalMs = 1200L)
    }

    fun onActivityResumed(activity: Activity?) {
        if (!enabled || activity == null ||
            !activity.javaClass.name.startsWith("com.dragon.read.")) return
        foregroundActivity = WeakReference(activity)
        pendingExit = null
        lastPlayerActivityPauseAt = 0L
        lastUserLeaveAt = 0L
        lastUserLeaveWasPlaying = false
        lastUserLeaveEngine.clear()
        backgroundTransition.cancel()
        episodeMonitor.reset()
        if (backgroundFlagApplied && !requestedBackgroundPlayback) {
            if (originalBackgroundFlag != null)
                setNativeBackgroundAllowance(originalBackgroundFlag == true)
            backgroundFlagApplied = false
        }
        if (backgroundPauseGuardEngine.get() != null) {
            backgroundPauseGuardEngine.clear()
            backgroundPauseGuardUntil = 0L
            backgroundPauseSuppressed = 0
            backgroundResumeBudget = 0
            LogUtil.info("系统媒体: 红果恢复前台，解除当前播放器暂停保护")
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
        val active = controller.get()
        val context = videoContext.get()
        if (candidate != null && (candidate === active || candidate === context) &&
            videoShopOwnsPlayingEngine(candidate) &&
            shouldHoldOriginalOnLeave(videoEngine.get())) {
            LogUtil.info("系统媒体: 离开红果前保留原生 VideoShop.pause")
            LogUtil.incr("systemMediaEarlyVideoShopPauseHeld")
            return true
        }
        if (!enabled || candidate == null || userCommandInProgress ||
            !requestedBackgroundPlayback || !knownPlaying) return false
        val now = SystemClock.uptimeMillis()
        if (now - leaveAt !in 0L..BACKGROUND_PAUSE_GUARD_MS) return false
        if ((candidate !== active && candidate !== context) ||
            !videoShopOwnsPlayingEngine(candidate)) return false
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
            explicitPauseEpoch++
            pendingExit = null
            backgroundTransition.cancel()
            backgroundPauseGuardEngine.clear()
            backgroundPauseGuardUntil = 0L
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
                .setContentTitle(mediaPresentation.title(seriesTitle))
                .setContentText(mediaPresentation.subtitle(episodeTitle, playing))
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
                FeatureTrace.event("media", "notification", "posted",
                    "playing=" + playing, flow = mediaFlow)
            }
        } catch (e: Throwable) {
            LogUtil.warn("系统媒体: 通知发布失败（检查红果通知权限）: " +
                e.javaClass.simpleName + ": " + e.message)
            LogUtil.incr("systemMediaNotificationFailed")
            FeatureTrace.event("media", "notification", "fail",
                "errorType=" + e.javaClass.simpleName, flow = mediaFlow)
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
        handler.removeCallbacks(delayedMetadataSync)
        mediaRefresh.cancel()
        mediaPresentation.reset()
        FeatureTrace.event("media", "session", "stopped", flow = mediaFlow)
        mediaFlow = 0L
        progressPolling = false
        durationMs = -1L
        positionMs = -1L
        bufferedMs = -1L
        actualPlaybackSpeed = 1f
        seriesTitle = null
        episodeTitle = null
        currentSeriesIdentity = null
        currentMetadataPage = null
        coverArt = null
        artworkGeneration++
        artworkRequestUrl = null
        artworkRequestedAt = 0L
        lastMetadataSignature = null
        lastMetadataArt = null
        lastLifecyclePauseAt = 0L
        lastPlayerActivityPauseAt = 0L
        lastLifecyclePauseWasPlaying = false
        lastUserLeaveAt = 0L
        lastUserLeaveWasPlaying = false
        lastUserLeaveEngine.clear()
        backgroundTransition.cancel()
        episodeMonitor.reset()
        pendingExit = null
        backgroundResumeBudget = 0
        backgroundPauseGuardEngine.clear()
        backgroundPauseGuardUntil = 0L
        backgroundPauseSuppressed = 0
        lastLifecyclePauseEngine.clear()
        lastVisibleInfoAt = 0L
        lastVisibleInfoIdentity = ""
        lastVisibleInfoSource = ""
        currentEngineVideoIdentity = null
        lastNativeMetaProbeAt = 0L
        controller.clear()
        layer.clear()
        videoContext.clear()
        videoEngine.clear()
        nativeSeriesPlayer.clear()
        nativeEofEngine.clear()
        nativeEofAt = 0L
        nativeEofGeneration++
        nativeEofRecoveryScheduled = false
        nativeEofRecoveryAttempted = false
        foregroundActivity.clear()
        originalBackgroundFlag = null
        originalBackgroundHost.clear()
    }
}
