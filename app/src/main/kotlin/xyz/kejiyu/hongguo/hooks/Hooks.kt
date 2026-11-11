package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Color
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.*
import xyz.kejiyu.hongguo.ModuleSettingsStore
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook
import xyz.kejiyu.hongguo.BuildConfig
import xyz.kejiyu.hongguo.UpdateChecker
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

object Hooks {

    private var gPrefs: SharedPreferences? = null
    private var gSettingsObserver: android.database.ContentObserver? = null

    // Capture window state only when the module actually changes the status bar.
    private data class OriginalStatusBar(
        val flags: Int,
        val systemUiVisibility: Int,
        val color: Int,
        val cutoutMode: Int,
        val lightAppearance: Int,
        val wasVisible: Boolean?,
    )
    private val gOriginalStatusBars = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, OriginalStatusBar>()
    )
    private val gHiddenGestureBars = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    // View hierarchy scans run often; never read Settings.Secure per View.
    @Volatile private var gGestureNavigationCache: Boolean? = null


    private var gMasterOn = false
    private var gStatusOn = false
    private var gControlOn = false
    // Independent of the master switch, so a disabled module can still be opened.
    private var gVideoLongPressSettingsOn = true
    // Keep the app's other tabs and Android three-button navigation untouched.
    private var gDisableShoppingMall = true
    private var gMiniPlayerOn = false
    private var gSystemMediaOn = false
    private var gPlayerOn = false
    private var gAdOn = false
    private var gRefreshOff = false
    private var gTopZoneOn = false
    private var gNavBarOff = false
    private var gProgressOff = false
    private var gRestoreControlsOnPause = false
    private var gVipOn = false
    private var gVipIconOn = false
    private var gMaxQualityOn = false

    private var gDefaultSpeedOn = false
    private var gDefaultSpeed = 1.0f
    private const val DEFAULT_SPEED_PREF = "default_speed"
    private const val DEFAULT_SPEED_VALUE_PREF = "default_speed_value"
    private val DEFAULT_SPEED_OPTIONS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
    private var gDoubleTapCommentOn = false

    private var gOledProtectOn = false

    private var gNotificationMenuOn = false

    private var gDownloadLimitUnlimitOn = false
    private const val DOWNLOAD_LIMIT_PREF = "download_limit_unlimit"
    private const val DOWNLOAD_LIMIT_EPISODE_PREF = "download_limit_one_day_episode"
    private const val DOWNLOAD_LIMIT_SERIES_PREF = "download_limit_one_day_series"
    private const val DOWNLOAD_LIMIT_TOTAL_PREF = "download_limit_total_series"
    private const val DOWNLOAD_LIMIT_AB_KEY = "video_download_limit_expansion_v711"
    private const val DOWNLOAD_LIMIT_DEFAULT_VALUE = 99999
    private var gDownloadOneDayEpisode = DOWNLOAD_LIMIT_DEFAULT_VALUE
    private var gDownloadOneDaySeries = DOWNLOAD_LIMIT_DEFAULT_VALUE
    private var gDownloadTotalSeries = DOWNLOAD_LIMIT_DEFAULT_VALUE
    @Volatile private var gVideoPaused = false

    private val gEngineMaxResolution = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Any>()
    )

    private val gControllerMaxResolution = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Any>()
    )

    private val gKnownPercentSpeedPlayers = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Boolean>()
    )
    private val gKnownFloatSpeedPlayers = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, Boolean>()
    )

    private val gRightViewAgencies = mutableListOf<java.lang.ref.WeakReference<Any>>()
    @Volatile private var gLastDoubleTapCommentAt = 0L

    @Volatile private var gSuppressDoubleTapLikeUntil = 0L

    private val gForcedShortMaskVisibility = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )

    private data class SavedViewState(var visibility: Int, val alpha: Float)
    private val gSavedViewStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, SavedViewState>()
    )

    private data class PauseForcedState(
        val visibility: Int,
        val alpha: Float,
        val translationX: Float,
        val translationY: Float,
    )
    private val gPauseForcedStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, PauseForcedState>()
    )

    private val gCleanMaskViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )
    private val gInternalViewMutation = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val gInsideFeedBottomMarginWrite = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private val gSavedTopMargins = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )

    private data class SavedBottomLayoutState(
        val width: Int,
        val height: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val marginLeft: Int?,
        val marginTop: Int?,
        val marginRight: Int?,
        val marginBottom: Int?,
        val bottomToTop: Int?,
        val bottomToBottom: Int?,
        val goneBottomMargin: Int?,
    )
    private val gSavedBottomLayoutStates = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, SavedBottomLayoutState>()
    )

    private val gSeriesGuardedViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownSeriesToolbars = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )

    private val gKnownRefreshAccessoryViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownMainBottomNavViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownFullSeriesEntryViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gKnownBottomBackdropViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gSavedNativeNavBarColors = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, Int>()
    )

    private val gFeedViewportNativeBottomMargins = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )
    private val gKnownFeedViewportViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    private val gVideoToolbarLayers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    private val gToolbarBaseLayers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    private data class ShortVideoHolderState(
        val ref: java.lang.ref.WeakReference<Any>,
        var playbackState: Int,
        var updatedAt: Long,
    )
    private val gShortVideoHolders = mutableListOf<ShortVideoHolderState>()
    private val gShortSeriesFragments =
        mutableListOf<java.lang.ref.WeakReference<Any>>()
    private val gCnHomeFragments =
        mutableListOf<java.lang.ref.WeakReference<Any>>()
    private var gLastVideoStateAt = 0L
    private var gLastVideoStateReason = "init"
    private var gLastWindowedMode: Boolean? = null

    private var gReceiverRegistered = false
    private var gCurrentActivity: Activity? = null

    private val gTargetIdSet = mutableSetOf<Int>()
    private val gProgressIdSet = mutableSetOf<Int>()
    private val gPauseRestoreIdSet = mutableSetOf<Int>()
    private val hideClasses: List<String> get() = listOf(gNames.hideView1, gNames.hideView2).filter { it.isNotBlank() }
    @Volatile private var gNames: TargetNames.Names = TargetNames.CN
    @Volatile private var gPkg = TargetNames.CN_PACKAGE
    @Volatile private var gTargetVersionName = "未知"
    @Volatile private var gTargetVersionCode = -1L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var gIsNight = false
    private var gSettingsActivity: Activity? = null
    private val gKejiyuBtnTag = "KEJIYU_BTN_TAG"
    private val gKejiyuSettingsWrapperTag = "KEJIYU_SETTINGS_WRAPPER_TAG"

    private var gTopZoneTracking = false
    private var gTopZoneActive = false
    private var gTopZoneStartY = 0f
    private const val TOP_ZONE_HEIGHT = 250

    private val seedIds = intArrayOf(
        0x7F0B03E3, 0x7F0B071E, 0x7F0B0412,
        0x7F0B0BCB, 0x7F0A00D2, 0x7F0B0C6A,
    )

    /**
     * Settings from the module app are authoritative. Import legacy target prefs
     * on first use, mirror back to the target XML for the download-process reader,
     * and refresh runtime fields when the app resumes or the provider changes.
     */
    private fun initPrefs(ctx: Context) {
        if (gPrefs == null) {
            gPrefs = ctx.getSharedPreferences("lspilot_kejiyu", Context.MODE_PRIVATE)
        }
        val prefs = gPrefs ?: return
        if (ctx.packageName == TargetNames.CN_PACKAGE ||
            ctx.packageName == TargetNames.OVERSEA_PACKAGE) {
            ModuleSettingsStore.importIfEmpty(ctx, prefs.all)
            ModuleSettingsStore.pullIntoTarget(ctx, prefs)
            ensureSettingsObserver(ctx)
        }

        gMasterOn = prefs.getBoolean("master_on", false)
        gStatusOn = prefs.getBoolean("status_bar", false)
        gControlOn = prefs.getBoolean("control_hide", false)
        gVideoLongPressSettingsOn = prefs.getBoolean("video_longpress_settings", true)
        gDisableShoppingMall = prefs.getBoolean("disable_shopping_mall", true)
        gMiniPlayerOn = prefs.getBoolean("in_app_mini_player", false)
        InAppMiniPlayer.enabled = gMasterOn && gMiniPlayerOn
        gSystemMediaOn = prefs.getBoolean("system_media_playback", false)
        SystemMediaBridge.configure(ctx, gMasterOn && gSystemMediaOn)
        gPlayerOn = prefs.getBoolean("player_bar", false)
        gAdOn = prefs.getBoolean("ad_block", false)
        gRefreshOff = prefs.getBoolean("pull_refresh", false)
        gTopZoneOn = prefs.getBoolean("top_zone", false)
        gNavBarOff = prefs.getBoolean("nav_bar_off", false)
        gProgressOff = prefs.getBoolean("progress_off", false)
        gRestoreControlsOnPause = prefs.getBoolean("restore_controls_pause", false)
        gVipOn = prefs.getBoolean("vip_unlock", false)
        gVipIconOn = prefs.getBoolean("vip_icon", false)
        gMaxQualityOn = prefs.getBoolean("max_quality", false)
        gDefaultSpeedOn = prefs.getBoolean(DEFAULT_SPEED_PREF, false)
        gDefaultSpeed = normalizeDefaultSpeed(prefs.getFloat(DEFAULT_SPEED_VALUE_PREF, 1.0f))
        gDoubleTapCommentOn = prefs.getBoolean("double_tap_comment", false)
        gOledProtectOn = prefs.getBoolean("oled_protect", false)
        gNotificationMenuOn = prefs.getBoolean("notification_menu", false)
        gDownloadLimitUnlimitOn = prefs.getBoolean(DOWNLOAD_LIMIT_PREF, false)
        gDownloadOneDayEpisode = prefs.getInt(DOWNLOAD_LIMIT_EPISODE_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)
        gDownloadOneDaySeries = prefs.getInt(DOWNLOAD_LIMIT_SERIES_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)
        gDownloadTotalSeries = prefs.getInt(DOWNLOAD_LIMIT_TOTAL_PREF, DOWNLOAD_LIMIT_DEFAULT_VALUE)

        // The generic View/Window and Android navigation hooks have no
        // dependence on old obfuscated IDs. Only unsafe hardcoded navigation
        // and toolbar substitutions are guarded at their call sites.
        // Never silently disable user-selected feature preferences based on
        // a missing legacy compatibility entry.
    }

    private fun ensureSettingsObserver(ctx: Context) {
        if (gSettingsObserver != null) return
        val observer = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post {
                    try {
                        val context = appCtx ?: ctx.applicationContext
                        val previouslyHiddenControls = gMasterOn && gControlOn
                        val previouslyHiddenPlayer = gMasterOn && gPlayerOn
                        initPrefs(context)
                        if (!InAppMiniPlayer.enabled) InAppMiniPlayer.restore()
                        if (isShoppingMallDisabled()) {
                            gNextShoppingTabScanAt = 0L
                            mainHandler.post { scanShoppingTabIfNeeded(gCurrentActivity, force = true) }
                        } else restoreShoppingTabViews()
                        if (!gMasterOn || (previouslyHiddenControls && !gControlOn)) {
                            restoreAllSavedViews()
                            restoreShortVideoNativeControls()
                            setVideoToolbarsVisible(true)
                        } else if (previouslyHiddenPlayer && !gPlayerOn) {
                            restoreShortVideoNativeControls()
                            setVideoToolbarsVisible(true)
                        }
                        applyToCurrent()
                        gCurrentActivity?.let { activity ->
                            if (gMasterOn && gStatusOn) applyCleanTop(activity) else showStatusBar(activity)
                            if (gMasterOn && gNavBarOff) applyNavBar(activity) else showNavBar(activity)
                        }
                        createNotification(context)
                        if (gMasterOn && gMaxQualityOn) applyHighestToKnownEngines()
                        scheduleDefaultSpeedApply("module-settings")
                        LogUtil.info("模块 App 配置已同步到红果进程")
                    } catch (e: Throwable) {
                        LogUtil.warn("模块配置同步失败: " + e.message)
                    }
                }
            }
        }
        try {
            ctx.contentResolver.registerContentObserver(ModuleSettingsStore.URI, false, observer)
            gSettingsObserver = observer
        } catch (e: Throwable) {
            LogUtil.warn("无法监听模块设置更新: " + e.message)
        }
    }

    private fun savePref(key: String, value: Boolean) {
        val editor = gPrefs?.edit()?.putBoolean(key, value) ?: return

        if (key == DOWNLOAD_LIMIT_PREF || key == "master_on") editor.commit()
        else editor.apply()
        appCtx?.let { ModuleSettingsStore.pushFromTarget(it, key, value) }
    }

    private fun normalizeDefaultSpeed(value: Float): Float {
        if (!value.isFinite()) return 1.0f
        return DEFAULT_SPEED_OPTIONS.minByOrNull { kotlin.math.abs(it - value) } ?: 1.0f
    }

    private fun defaultSpeedPercent(): Int = (normalizeDefaultSpeed(gDefaultSpeed) * 100f + 0.5f).toInt()

    private fun formatSpeed(value: Float): String {
        val v = normalizeDefaultSpeed(value)
        return if (kotlin.math.abs(v - v.toInt().toFloat()) < 0.001f) "${v.toInt()}.0x"
        else ("%.2fx".format(java.util.Locale.US, v)).replace("0x", "x")
    }

    private fun defaultSpeedSummary(): String = "当前 ${formatSpeed(gDefaultSpeed)}"

    private fun saveDefaultSpeedValue(value: Float) {
        gDefaultSpeed = normalizeDefaultSpeed(value)
        gPrefs?.edit()?.putFloat(DEFAULT_SPEED_VALUE_PREF, gDefaultSpeed)?.apply()
        appCtx?.let { ModuleSettingsStore.pushFromTarget(it, DEFAULT_SPEED_VALUE_PREF, gDefaultSpeed) }
    }

    private fun defaultSpeedEnabledNow(): Boolean = gMasterOn && gDefaultSpeedOn

    private fun weakPlayerSnapshot(map: MutableMap<Any, Boolean>): List<Any> {
        return try { synchronized(map) { map.keys.toList() } } catch (_: Throwable) { emptyList() }
    }

    private fun applySpeedToPercentPlayer(player: Any, reason: String): Boolean {
        if (!defaultSpeedEnabledNow()) return false
        return try {
            val method = player.javaClass.getMethod("setPlaySpeed", Integer.TYPE)
            method.invoke(player, defaultSpeedPercent())
            LogUtil.incr("defaultSpeedImmediatePercent")
            true
        } catch (_: Throwable) {
            try {
                val method = player.javaClass.getDeclaredMethod("setPlaySpeed", Integer.TYPE).apply { isAccessible = true }
                method.invoke(player, defaultSpeedPercent())
                LogUtil.incr("defaultSpeedImmediatePercent")
                true
            } catch (e: Throwable) {
                LogUtil.debug("默认倍速 percent 应用失败[$reason]: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    private fun applySpeedToFloatPlayer(player: Any, reason: String): Boolean {
        if (!defaultSpeedEnabledNow()) return false
        return try {
            val method = player.javaClass.getMethod("setSpeed", java.lang.Float.TYPE)
            method.invoke(player, gDefaultSpeed)
            LogUtil.incr("defaultSpeedImmediateFloat")
            true
        } catch (_: Throwable) {
            try {
                val method = player.javaClass.getDeclaredMethod("setSpeed", java.lang.Float.TYPE).apply { isAccessible = true }
                method.invoke(player, gDefaultSpeed)
                LogUtil.incr("defaultSpeedImmediateFloat")
                true
            } catch (e: Throwable) {
                LogUtil.debug("默认倍速 float 应用失败[$reason]: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    /** The same stable VideoShop speed path for feed and full series.
     * Does not depend on a percentage-player/controller's obfuscated name. */
    private fun applyUniversalSpeed(reason: String): Boolean {
        if (!TargetNames.isUniversal(gNames) || !defaultSpeedEnabledNow()) return false
        val activity = gCurrentActivity ?: return false
        return try {
            val loader = activity.classLoader
            val klass = Class.forName(
                "com.ss.android.videoshop.context.VideoContext", false, loader)
            val context = try {
                klass.getMethod("getResumedVideoContext").invoke(null)
            } catch (_: Throwable) { null } ?: klass
                .getMethod("getVideoContext", Context::class.java)
                .invoke(null, activity) ?: return false
            val playing = try {
                context.javaClass.getMethod("isPlaying").invoke(context) as? Boolean
            } catch (_: Throwable) { null }
            if (playing != true) return false
            val ptype = Class.forName("com.ss.ttm.player.PlaybackParams", false, loader)
            val existing = try {
                context.javaClass.getMethod("getPlayBackParams").invoke(context)
            } catch (_: Throwable) { null }
            val params = existing ?: ptype.getDeclaredConstructor().newInstance()
            val old = try {
                (ptype.getMethod("getSpeed").invoke(params) as? Number)?.toFloat()
            } catch (_: Throwable) { null }
            if (old != null && kotlin.math.abs(old - gDefaultSpeed) < 0.005f)
                return true
            ptype.getMethod("setSpeed", java.lang.Float.TYPE).invoke(params, gDefaultSpeed)
            context.javaClass.getMethod("setPlayBackParams", ptype)
                .invoke(context, params)
            LogUtil.info("通用默认倍速: ${formatSpeed(gDefaultSpeed)} source=$reason")
            LogUtil.incr("defaultSpeedUniversalApply")
            true
        } catch (e: Throwable) {
            LogUtil.debug("通用默认倍速暂不可用: " + e.javaClass.simpleName)
            false
        }
    }

    private fun applyDefaultSpeedNow(reason: String) {
        if (!defaultSpeedEnabledNow()) return
        val run = Runnable {
            var applied = 0
            weakPlayerSnapshot(gKnownPercentSpeedPlayers).forEach { if (applySpeedToPercentPlayer(it, reason)) applied++ }
            weakPlayerSnapshot(gKnownFloatSpeedPlayers).forEach { if (applySpeedToFloatPlayer(it, reason)) applied++ }
            if (applyUniversalSpeed(reason)) applied++
            LogUtil.info("默认倍速立即应用 | speed=${formatSpeed(gDefaultSpeed)} | players=$applied | reason=$reason")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun scheduleDefaultSpeedApply(reason: String) {
        if (!defaultSpeedEnabledNow()) return
        mainHandler.post { applyDefaultSpeedNow("$reason/0") }
        mainHandler.postDelayed({ applyDefaultSpeedNow("$reason/120") }, 120L)
        mainHandler.postDelayed({ applyDefaultSpeedNow("$reason/420") }, 420L)
    }

    private fun saveDownloadLimitValues(episode: Int, series: Int, total: Int) {
        gDownloadOneDayEpisode = episode
        gDownloadOneDaySeries = series
        gDownloadTotalSeries = total
        gPrefs?.edit()
            ?.putInt(DOWNLOAD_LIMIT_EPISODE_PREF, episode)
            ?.putInt(DOWNLOAD_LIMIT_SERIES_PREF, series)
            ?.putInt(DOWNLOAD_LIMIT_TOTAL_PREF, total)
            ?.commit()
        appCtx?.let {
            ModuleSettingsStore.pushFromTarget(it, DOWNLOAD_LIMIT_EPISODE_PREF, episode)
            ModuleSettingsStore.pushFromTarget(it, DOWNLOAD_LIMIT_SERIES_PREF, series)
            ModuleSettingsStore.pushFromTarget(it, DOWNLOAD_LIMIT_TOTAL_PREF, total)
        }
    }

    private fun readBooleanPrefFromDisk(key: String, defaultValue: Boolean = false): Boolean {
        try {
            val dataDir = try {
                appCtx?.applicationInfo?.dataDir
            } catch (_: Throwable) { null } ?: "/data/user/0/$gPkg"
            val file = java.io.File(dataDir, "shared_prefs/lspilot_kejiyu.xml")
            if (!file.isFile) return defaultValue
            java.io.FileInputStream(file).use { input ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "boolean") {
                        if (parser.getAttributeValue(null, "name") == key) {
                            return parser.getAttributeValue(null, "value")?.toBooleanStrictOrNull() ?: defaultValue
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (_: Throwable) {}
        return defaultValue
    }

    private fun readIntPrefFromDisk(key: String, defaultValue: Int): Int {
        try {
            val dataDir = try {
                appCtx?.applicationInfo?.dataDir
            } catch (_: Throwable) { null } ?: "/data/user/0/$gPkg"
            val file = java.io.File(dataDir, "shared_prefs/lspilot_kejiyu.xml")
            if (!file.isFile) return defaultValue
            java.io.FileInputStream(file).use { input ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "int") {
                        if (parser.getAttributeValue(null, "name") == key) {
                            return parser.getAttributeValue(null, "value")?.toIntOrNull() ?: defaultValue
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (_: Throwable) {}
        return defaultValue
    }

    private fun downloadUnlimitEnabledNow(): Boolean {
        val master = readBooleanPrefFromDisk("master_on", gMasterOn)
        val feature = readBooleanPrefFromDisk(DOWNLOAD_LIMIT_PREF, gDownloadLimitUnlimitOn)
        return master && feature
    }

    private fun currentDownloadLimitValues(): Triple<Int, Int, Int> {
        fun clean(v: Int): Int = v.coerceIn(1, 999_999_999)
        val episode = clean(readIntPrefFromDisk(DOWNLOAD_LIMIT_EPISODE_PREF, gDownloadOneDayEpisode))
        val series = clean(readIntPrefFromDisk(DOWNLOAD_LIMIT_SERIES_PREF, gDownloadOneDaySeries))
        val total = clean(readIntPrefFromDisk(DOWNLOAD_LIMIT_TOTAL_PREF, gDownloadTotalSeries))
        return Triple(episode, series, total)
    }

    private fun customDownloadLimitJson(): String {
        val (episode, series, total) = currentDownloadLimitValues()
        return "{\"one_day_max_episode_limit\":" + episode +
            ",\"one_day_max_series_limit\":" + series +
            ",\"total_max_series_limit\":" + total + "}"
    }

    private fun overwriteDownloadLimitObject(obj: Any?): Boolean {
        // a/b/c are old obfuscated data fields; never alter an unknown
        // class layout simply because the fields happen to be integers.
        if (TargetNames.isUniversal(gNames) || obj == null) return false
        val (episode, series, total) = currentDownloadLimitValues()
        val values = mapOf("a" to episode, "b" to series, "c" to total)
        return try {
            var changed = false
            for ((name, value) in values) {
                try {
                    val f = obj.javaClass.getDeclaredField(name)
                    if (f.type == Int::class.javaPrimitiveType) {
                        f.isAccessible = true
                        f.setInt(obj, value)
                        changed = true
                    }
                } catch (_: Throwable) {}
            }
            changed
        } catch (_: Throwable) { false }
    }
    private fun resolveEntryId(
        entry: String,
        root: View?,
        targetSet: MutableSet<Int> = gTargetIdSet,
        logPrefix: String = "资源",
    ) {
        if (root == null) return
        try {
            val id = root.resources.getIdentifier(entry, "id", gPkg)
            if (id > 0 && targetSet.add(id)) LogUtil.info("$logPrefix $entry ID: $id")
        } catch (_: Exception) {}
    }
    private fun resolvePauseRestoreIds(root: View?) {
        if (root == null) return
        when (gNames.profileId) {
            "OVERSEA-7.3.1.32" -> {
                resolveEntryId("right_interact_container", root, gPauseRestoreIdSet, "暂停恢复资源")
                resolveEntryId("ly_tools_bar_icon", root, gPauseRestoreIdSet, "暂停恢复资源")
            }
            "CN-7.3.3.18" -> resolveEntryId("fxu", root, gPauseRestoreIdSet, "暂停恢复资源")
        }
    }
    private fun quickMatch(v: View?): Boolean {
        if (v == null) return false
        if (keepNativeNavigation()) {
            val entry = viewEntryName(v)
            if (v.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout" ||
                entry == "bottom_bar_layout" || entry == "aot" ||
                entry == "bottom_tab_mask" || entry == "ar8" || entry == "ar9") return false
        }
        LogUtil.incr("matchCall")
        try { val id = v.id; if (id > 0 && gTargetIdSet.contains(id)) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        try { if (v.javaClass.name in hideClasses) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        if (isFullscreenWatchControl(v)) { LogUtil.incr("matchHit"); return true }
        if (isKnownFullSeriesEntry(v) || isHomeFullSeriesEntry(v)) { LogUtil.incr("matchHit"); return true }
        if (isKnownBottomBackdrop(v) || isHomeBottomBackdropMarker(v)) { LogUtil.incr("matchHit"); return true }
        return false
    }

    private fun isFullscreenWatchControl(v: View?): Boolean {
        if (!gNames.structuralFullscreenWatch || v !is LinearLayout) return false
        try { if (v.id > 0) return false } catch (_: Throwable) {}
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val widthDp = width / density
        val heightDp = height / density
        val leftDp = v.paddingLeft / density
        val topDp = v.paddingTop / density
        val rightDp = v.paddingRight / density
        val bottomDp = v.paddingBottom / density
        val bgOk = try { v.background?.javaClass?.name == "android.graphics.drawable.GradientDrawable" } catch (_: Throwable) { false }
        return bgOk && widthDp in 84f..94f && heightDp in 25f..32f &&
            leftDp in 8f..12f && rightDp in 8f..12f &&
            topDp in 4f..8f && bottomDp in 4f..8f
    }

    private fun isInsideCurrentActivityDecor(v: View?): Boolean {
        if (v == null) return false
        val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null } ?: return false
        var node: View? = v
        var guard = 0
        while (node != null && guard++ < 80) {
            if (node === decor) return true
            node = node.parent as? View
        }
        return false
    }

    private fun viewEntryName(v: View?): String {
        if (v == null) return ""
        return try {
            if (v.id > 0) v.resources.getResourceEntryName(v.id) else ""
        } catch (_: Throwable) { "" }
    }

    private fun collectUiTexts(root: View?, maxDepth: Int = 4): List<String> {
        if (root == null) return emptyList()
        val out = ArrayList<String>(8)
        fun walk(v: View, depth: Int) {
            if (depth > maxDepth || out.size >= 24) return
            if (v is TextView) {
                val text = try { v.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
                if (text.isNotEmpty()) out.add(text)
            }
            val desc = try { v.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            if (desc.isNotEmpty()) out.add(desc)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun isRefreshText(text: String): Boolean {
        val t = text.lowercase()
        return text.contains("下拉刷新") || text.contains("刷新内容") || text.contains("松开刷新") ||
            text.contains("正在刷新") || t.contains("pull to refresh") || t.contains("release to refresh") ||
            t.contains("refreshing")
    }

    private fun isRefreshAccessoryContainer(v: View?): Boolean {
        if (v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 39f..56f) return false
        val screenW = try { v.resources.displayMetrics.widthPixels } catch (_: Throwable) { width }
        if (screenW > 0 && width.toFloat() / screenW.toFloat() < 0.93f) return false

        if (collectUiTexts(v, 4).any(::isRefreshText)) return true
        val idName = viewEntryName(v)
        if (idName == "root_layout") {
            val parent = v.parent as? View
            if (collectUiTexts(parent, 3).any(::isRefreshText)) return true
        }
        return false
    }

    private fun hideRefreshAccessory(v: View?) {
        if (v == null || !gMasterOn || !gRefreshOff) return
        try {
            synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews[v] = true }
            if (v.visibility != View.GONE) {
                rememberViewState(v)

                setModuleVisibility(v, View.GONE)
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
                LogUtil.incr("refreshAccessoryHide")
            }
        } catch (_: Throwable) {}
    }

    private fun scanTreeRefreshAccessory(v: View?) {
        if (v == null || !gMasterOn || !gRefreshOff) return
        if (isRefreshAccessoryContainer(v)) {
            hideRefreshAccessory(v)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeRefreshAccessory(v.getChildAt(i))
    }

    private fun restoreRefreshAccessories() {
        val views = synchronized(gKnownRefreshAccessoryViews) {
            gKnownRefreshAccessoryViews.keys.toList().also { gKnownRefreshAccessoryViews.clear() }
        }
        for (v in views) restoreView(v)
        if (views.isNotEmpty()) LogUtil.info("restore refresh accessories: ${views.size}")
    }

    private fun isHomeFullSeriesEntry(v: View?): Boolean {
        if (keepNativeNavigation()) return false
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) ||
            v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        synchronized(gKnownFullSeriesEntryViews) {
            if (gKnownFullSeriesEntryViews.containsKey(v)) return true
        }
        if (v.javaClass.name != "android.widget.FrameLayout") return false
        if (viewEntryName(v) != "root_layout") return false

        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { return false }
        val density = dm.density.coerceAtLeast(0.1f)
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 41f..47.5f) return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.94f) return false

        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }

        if (loc[1] < dm.heightPixels * 0.68f) return false

        val texts = collectUiTexts(v, 5)
        val matchedByText = texts.any { raw ->
            val text = raw.replace(" ", "")
            val lower = text.lowercase()
            text.contains("观看完整漫剧") || text.contains("观看完整短剧") ||
                text.contains("观看全集") || text.contains("完整剧集") ||
                lower.contains("watchall") || lower.contains("allepisodes") || lower.contains("fullseries") ||
                (text.contains("观看") && Regex("全\\d+集").containsMatchIn(text))
        }
        val matched = matchedByText || gPkg == TargetNames.OVERSEA_PACKAGE
        if (!matched) return false

        synchronized(gKnownFullSeriesEntryViews) { gKnownFullSeriesEntryViews[v] = true }
        LogUtil.info("home full-series entry recognized: root_layout, h=${"%.1f".format(hDp)}dp")
        return true
    }

    private fun isKnownFullSeriesEntry(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownFullSeriesEntryViews) { gKnownFullSeriesEntryViews.containsKey(v) }

    private fun isHomeBottomBackdropMarker(v: View?): Boolean {
        if (keepNativeNavigation()) return false
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) ||
            v == null || !isInsideCurrentActivityDecor(v)) return false
        val markerName = viewEntryName(v)
        val markerMatched = when {
            gPkg == TargetNames.OVERSEA_PACKAGE -> markerName == "bottom_tab_mask"
            gNames.profileId == "CN-7.3.3.18" -> markerName == "ar9"
            gNames.profileId == "CN-7.3.2.32" || gNames.profileId == "CN-7.3.1.32" -> markerName == "ar8"
            else -> markerName == "ar9" || markerName == "ar8"
        }
        if (!markerMatched) return false
        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { return false }
        val density = dm.density.coerceAtLeast(0.1f)
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 50f..61f) return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.94f) return false
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }
        return loc[1] + height >= dm.heightPixels * 0.94f
    }

    private fun isKnownBottomBackdrop(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownBottomBackdropViews) { gKnownBottomBackdropViews.containsKey(v) }

    private fun isNativeMainBottomFrame(v: View?): Boolean {
        if (keepNativeNavigation()) return false
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) || v == null) return false
        return try {
            val idName = viewEntryName(v)
            v.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout" ||
                (v is ViewGroup && (idName == "aot" || idName == "bottom_bar_layout"))
        } catch (_: Throwable) { false }
    }

    private fun isNativeVideoFeedBottomMask(v: View?): Boolean {
        if (keepNativeNavigation()) return false
        if (gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE) || v == null) return false
        return try {
            val idName = viewEntryName(v)
            when {
                gPkg == TargetNames.OVERSEA_PACKAGE -> idName == "bottom_tab_mask"
                gNames.profileId == "CN-7.3.3.18" -> idName == "ar9"
                gNames.profileId == "CN-7.3.2.32" || gNames.profileId == "CN-7.3.1.32" -> idName == "ar8"
                gPkg == TargetNames.CN_PACKAGE -> idName == "ar9" || idName == "ar8"
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    private fun rememberNativeNavBarColor(act: Activity?) {
        if (act == null) return
        try {
            synchronized(gSavedNativeNavBarColors) {
                if (!gSavedNativeNavBarColors.containsKey(act)) {
                    gSavedNativeNavBarColors[act] = act.window.navigationBarColor
                }
            }
        } catch (_: Throwable) {}
    }

    private fun updateNativeNavBarRestoreColor(act: Activity?) {
        if (act == null) return
        try { synchronized(gSavedNativeNavBarColors) { gSavedNativeNavBarColors[act] = act.window.navigationBarColor } }
        catch (_: Throwable) {}
    }

    private fun restoreNativeBottomWindowColor(act: Activity?) {
        if (act == null || gNavBarOff) return
        val color = synchronized(gSavedNativeNavBarColors) { gSavedNativeNavBarColors.remove(act) } ?: return
        try {
            act.window.navigationBarColor = color
            act.window.decorView.requestApplyInsets()
        } catch (_: Throwable) {}
    }

    private fun collapseNativeMainBottomFrame(v: View?) {
        if (keepNativeNavigation()) return
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        if (!isNativeMainBottomFrame(v)) return
        try {
            synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews[v] = true }
            if (v.visibility != View.GONE) {
                rememberViewState(v)
                val oldHeight = if (v.height > 0) v.height else v.measuredHeight
                setModuleVisibility(v, View.GONE)
                LogUtil.info("底部原生栏已折叠 | pkg=$gPkg | id=${viewEntryName(v)} | h=${oldHeight}px")
            }
            v.requestLayout()
            (v.parent as? View)?.requestLayout()
            // App-tab visibility must not change the OS navigation bar's insets.
            LogUtil.incr("nativeBottomFrameCollapse")
        } catch (e: Throwable) {
            LogUtil.warn("collapse native BottomTabFrameLayout failed: $e")
        }
    }

    private fun collapseNativeVideoFeedBottomMask(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        if (!isNativeVideoFeedBottomMask(v)) return
        try {
            synchronized(gKnownBottomBackdropViews) { gKnownBottomBackdropViews[v] = true }
            if (v.visibility != View.GONE) {
                rememberViewState(v)
                val oldHeight = if (v.height > 0) v.height else v.measuredHeight
                setModuleVisibility(v, View.GONE)
                LogUtil.info("短视频底部 mask 已折叠 | pkg=$gPkg | id=${viewEntryName(v)} | h=${oldHeight}px")
            }
            v.requestLayout()
            (v.parent as? View)?.requestLayout()
            LogUtil.incr("nativeVideoFeedBottomMaskCollapse")
        } catch (e: Throwable) {
            LogUtil.warn("collapse VideoFeedTabBottomMask failed: $e")
        }
    }

    private fun findNativeMainBottomFrame(act: Activity?): View? {
        if (act == null || gPkg !in setOf(TargetNames.CN_PACKAGE, TargetNames.OVERSEA_PACKAGE)) return null
        val fieldCandidates = when (gPkg) {
            TargetNames.CN_PACKAGE -> listOf("H", "G")
            TargetNames.OVERSEA_PACKAGE -> listOf("G", "H")
            else -> emptyList()
        }
        for (fieldName in fieldCandidates) {
            try {
                val byField = findFieldValue(act, fieldName) as? View
                if (isNativeMainBottomFrame(byField)) return byField
            } catch (_: Throwable) {}
        }
        val idCandidates = when (gPkg) {
            TargetNames.CN_PACKAGE -> listOf("aot", "bottom_bar_layout")
            TargetNames.OVERSEA_PACKAGE -> listOf("bottom_bar_layout", "aot")
            else -> emptyList()
        }
        for (entry in idCandidates) {
            try {
                val id = act.resources.getIdentifier(entry, "id", gPkg)
                if (id != 0) {
                    val byId = act.findViewById<View>(id)
                    if (isNativeMainBottomFrame(byId)) return byId
                }
            } catch (_: Throwable) {}
        }
        fun walk(v: View?): View? {
            if (v == null) return null
            if (isNativeMainBottomFrame(v)) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) {
                val hit = walk(v.getChildAt(i))
                if (hit != null) return hit
            }
            return null
        }
        return try { walk(act.window?.decorView) } catch (_: Throwable) { null }
    }

    private fun enforceNativeMainBottomHidden(act: Activity?) {
        if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        collapseNativeMainBottomFrame(findNativeMainBottomFrame(act))
    }

    private fun findFeedViewport(root: View?): View? {
        if (root == null) return null
        try {
            if (root.javaClass.name == "androidx.viewpager2.widget.ViewPager2") return root
            val idName = viewEntryName(root)

            if (idName == "kkk" || idName == "kij" || idName == "view_pager_container") return root
        } catch (_: Throwable) {}
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findFeedViewport(root.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun reclaimFeedViewportBottomMargin(root: View?) {
        if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        val pager = findFeedViewport(root) ?: return
        try {
            synchronized(gKnownFeedViewportViews) { gKnownFeedViewportViews[pager] = true }
            val lp = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val nativeBottom = lp.bottomMargin
            if (nativeBottom > 0) {

                synchronized(gFeedViewportNativeBottomMargins) {
                    gFeedViewportNativeBottomMargins[pager] = nativeBottom
                }
                lp.bottomMargin = 0
                pager.layoutParams = lp
                pager.requestLayout()
                (pager.parent as? View)?.requestLayout()
                LogUtil.info("首页视频底部占位已回收 | pkg=$gPkg | id=${viewEntryName(pager)} | ${nativeBottom}px -> 0")
                LogUtil.incr("feedViewportBottomMarginReclaim")
            }
        } catch (e: Throwable) {
            LogUtil.warn("reclaim feed viewport bottom margin failed: $e")
        }
    }

    private fun enforceKnownFeedViewportBottomMargin() {
        if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        val views = synchronized(gKnownFeedViewportViews) { gKnownFeedViewportViews.keys.toList() }
        for (pager in views) reclaimFeedViewportBottomMargin(pager)
    }

    private fun restoreFeedViewportBottomMargins(clear: Boolean = true) {
        val entries = synchronized(gFeedViewportNativeBottomMargins) {
            gFeedViewportNativeBottomMargins.entries.map { it.key to it.value }.also {
                if (clear) gFeedViewportNativeBottomMargins.clear()
            }
        }
        for ((pager, bottom) in entries) {
            try {
                val lp = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom
                    pager.layoutParams = lp
                    pager.requestLayout()
                    (pager.parent as? View)?.requestLayout()
                }
            } catch (_: Throwable) {}
        }
        if (clear) synchronized(gKnownFeedViewportViews) { gKnownFeedViewportViews.clear() }
        if (entries.isNotEmpty()) LogUtil.info("首页视频底部占位已恢复 | count=${entries.size}")
    }

    private fun reflectIntField(obj: Any?, name: String): Int? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.getInt(obj)
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return null
    }

    private fun setReflectIntField(obj: Any?, name: String, value: Int): Boolean {
        if (obj == null) return false
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                f.setInt(obj, value)
                return true
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return false
    }

    private fun rememberBottomLayoutState(v: View) {
        synchronized(gSavedBottomLayoutStates) {
            if (gSavedBottomLayoutStates.containsKey(v)) return
            val lp = v.layoutParams
            val mlp = lp as? ViewGroup.MarginLayoutParams
            gSavedBottomLayoutStates[v] = SavedBottomLayoutState(
                width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                paddingLeft = v.paddingLeft,
                paddingTop = v.paddingTop,
                paddingRight = v.paddingRight,
                paddingBottom = v.paddingBottom,
                marginLeft = mlp?.leftMargin,
                marginTop = mlp?.topMargin,
                marginRight = mlp?.rightMargin,
                marginBottom = mlp?.bottomMargin,
                bottomToTop = reflectIntField(lp, "bottomToTop"),
                bottomToBottom = reflectIntField(lp, "bottomToBottom"),
                goneBottomMargin = reflectIntField(lp, "goneBottomMargin"),
            )
        }
    }

    private fun restoreBottomLayoutReclaim() {
        val entries = synchronized(gSavedBottomLayoutStates) {
            val copy = gSavedBottomLayoutStates.entries.map { it.key to it.value }
            gSavedBottomLayoutStates.clear()
            copy
        }
        for ((v, st) in entries) {
            try {
                val lp = v.layoutParams
                if (lp != null) {
                    lp.width = st.width
                    lp.height = st.height
                    val mlp = lp as? ViewGroup.MarginLayoutParams
                    if (mlp != null && st.marginLeft != null && st.marginTop != null &&
                        st.marginRight != null && st.marginBottom != null) {
                        mlp.setMargins(st.marginLeft, st.marginTop, st.marginRight, st.marginBottom)
                    }
                    st.bottomToTop?.let { setReflectIntField(lp, "bottomToTop", it) }
                    st.bottomToBottom?.let { setReflectIntField(lp, "bottomToBottom", it) }
                    st.goneBottomMargin?.let { setReflectIntField(lp, "goneBottomMargin", it) }
                    v.layoutParams = lp
                }
                v.setPadding(st.paddingLeft, st.paddingTop, st.paddingRight, st.paddingBottom)
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        if (entries.isNotEmpty()) LogUtil.info("restore bottom layout reclaim: ${entries.size}")
    }

    private fun isNear(value: Int, target: Int, tolerance: Int): Boolean =
        kotlin.math.abs(value - target) <= tolerance

    private fun reclaimBottomSpaceFromAr9(marker: View) {
        if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        try {
            val dm = marker.resources.displayMetrics
            val density = dm.density.coerceAtLeast(0.1f)
            val markerLoc = IntArray(2)
            marker.getLocationOnScreen(markerLoc)
            val markerTop = markerLoc[1]
            val markerHeight = (if (marker.height > 0) marker.height else marker.measuredHeight).coerceAtLeast(1)
            val tol = (12f * density).toInt().coerceAtLeast(8)
            val minLargeHeight = (dm.heightPixels * 0.28f).toInt()

            val anchorIds = linkedSetOf<Int>()
            var idNode: View? = marker
            var idDepth = 0
            while (idNode != null && idDepth++ < 8) {
                if (idNode.id > 0) anchorIds.add(idNode.id)
                idNode = idNode.parent as? View
            }

            fun reclaimLargeBranch(root: View?, depth: Int = 0) {
                if (root == null || depth > 4) return
                try {
                    val w = if (root.width > 0) root.width else root.measuredWidth
                    val h = if (root.height > 0) root.height else root.measuredHeight
                    if (w > 0 && h > minLargeHeight &&
                        w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.82f) {
                        val lp = root.layoutParams
                        val mlp = lp as? ViewGroup.MarginLayoutParams
                        val bottomMargin = mlp?.bottomMargin ?: 0
                        val padBottom = root.paddingBottom
                        val barLikeMin = (markerHeight * 0.55f).toInt()
                        val barLikeMax = (markerHeight * 1.75f).toInt()
                        var changed = false
                        if (padBottom in barLikeMin..barLikeMax && padBottom > 0) {
                            rememberBottomLayoutState(root)
                            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
                            changed = true
                        }
                        if (mlp != null && bottomMargin in barLikeMin..barLikeMax && bottomMargin > 0) {
                            rememberBottomLayoutState(root)
                            mlp.bottomMargin = 0
                            root.layoutParams = mlp
                            changed = true
                        }
                        if (changed) {
                            root.requestLayout()
                            LogUtil.incr("bottomInsetReclaim")
                        }
                    }
                } catch (_: Throwable) {}
                if (root is ViewGroup) {
                    for (i in 0 until root.childCount) reclaimLargeBranch(root.getChildAt(i), depth + 1)
                }
            }

            var branch: View = marker
            var parent = marker.parent as? ViewGroup
            var depth = 0
            while (parent != null && depth++ < 7) {
                val parentChildren = parent.childCount
                for (i in 0 until parentChildren) {
                    val child = parent.getChildAt(i)
                    if (child === branch) continue
                    try {
                        val lp = child.layoutParams
                        var changed = false

                        val btt = reflectIntField(lp, "bottomToTop")
                        if (btt != null && btt >= 0 && anchorIds.contains(btt)) {
                            rememberBottomLayoutState(child)
                            setReflectIntField(lp, "bottomToTop", -1)
                            setReflectIntField(lp, "bottomToBottom", 0)
                            setReflectIntField(lp, "goneBottomMargin", 0)
                            child.layoutParams = lp
                            changed = true
                            LogUtil.info("bottom constraint reclaimed: ${child.javaClass.name}, anchor=$btt")
                            LogUtil.incr("bottomConstraintReclaim")
                        }

                        val loc = IntArray(2)
                        child.getLocationOnScreen(loc)
                        val w = if (child.width > 0) child.width else child.measuredWidth
                        val h = if (child.height > 0) child.height else child.measuredHeight
                        val childBottom = loc[1] + h
                        val looksLikeMainContent = w > 0 && h > minLargeHeight &&
                            w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.82f &&
                            loc[1] < markerTop - markerHeight
                        if (looksLikeMainContent && isNear(childBottom, markerTop, tol) && lp != null) {
                            val parentLoc = IntArray(2)
                            parent.getLocationOnScreen(parentLoc)
                            val parentH = if (parent.height > 0) parent.height else parent.measuredHeight
                            val desiredHeight = parentLoc[1] + parentH - loc[1]
                            val growBy = desiredHeight - h
                            val maxGrow = (markerHeight * 1.8f).toInt().coerceAtLeast(markerHeight + tol)
                            if (growBy > tol && growBy <= maxGrow) {
                                rememberBottomLayoutState(child)
                                lp.height = desiredHeight
                                child.layoutParams = lp
                                changed = true
                                LogUtil.info("bottom content extended: ${child.javaClass.name}, ${h}px -> ${desiredHeight}px")
                                LogUtil.incr("bottomHeightReclaim")
                            }
                        }

                        if (looksLikeMainContent) reclaimLargeBranch(child)

                        if (changed) {
                            child.requestLayout()
                            parent.requestLayout()
                        }
                    } catch (_: Throwable) {}
                }
                branch = parent
                parent = parent.parent as? ViewGroup
            }

            val decor = gCurrentActivity?.window?.decorView
            reclaimLargeBranch(decor)
        } catch (e: Throwable) {
            LogUtil.warn("reclaim bottom space from ar9 failed: $e")
        }
    }

    private fun bottomBackdropCollapseTarget(marker: View): View {
        val dm = try { marker.resources.displayMetrics } catch (_: Throwable) { return marker }
        val density = dm.density.coerceAtLeast(0.1f)
        var target: View = marker
        var cur: View = marker
        repeat(4) {
            val parent = cur.parent as? View ?: return@repeat
            val w = if (parent.width > 0) parent.width else parent.measuredWidth
            val h = if (parent.height > 0) parent.height else parent.measuredHeight
            if (w <= 0 || h <= 0) return@repeat
            val hDp = h / density
            val loc = IntArray(2)
            try { parent.getLocationOnScreen(loc) } catch (_: Throwable) { return@repeat }
            val fullWidth = w.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() >= 0.92f
            val compact = hDp in 48f..128f
            val nearBottom = loc[1] + h >= dm.heightPixels * 0.92f
            if (!fullWidth || !compact || !nearBottom) return@repeat
            target = parent
            cur = parent
        }
        return target
    }

    private fun collapseHomeBottomBackdrop(marker: View?) {
        if (marker == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return

        if (isNativeVideoFeedBottomMask(marker) || isHomeBottomBackdropMarker(marker) || isKnownBottomBackdrop(marker)) {
            collapseNativeVideoFeedBottomMask(marker)
            enforceNativeMainBottomHidden(gCurrentActivity)
        }
    }

    // Only the shopping tab and its dedicated activity are blocked. In particular
    // SeriesBookMallTabFragment and SeriesMallFragment are SHORT-DRAMA screens,
    // not the e-commerce shopping mall, and must remain functional.
    private const val SHOP_ACTIVITY =
        "com.dragon.read.component.biz.impl.mall.activity.MallPageActivity"
    private const val MAIN_TAB_ACTIVITY =
        "com.dragon.read.pages.main.MainFragmentActivity"
    private val gOriginalShoppingTabVisibility = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Int>()
    )
    private var gNextShoppingTabScanAt = 0L

    private fun isShoppingMallDisabled(): Boolean =
        gMasterOn && gDisableShoppingMall && gPkg == TargetNames.CN_PACKAGE

    private fun hasExactText(view: View?, value: String, depth: Int = 0): Boolean {
        if (view == null || depth > 5) return false
        if (view is TextView &&
            view.text?.toString()?.trim()?.replace(" ", "") == value) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasExactText(view.getChildAt(i), value, depth + 1)) return true
            }
        }
        return false
    }

    private fun findMallBottomBarRoot(act: Activity): ViewGroup? {
        if (act.javaClass.name != MAIN_TAB_ACTIVITY) return null

        for (entry in listOf("bottom_bar_layout", "aot")) {
            try {
                val id = act.resources.getIdentifier(entry, "id", gPkg)
                if (id != 0) {
                    val view = act.findViewById<View>(id)
                    if (view is ViewGroup && view.isAttachedToWindow) return view
                }
            } catch (_: Throwable) {}
        }

        for (field in listOf("H", "G")) {
            val view = try { findFieldValue(act, field) as? ViewGroup } catch (_: Throwable) { null }
            if (view?.javaClass?.name == "com.dragon.read.widget.BottomTabFrameLayout") return view
        }

        fun walk(view: View?, depth: Int = 0): ViewGroup? {
            if (view == null || depth > 18) return null
            if (view is ViewGroup) {
                if (view.javaClass.name == "com.dragon.read.widget.BottomTabFrameLayout") return view
                for (i in 0 until view.childCount) {
                    val found = walk(view.getChildAt(i), depth + 1)
                    if (found != null) return found
                }
            }
            return null
        }
        return walk(act.window.decorView)
    }

    private fun findShoppingTabView(root: ViewGroup): View? {
        val matches = mutableListOf<TextView>()
        fun collectMallLabels(view: View?, depth: Int) {
            if (view == null || depth > 15 || matches.size >= 3) return
            if (view is TextView &&
                view.text?.toString()?.trim()?.replace(" ", "") == "商城") {
                matches.add(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collectMallLabels(view.getChildAt(i), depth + 1)
                }
            }
        }
        collectMallLabels(root, 0)
        if (matches.isEmpty()) return null

        val otherNames = setOf("首页", "剧场", "我的", "赚钱")
        for (match in matches) {
            val location = IntArray(2)
            try { match.getLocationOnScreen(location) } catch (_: Throwable) { continue }
            if (location[1] < match.resources.displayMetrics.heightPixels * 0.68f) continue

            var item: View = match
            for (level in 0 until 8) {
                val parent = item.parent as? ViewGroup ?: break
                val siblings = (0 until parent.childCount).mapNotNull { parent.getChildAt(it) }
                if (siblings.size in 3..7 &&
                    siblings.count { child -> otherNames.any { hasExactText(child, it) } } >= 2 &&
                    siblings.count { child -> hasExactText(child, "商城") } == 1) {
                    // The item contains only "商城"; the siblings contain other tabs.
                    return item
                }
                if (parent === root) break
                item = parent
            }
        }
        return null
    }

    private fun isKnownShoppingTabView(view: View?): Boolean = view != null &&
        synchronized(gOriginalShoppingTabVisibility) {
            gOriginalShoppingTabVisibility.containsKey(view)
        }

    private fun isWithinShoppingTab(view: View?): Boolean {
        var node = view
        repeat(12) {
            if (node == null) return false
            if (isKnownShoppingTabView(node)) return true
            node = node?.parent as? View
        }
        return false
    }

    private fun restoreShoppingTabViews() {
        val saved = synchronized(gOriginalShoppingTabVisibility) {
            gOriginalShoppingTabVisibility.entries.map { it.key to it.value }.also {
                gOriginalShoppingTabVisibility.clear()
            }
        }
        for ((item, visibility) in saved) {
            try {
                setModuleVisibility(item, visibility)
                item.requestLayout()
                (item.parent as? ViewGroup)?.requestLayout()
            } catch (_: Throwable) {}
        }
        if (saved.isNotEmpty()) LogUtil.info("商城导航已恢复: " + saved.size)
    }

    private fun enforceShoppingTabHidden(act: Activity?) {
        if (!isShoppingMallDisabled()) {
            if (gOriginalShoppingTabVisibility.isNotEmpty()) restoreShoppingTabViews()
            return
        }
        if (act?.javaClass?.name != MAIN_TAB_ACTIVITY || act.isFinishing) return
        try {
            val root = findMallBottomBarRoot(act) ?: return
            val item = findShoppingTabView(root) ?: return
            synchronized(gOriginalShoppingTabVisibility) {
                if (!gOriginalShoppingTabVisibility.containsKey(item)) {
                    gOriginalShoppingTabVisibility[item] = item.visibility
                }
            }
            if (item.visibility != View.GONE) {
                setModuleVisibility(item, View.GONE)
                item.requestLayout()
                (item.parent as? ViewGroup)?.requestLayout()
                LogUtil.info("首页仅隐藏商城 Tab, parent=" +
                    (item.parent as? View)?.javaClass?.name)
                LogUtil.incr("shoppingTabHide")
            }
        } catch (e: Throwable) {
            LogUtil.warn("商城 Tab 隐藏失败: " + e.message)
        }
    }

    private fun scanShoppingTabIfNeeded(act: Activity?, force: Boolean = false) {
        if (!isShoppingMallDisabled()) {
            restoreShoppingTabViews()
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now < gNextShoppingTabScanAt) return
        gNextShoppingTabScanAt = now + 850L
        enforceShoppingTabHidden(act)
    }

    private fun isShoppingActivityIntent(intent: Intent?): Boolean =
        intent?.component?.className == SHOP_ACTIVITY

    // Mini-player activation uses only a confirmed native video rendering host.
    // Never remove or resize the whole feed holder or the bottom tabs.
    private fun findLiveVideoHost(act: Activity): View? {
        if (act.javaClass.name != MAIN_TAB_ACTIVITY &&
            act.javaClass.name !=
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity") return null
        val candidates = ArrayList<View>()
        val inspected = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<View, Boolean>()
        )
        var visibleRenderers = 0
        var nativeHosts = 0

        fun selectRendererHost(renderer: View) {
            visibleRenderers++
            var host = renderer.parent as? ViewGroup ?: return
            // A renderer itself cannot be reparented safely. Prefer its
            // existing direct video frame and keep the same player instance.
            if (!host.isShown || host.width < dp(act, 80f) ||
                host.height < dp(act, 110f)) return
            if (host === act.window.decorView || host.id == android.R.id.content) return
            val grandParent = host.parent as? ViewGroup
            // Host names used by the native VideoShop player vary by build.
            // If an ancestor IS the VideoShop host, prefer it.
            for (i in 0 until 3) {
                if (host.javaClass.name ==
                    "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") break
                if (grandParent?.javaClass?.name ==
                    "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") {
                    host = grandParent
                }
                break
            }
            if (host.parent is ViewGroup && !candidates.contains(host)) candidates.add(host)
        }

        fun find(view: View?, depth: Int) {
            if (view == null || depth > 45 || !view.isShown ||
                !inspected.add(view)) return
            val className = view.javaClass.name
            if (className == "com.ss.android.videoshop.mediaview.LayerHostMediaLayout") {
                if (view.width >= dp(act, 80f) && view.height >= dp(act, 110f) &&
                    view.parent is ViewGroup) {
                    candidates.add(view)
                    nativeHosts++
                }
            }
            if (view is android.view.TextureView || view is android.view.SurfaceView) {
                selectRendererHost(view)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)
            }
        }

        // Holder classes change between Hongguo versions. Treat the actual
        // attached rendering tree as authoritative, not obfuscated holder IDs.
        for ((holder, state) in shortVideoHolderSnapshot().asReversed()) {
            if (state == 2 || !isShortVideoHolderVisible(holder)) continue
            find(shortVideoHolderRoot(holder), 0)
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) {
            find(act.window.decorView, 0)
        }
        val chosen = candidates
            .filter { it.parent is ViewGroup && it.width >= dp(act, 80f) &&
                it.height >= dp(act, 110f) }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        LogUtil.info("应用内小窗扫描: nativeHosts=$nativeHosts" +
            " renderers=$visibleRenderers candidates=${candidates.size}" +
            " result=${chosen?.javaClass?.name ?: "none"}")
        if (chosen == null) LogUtil.incr("inAppMiniNoVideoCandidate")
        return chosen
    }

    private var gMiniTouchStartTab: String? = null
    private var gMiniTouchStartAt: Long = -1L
    private var gMiniTouchStartX: Float = 0f
    private var gMiniTouchStartY: Float = 0f

    /**
     * Home tabs sometimes handle MotionEvent directly instead of calling
     * View.performClick(). Observe Activity.dispatchTouchEvent before native
     * handling and resolve the bottom tab from its exact on-screen labels.
     */
    private fun nativeTabFromTouch(act: Activity, rawX: Float, rawY: Float): String? {
        if (act.javaClass.name != MAIN_TAB_ACTIVITY) return null
        val bar = findMallBottomBarRoot(act) ?: return null
        val origin = IntArray(2)
        try { bar.getLocationOnScreen(origin) } catch (_: Throwable) { return null }
        if (bar.width < dp(act, 180f) || bar.height < dp(act, 32f) ||
            rawX < origin[0] || rawX > origin[0] + bar.width ||
            rawY < origin[1] || rawY > origin[1] + bar.height) return null

        val names = setOf("首页", "剧场", "商城", "赚钱", "我的")
        val labeled = ArrayList<Pair<String, Float>>()
        fun scan(view: View?, depth: Int) {
            if (view == null || depth > 16 || !view.isShown) return
            val text = (view as? TextView)?.text?.toString()?.trim()
                ?: view.contentDescription?.toString()?.trim()
            if (text != null && text in names) {
                val loc = IntArray(2)
                try {
                    view.getLocationOnScreen(loc)
                    labeled.add(text to (loc[0] + view.width / 2f))
                } catch (_: Throwable) {}
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
            }
        }
        scan(bar, 0)
        val closest = labeled.minByOrNull { kotlin.math.abs(it.second - rawX) } ?: return null
        val slotWidth = bar.width.toFloat() / labeled.size.coerceAtLeast(3)
        if (kotlin.math.abs(closest.second - rawX) > slotWidth * 0.8f) return null
        return closest.first
    }

    private fun observeNativeTabTouch(
        ev: android.view.MotionEvent?, act: Activity?,
    ) {
        val activity = act ?: return
        if (!gMasterOn || !gMiniPlayerOn || ev == null ||
            activity.javaClass.name != MAIN_TAB_ACTIVITY) return
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gMiniTouchStartTab = nativeTabFromTouch(activity, ev.rawX, ev.rawY)
                gMiniTouchStartAt = ev.downTime
                gMiniTouchStartX = ev.rawX
                gMiniTouchStartY = ev.rawY
                if (gMiniTouchStartTab != null) {
                    LogUtil.info("应用内小窗: 底部 Tab 触摸开始=" + gMiniTouchStartTab)
                    LogUtil.incr("inAppMiniTabTouchDown")
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val tab = gMiniTouchStartTab
                gMiniTouchStartTab = null
                if (tab != null && ev.downTime == gMiniTouchStartAt &&
                    kotlin.math.abs(ev.rawX - gMiniTouchStartX) < dp(activity, 24f) &&
                    kotlin.math.abs(ev.rawY - gMiniTouchStartY) < dp(activity, 24f) &&
                    nativeTabFromTouch(activity, ev.rawX, ev.rawY) == tab) {
                    beforeNativeTabAction(activity, tab, "native-touch")
                }
            }
            android.view.MotionEvent.ACTION_CANCEL,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> gMiniTouchStartTab = null
        }
    }

    private fun nativeTabAction(view: View?): String? {
        val act = gCurrentActivity ?: return null
        if (act.javaClass.name != MAIN_TAB_ACTIVITY || view == null) return null
        val bottom = findMallBottomBarRoot(act) ?: return null
        val labels = listOf("首页", "剧场", "商城", "赚钱", "我的")
        var current: View = view
        var depth = 0
        while (current !== bottom && depth++ < 16) {
            val parent = current.parent as? ViewGroup ?: break
            if (parent.childCount in 3..7) {
                val distinctLabels = labels.count { name ->
                    (0 until parent.childCount).any { idx ->
                        hasExactText(parent.getChildAt(idx), name)
                    }
                }
                if (distinctLabels >= 2) {
                    return labels.firstOrNull { hasExactText(current, it) }
                }
            }
            current = parent
        }
        return null
    }

    private fun nativeTabClickForLabel(act: Activity, label: String): View? {
        val root = findMallBottomBarRoot(act) ?: return null
        val match = mutableListOf<View>()
        fun scan(view: View?, depth: Int) {
            if (view == null || depth > 15) return
            if (view is TextView && view.text?.toString()?.trim() == label) {
                match.add(view)
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                scan(view.getChildAt(i), depth + 1)
            }
        }
        scan(root, 0)
        for (labelView in match) {
            var target: View = labelView
            repeat(7) {
                if (target.isClickable && target !== root) return target
                val parent = target.parent as? View ?: return@repeat
                if (parent === root) return@repeat
                target = parent
            }
        }
        return null
    }

    private fun beforeNativeTabClick(view: View?) {
        val act = gCurrentActivity ?: return
        val direct = nativeTabAction(view)
        val centerTab = if (direct == null && view != null) {
            val loc = IntArray(2)
            try {
                view.getLocationOnScreen(loc)
                nativeTabFromTouch(act, loc[0] + view.width / 2f,
                    loc[1] + view.height / 2f)
            } catch (_: Throwable) { null }
        } else direct
        if (centerTab != null) beforeNativeTabAction(act, centerTab, "performClick")
    }

    private fun beforeNativeTabAction(act: Activity, tab: String, source: String) {
        if (!gMasterOn || !gMiniPlayerOn) return
        LogUtil.info("应用内小窗 Tab: $tab, event=$source, active=" +
            InAppMiniPlayer.activeFor(act))
        LogUtil.incr("inAppMiniTabRecognized")
        if (tab == "首页") {
            if (InAppMiniPlayer.activeFor(act)) InAppMiniPlayer.restore()
            return
        }
        if (tab == "商城" && isShoppingMallDisabled()) return
        if (InAppMiniPlayer.activeFor(act)) return
        val player = findLiveVideoHost(act)
        if (player == null) {
            LogUtil.warn("应用内小窗未启动: Tab 已识别，但找不到正在显示的视频渲染容器")
            return
        }
        if (InAppMiniPlayer.dock(act, player)) {
            mainHandler.postDelayed({
                if (InAppMiniPlayer.activeFor(act)) {
                    InAppMiniPlayer.resumeAfterTabSwitch()
                }
            }, 320L)
        }
    }

    /** Native detail screens are separate Activities and therefore bypass
     * the bottom-tab mini activation. Dock BEFORE the activity launch, then
     * transfer the SAME video window to the detail Activity onResume.
     * Never start a second video engine or seek to a new media source. */
    private fun beforeNativeDramaDetailsLaunch(source: Activity?, intent: Intent?) {
        val owner = source ?: return
        if (!gMasterOn || !gMiniPlayerOn ||
            owner.javaClass.name !in setOf(
                MAIN_TAB_ACTIVITY,
                "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
            )) return
        val destination = intent?.component?.className ?: return
        if (destination !in setOf(
            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity"
        )) return
        if (!InAppMiniPlayer.activeFor(owner)) {
            val videoHost = findLiveVideoHost(owner)
            if (videoHost == null || !InAppMiniPlayer.dock(owner, videoHost)) {
                LogUtil.warn("应用内小窗：详情页跳转时未找到原视频渲染容器")
                LogUtil.incr("inAppMiniDetailNoRenderer")
                return
            }
        }
        InAppMiniPlayer.prepareForDetails(owner)
    }

    private val mainBottomNavLabels = setOf("首页", "剧场", "商城", "赚钱", "我的")

    private fun isMainBottomNavContainer(v: View?): Boolean {
        if (keepNativeNavigation()) return false
        if (gPkg != TargetNames.CN_PACKAGE || v !is ViewGroup || !isInsideCurrentActivityDecor(v)) return false
        synchronized(gKnownMainBottomNavViews) { if (gKnownMainBottomNavViews.containsKey(v)) return true }
        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return false
        val hDp = height / density
        if (hDp !in 38f..105f) return false
        val dm = try { v.resources.displayMetrics } catch (_: Throwable) { null } ?: return false
        if (width.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat() < 0.90f) return false
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }
        if (loc[1] < dm.heightPixels * 0.55f) return false
        val labels = collectUiTexts(v, 5).map { it.trim() }.toSet()
        val hitCount = mainBottomNavLabels.count { target -> labels.any { it == target } }

        var structuralSlots = 0
        if (v.childCount in 4..7) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                val cw = if (child.width > 0) child.width else child.measuredWidth
                val ch = if (child.height > 0) child.height else child.measuredHeight
                val cwRatio = cw.toFloat() / dm.widthPixels.coerceAtLeast(1).toFloat()
                val chDp = ch / density
                if (cwRatio in 0.12f..0.30f && chDp >= 24f) structuralSlots++
            }
        }
        val nearBottom = loc[1] + height >= dm.heightPixels * 0.90f
        val structuralMatch = nearBottom && (
            structuralSlots >= 4 ||

                (v.childCount == 5 && hDp in 48f..88f)
            )
        if (hitCount < 4 && !structuralMatch) return false
        synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews[v] = true }
        LogUtil.info("main bottom nav recognized: ${v.javaClass.name}, h=${"%.1f".format(hDp)}dp, labels=$hitCount, slots=$structuralSlots")
        return true
    }

    private fun isKnownMainBottomNav(v: View?): Boolean = if (v == null) false else
        synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews.containsKey(v) }

    private fun shouldCollapseControl(v: View?): Boolean {
        if (v == null) return false
        val idName = viewEntryName(v)

        if (idName == "iu1" || idName == "is7") return true
        if (isKnownFullSeriesEntry(v) || isHomeFullSeriesEntry(v)) return true
        if (isNativeMainBottomFrame(v) || isNativeVideoFeedBottomMask(v)) return true
        if (isKnownBottomBackdrop(v) || isHomeBottomBackdropMarker(v)) return true
        return isMainBottomNavContainer(v)
    }

    private fun installSeriesToolbarLayoutGuard(v: View?) {
        if (gNames.seriesToolbarProfile == "none" || v !is ViewGroup) return
        if (v.javaClass.name != "androidx.constraintlayout.widget.ConstraintLayout") return
        try { if (v.id > 0) return } catch (_: Throwable) {}
        synchronized(gSeriesGuardedViews) {
            if (gSeriesGuardedViews.containsKey(v)) return
            gSeriesGuardedViews[v] = true
        }
        try {
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) return@addOnLayoutChangeListener
                val kind = seriesToolbarKind(view)
                if (kind != 0) rememberSeriesToolbar(view, kind)
                if (kind != 0 && view.visibility == View.VISIBLE) {
                    rememberViewState(view)
                    setModuleVisibility(view, View.INVISIBLE)
                    LogUtil.incr("seriesLayoutGuardHide")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun installSeriesToolbarGuardsInTree(v: View?) {
        if (v == null || gNames.seriesToolbarProfile == "none") return
        installSeriesToolbarLayoutGuard(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) installSeriesToolbarGuardsInTree(v.getChildAt(i))
        }
    }

    private fun seriesToolbarKind(v: View?): Int {
        if (gNames.seriesToolbarProfile == "none" || v !is ViewGroup) return 0
        if (v.javaClass.name != "androidx.constraintlayout.widget.ConstraintLayout") return 0

        if (!isInsideCurrentActivityDecor(v)) return 0
        try { if (v.id > 0) return 0 } catch (_: Throwable) {}
        if (v.paddingLeft != 0 || v.paddingTop != 0 || v.paddingRight != 0 || v.paddingBottom != 0) return 0
        try {
            val lp = v.layoutParams
            if (lp is ViewGroup.MarginLayoutParams &&
                (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0)) return 0
        } catch (_: Throwable) {}

        val density = try { v.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width <= 0 || height <= 0) return 0
        val widthDp = width / density
        val heightDp = height / density
        val parentWidth = ((v.parent as? View)?.width ?: 0).takeIf { it > 0 }
            ?: try { v.resources.displayMetrics.widthPixels } catch (_: Throwable) { width }
        val widthRatio = if (parentWidth > 0) width.toFloat() / parentWidth.toFloat() else 1f
        val totalInsetDp = if (parentWidth >= width) (parentWidth - width) / density else 0f

        when (gNames.seriesToolbarProfile) {
            "auto" -> {
                val act = gCurrentActivity
                if (act?.javaClass?.name !=
                    "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
                    return 0
                val loc = IntArray(2)
                try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return 0 }
                val screenHeight = act.resources.displayMetrics.heightPixels
                val validShape = heightDp in 33f..58f &&
                    widthRatio >= 0.82f && v.childCount in 2..16
                if (!validShape) return 0
                val labels = collectUiTexts(v, 4).map { it.replace(" ", "") }
                val contentEvidence = labels.any { t ->
                    t.contains("全集") || t.contains("选集") ||
                        t.contains("第") && t.contains("集") ||
                        t.contains("播放") || t.contains("返回")
                } || (0 until v.childCount).any { idx ->
                    v.getChildAt(idx) is android.widget.SeekBar
                }
                // Do not hide a fullscreen dialog or a random panel just
                // because it happens to have the same size as a toolbar.
                if (!contentEvidence) return 0
                if (loc[1] >= 0 && loc[1] < screenHeight * 0.17f) return 1
                if (loc[1] > screenHeight * 0.62f &&
                    loc[1] < screenHeight * 0.97f) return 2
            }
            "oversea73132" -> {
                val isTop = v.childCount == 9 && heightDp in 40f..48f &&
                    (widthDp >= 400f || widthRatio >= 0.97f)
                if (isTop) return 1
                val isBottom = v.childCount == 3 && heightDp in 36f..44f &&
                    (widthDp in 378f..408f || totalInsetDp in 24f..42f)
                if (isBottom) return 2
            }
            "cn73318" -> {

                val isTop = heightDp in 41f..47f && (widthDp >= 410f || widthRatio >= 0.97f)
                if (isTop) return 1

                val isBottom = heightDp in 36f..44f &&
                    (widthDp in 378f..408f || totalInsetDp in 24f..42f)
                if (isBottom) return 2
            }
        }
        return 0
    }

    private fun rememberSeriesToolbar(v: View?, kind: Int) {
        if (v == null || kind == 0) return
        synchronized(gKnownSeriesToolbars) { gKnownSeriesToolbars[v] = kind }
    }

    private fun knownSeriesToolbarSnapshot(): List<Pair<View, Int>> = synchronized(gKnownSeriesToolbars) {
        gKnownSeriesToolbars.entries.mapNotNull { (view, kind) ->
            if (view.windowToken != null || view.isAttachedToWindow) Pair(view, kind) else null
        }
    }

    private fun hideSeriesToolbarView(v: View?) {
        if (v == null || !gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) return
        val kind = seriesToolbarKind(v)
        if (kind == 0) return
        rememberSeriesToolbar(v, kind)
        try {
            if (v.visibility == View.VISIBLE) {
                rememberViewState(v)
                setModuleVisibility(v, View.INVISIBLE)
                LogUtil.incr(if (kind == 1) "seriesTopHide" else "seriesBottomHide")
            }
        } catch (_: Throwable) {}
    }

    private fun scanTreePlayer(v: View?) {
        if (v == null || !gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) return
        if (seriesToolbarKind(v) != 0) { hideSeriesToolbarView(v); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreePlayer(v.getChildAt(i))
    }

    private fun scanTreePlayerRestore(v: View?) {
        if (v == null) return
        val knownKind = synchronized(gKnownSeriesToolbars) { gKnownSeriesToolbars[v] ?: 0 }
        val kind = if (knownKind != 0) knownKind else seriesToolbarKind(v)
        if (kind != 0) {
            rememberSeriesToolbar(v, kind)
            restoreView(v)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreePlayerRestore(v.getChildAt(i))
    }
    private inline fun <T> internalViewMutation(block: () -> T): T {
        val old = gInternalViewMutation.get() == true
        gInternalViewMutation.set(true)
        return try { block() } finally { gInternalViewMutation.set(old) }
    }

    private fun rememberViewState(v: View, desiredVisibility: Int? = null) {
        synchronized(gSavedViewStates) {
            val old = gSavedViewStates[v]
            if (old == null) {
                gSavedViewStates[v] = SavedViewState(desiredVisibility ?: v.visibility, v.alpha)
            } else if (desiredVisibility != null) {

                old.visibility = desiredVisibility
            }
        }
    }

    private fun setModuleVisibility(v: View, visibility: Int) {
        internalViewMutation { v.visibility = visibility }
    }

    private fun blindView(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        try {

            if (v.visibility != View.VISIBLE) return
            rememberViewState(v)
            val collapse = shouldCollapseControl(v)
            setModuleVisibility(v, if (collapse) View.GONE else View.INVISIBLE)
            if (collapse) {
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
                LogUtil.incr("collapseControl")
            }
            LogUtil.incr("blindOK")
        } catch (_: Exception) {}
    }

    private fun restoreView(v: View?) {
        if (v == null) return
        val state = synchronized(gSavedViewStates) { gSavedViewStates.remove(v) } ?: return
        try {
            internalViewMutation {
                v.alpha = state.alpha
                v.visibility = state.visibility
            }
        } catch (_: Exception) {}
        try { v.requestLayout() } catch (_: Exception) {}
        try { (v.parent as? View)?.requestLayout() } catch (_: Exception) {}
    }

    private fun restoreAllSavedViews() {
        restoreFeedViewportBottomMargins(clear = true)
        restoreBottomLayoutReclaim()
        val entries = synchronized(gSavedViewStates) {
            val copy = gSavedViewStates.entries.map { Pair(it.key, SavedViewState(it.value.visibility, it.value.alpha)) }
            gSavedViewStates.clear()
            copy
        }
        for ((v, state) in entries) {
            try {
                internalViewMutation {
                    v.alpha = state.alpha
                    v.visibility = state.visibility
                }
                v.requestLayout()
                (v.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        synchronized(gForcedShortMaskVisibility) { gForcedShortMaskVisibility.clear() }
        synchronized(gCleanMaskViews) { gCleanMaskViews.clear() }
        if (entries.isNotEmpty()) LogUtil.info("restore saved views: ${entries.size}")
    }
    private fun forceOnePauseRestoreView(v: View?) {
        if (v == null) return
        try {
            synchronized(gPauseForcedStates) {
                if (!gPauseForcedStates.containsKey(v)) {
                    gPauseForcedStates[v] = PauseForcedState(
                        v.visibility, v.alpha, v.translationX, v.translationY,
                    )
                }
            }
            internalViewMutation {
                v.visibility = View.VISIBLE
                v.alpha = 1f
                v.translationX = 0f
                v.translationY = 0f
            }
            v.requestLayout()
        } catch (_: Throwable) {}
    }

    private fun scanTreePauseRestore(v: View?) {
        if (v == null) return
        try {
            if (v.id > 0 && gPauseRestoreIdSet.contains(v.id)) {
                forceOnePauseRestoreView(v)
            }
        } catch (_: Throwable) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreePauseRestore(v.getChildAt(i))
    }

    private fun forcePauseEpisodeSelector(root: View?) {
        if (root == null) return
        if (root is TextView) {
            val text = try { root.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val hit = text.contains("选集") || text.equals("Episodes", true) || text.startsWith("Episode", true)
            if (hit) {
                forceOnePauseRestoreView(root)

                var node: View? = root.parent as? View
                var depth = 0
                var candidate: View? = null
                while (node != null && depth++ < 4) {
                    if (node is ViewGroup && node.width > 0 && node.height > 0) {
                        val density = try { node.resources.displayMetrics.density.coerceAtLeast(0.1f) } catch (_: Throwable) { 1f }
                        val h = node.height / density
                        if (h in 30f..64f) { candidate = node; break }
                    }
                    node = node.parent as? View
                }
                if (candidate != null) {
                    forceOnePauseRestoreView(candidate)
                    rememberSeriesToolbar(candidate, 2)
                }
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) forcePauseEpisodeSelector(root.getChildAt(i))
    }

    private fun forcePauseRightAgencyTree(agency: Any) {
        val rightRoot = findFieldValue(agency, "p") as? View ?: return
        forceOnePauseRestoreView(rightRoot)
        scanTreePauseRestore(rightRoot)

        val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null }
        var node = rightRoot.parent as? View
        var depth = 0
        while (node != null && depth++ < 3) {
            if (node === decor) break
            val suppressed = try {
                node.visibility != View.VISIBLE ||
                    node.alpha < 0.99f ||
                    java.lang.Math.abs(node.translationX) > 1f ||
                    java.lang.Math.abs(node.translationY) > 1f
            } catch (_: Throwable) { false }
            if (suppressed) forceOnePauseRestoreView(node)
            node = node.parent as? View
        }
    }

    private fun forcePauseRestoreControls() {
        if (!gMasterOn || !gRestoreControlsOnPause || !gVideoPaused) return

        if (gPlayerOn) {
            for ((toolbar, _) in knownSeriesToolbarSnapshot()) {
                if (isInsideCurrentActivityDecor(toolbar)) forceOnePauseRestoreView(toolbar)
            }
            val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null }
            forcePauseEpisodeSelector(decor)
        }

        if (gControlOn) {
            for (root in collectAllWindows()) scanTreePauseRestore(root)

            for (agency in rightViewAgencySnapshot()) {
                try {
                    forcePauseRightAgencyTree(agency)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun restorePauseForcedViews() {
        val entries = synchronized(gPauseForcedStates) {
            val copy = gPauseForcedStates.entries.map { Pair(it.key, it.value) }
            gPauseForcedStates.clear()
            copy
        }
        for ((v, state) in entries) {
            try {
                internalViewMutation {
                    v.visibility = state.visibility
                    v.alpha = state.alpha
                    v.translationX = state.translationX
                    v.translationY = state.translationY
                }
                v.requestLayout()
            } catch (_: Throwable) {}
        }
        if (entries.isNotEmpty()) LogUtil.info("pause restore temporary states restored: ${entries.size}")
    }

    private fun isRedGuoAd(v: View?): Boolean {
        if (v == null) return false
        try { if (v is TextView && (v.text?.toString() ?: "").contains("红果")) return true } catch (_: Exception) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) if (isRedGuoAd(v.getChildAt(i))) return true
        return false
    }
    private fun scanTreeQuick(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        LogUtil.incr("scanTree")

        if (isNativeMainBottomFrame(v)) { collapseNativeMainBottomFrame(v); return }
        if (isNativeVideoFeedBottomMask(v)) { collapseNativeVideoFeedBottomMask(v); return }
        if (isHomeBottomBackdropMarker(v)) { collapseHomeBottomBackdrop(v); return }
        if (isKnownBottomBackdrop(v)) { blindView(v); return }

        if (isMainBottomNavContainer(v)) { blindView(v); return }
        if (quickMatch(v)) { blindView(v); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeQuick(v.getChildAt(i))
    }
    private fun restoreAllControls() {
        mainHandler.post {
            restoreFeedViewportBottomMargins(clear = false)
            restoreBottomLayoutReclaim()
            restoreShortVideoNativeControls()
            val roots = collectAllWindows()
            for (root in roots) {
                scanTreeRestore(root)
                scanTreePlayerRestore(root)
                scanTreeProgressRestore(root)
            }

            if (gRestoreControlsOnPause && gVideoPaused) forcePauseRestoreControls()
        }
    }

    private fun registerShortVideoHolder(holder: Any?, playbackState: Int? = null) {
        if (holder == null) return
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(gShortVideoHolders) {
            gShortVideoHolders.removeAll { it.ref.get() == null }
            val old = gShortVideoHolders.firstOrNull { it.ref.get() === holder }
            if (old != null) {
                gShortVideoHolders.remove(old)
                if (playbackState != null) old.playbackState = playbackState
                old.updatedAt = now
                gShortVideoHolders.add(old)
            } else {
                gShortVideoHolders.add(
                    ShortVideoHolderState(
                        java.lang.ref.WeakReference(holder),
                        playbackState ?: 0,
                        now,
                    )
                )
            }
        }
    }

    private fun shortVideoHolderSnapshot(): List<Pair<Any, Int>> = synchronized(gShortVideoHolders) {
        val result = mutableListOf<Pair<Any, Int>>()
        val iterator = gShortVideoHolders.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val holder = item.ref.get()
            if (holder == null) iterator.remove() else result.add(Pair(holder, item.playbackState))
        }
        result
    }

    private fun findFieldValue(instance: Any, fieldName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return null
    }

    private fun registerRightViewAgency(agency: Any?) {
        if (agency == null) return
        synchronized(gRightViewAgencies) {
            val it = gRightViewAgencies.iterator()
            while (it.hasNext()) {
                val current = it.next().get()
                if (current == null || current === agency) it.remove()
            }
            gRightViewAgencies.add(java.lang.ref.WeakReference(agency))
        }
    }

    private fun rightViewAgencySnapshot(): List<Any> = synchronized(gRightViewAgencies) {
        val result = mutableListOf<Any>()
        val it = gRightViewAgencies.iterator()
        while (it.hasNext()) {
            val agency = it.next().get()
            if (agency == null) it.remove() else result.add(agency)
        }
        result
    }

    private fun rightViewAgencyRoot(agency: Any): View? = findFieldValue(agency, "p") as? View

    private fun isRightViewAgencyVisible(agency: Any): Boolean {
        val rightView = rightViewAgencyRoot(agency) ?: return false
        return try {
            rightView.windowToken != null && rightView.width > 0 && rightView.height > 0
        } catch (_: Throwable) { false }
    }

    private fun isAgencyInCurrentActivityWindow(agency: Any): Boolean {
        val rightView = rightViewAgencyRoot(agency) ?: return false
        val currentToken = try { gCurrentActivity?.window?.decorView?.windowToken } catch (_: Throwable) { null }

        return try {
            if (currentToken == null) {
                rightView.windowToken != null && rightView.isAttachedToWindow
            } else {
                rightView.windowToken === currentToken && rightView.isAttachedToWindow
            }
        } catch (_: Throwable) { false }
    }

    private fun openCurrentCommentFromDoubleTap(): Boolean {
        if (!gMasterOn || !gDoubleTapCommentOn) return false
        val snapshot = rightViewAgencySnapshot()
        if (snapshot.isEmpty()) return false

        val currentWindow = snapshot.filter { isAgencyInCurrentActivityWindow(it) }.asReversed()
        val visibleFallback = snapshot.filter { isRightViewAgencyVisible(it) }.asReversed()
        val candidates = if (currentWindow.isNotEmpty()) currentWindow else visibleFallback

        for (agency in candidates) {
            try {
                var clazz: Class<*>? = agency.javaClass
                var method: java.lang.reflect.Method? = null
                while (clazz != null && method == null) {
                    method = try {
                        clazz.getDeclaredMethod(
                            gNames.rightViewAgencyEventMethod,
                            android.os.Bundle::class.java,
                            String::class.java,
                        ).apply { isAccessible = true }
                    } catch (_: Throwable) { null }
                    clazz = clazz.superclass
                }
                if (method == null) continue
                method.invoke(agency, android.os.Bundle(), "show_comment_dialog")
                LogUtil.incr("doubleTapOpenComment")
                LogUtil.info("双击评论事件已发送 | profile=${gNames.profileId} | agency=${agency.javaClass.name}.${gNames.rightViewAgencyEventMethod}")
                return true
            } catch (e: Throwable) {
                LogUtil.warn("双击打开评论区失败(${agency.javaClass.name}): $e")
            }
        }
        return false
    }

    private fun registerShortSeriesFragment(fragment: Any?) {
        if (fragment == null) return
        synchronized(gShortSeriesFragments) {
            gShortSeriesFragments.removeAll { it.get() == null }
            val old = gShortSeriesFragments.firstOrNull { it.get() === fragment }
            if (old != null) gShortSeriesFragments.remove(old)
            gShortSeriesFragments.add(java.lang.ref.WeakReference(fragment))
        }
    }

    private fun shortSeriesFragmentSnapshot(): List<Any> = synchronized(gShortSeriesFragments) {
        val result = mutableListOf<Any>()
        val iterator = gShortSeriesFragments.iterator()
        while (iterator.hasNext()) {
            val fragment = iterator.next().get()
            if (fragment == null) iterator.remove() else result.add(fragment)
        }
        result
    }

    private fun activityFromFragment(fragment: Any?): Activity? {
        if (fragment == null) return null
        return try {
            fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
        } catch (_: Throwable) { null }
    }

    private fun shortVideoHolderRoot(holder: Any): View? {
        try {
            val root = holder.javaClass.getMethod("getRootView").invoke(holder) as? View
            if (root != null) return root
        } catch (_: Throwable) {}
        return findFieldValue(holder, "itemView") as? View
    }

    private fun isShortVideoHolderVisible(holder: Any): Boolean {
        val root = shortVideoHolderRoot(holder) ?: return false
        return try {
            if (!root.isShown || root.windowToken == null || root.width <= 0 || root.height <= 0) return false
            val visible = Rect()
            if (!root.getGlobalVisibleRect(visible)) return false
            val visibleArea = visible.width().toLong() * visible.height().toLong()
            val totalArea = root.width.toLong() * root.height.toLong()
            visibleArea * 4L >= totalArea
        } catch (_: Throwable) { false }
    }

    private fun detectPausedFromShortVideoHolders(): Boolean? {
        for ((holder, state) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            when (state) {
                1 -> return false
                2 -> return true
            }
            try {
                val playing = holder.javaClass.getMethod("isVideoPlaying").invoke(holder) as? Boolean
                if (playing == true) return false
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun shouldForceShortVideoCleanMask(): Boolean {
        return gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)
    }

    private fun rememberAndForceShortVideoMaskInvisible(holder: Any) {
        try {
            val mask = findFieldValue(holder, gNames.shortMaskField) as? View ?: return
            if (mask.visibility != View.VISIBLE) return
            synchronized(gForcedShortMaskVisibility) {
                if (!gForcedShortMaskVisibility.containsKey(mask)) {
                    gForcedShortMaskVisibility[mask] = mask.visibility
                }
            }
            rememberViewState(mask)
            synchronized(gCleanMaskViews) { gCleanMaskViews[mask] = true }
            setModuleVisibility(mask, View.INVISIBLE)
            LogUtil.incr("shortMaskDirectFallback")
        } catch (e: Throwable) {
            LogUtil.warn("force short-video mask invisible failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun restoreForcedShortVideoMask(holder: Any) {
        try {
            val mask = findFieldValue(holder, gNames.shortMaskField) as? View ?: return
            val original = synchronized(gForcedShortMaskVisibility) {
                gForcedShortMaskVisibility.remove(mask)
            } ?: return

            synchronized(gCleanMaskViews) { gCleanMaskViews.remove(mask) }
            val saved = synchronized(gSavedViewStates) { gSavedViewStates[mask] }
            if (saved != null) restoreView(mask) else internalViewMutation { mask.visibility = original }
            LogUtil.incr("shortMaskDirectRestore")
        } catch (e: Throwable) {
            LogUtil.warn("restore short-video mask failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun setOneShortVideoMaskClear(holder: Any, clear: Boolean) {
        try {

            if (clear) rememberAndForceShortVideoMaskInvisible(holder)
            else restoreForcedShortVideoMask(holder)
        } catch (e: Throwable) {
            LogUtil.warn("set short-video mask clear=$clear failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun syncShortVideoMasks() {
        val holders = shortVideoHolderSnapshot().asReversed()
        if (shouldForceShortVideoCleanMask()) {
            var handledVisibleHolder = false
            for ((holder, _) in holders) {
                if (!isShortVideoHolderVisible(holder)) continue
                setOneShortVideoMaskClear(holder, true)
                handledVisibleHolder = true
            }

            if (!handledVisibleHolder) holders.firstOrNull()?.first?.let {
                setOneShortVideoMaskClear(it, true)
            }
            return
        }

        if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) {
            for ((holder, _) in holders) restoreForcedShortVideoMask(holder)
        }
    }

    private fun registerCnHomeFragment(fragment: Any?) {
        if (fragment == null || gPkg != "com.phoenix.read") return
        synchronized(gCnHomeFragments) {
            gCnHomeFragments.removeAll { it.get() == null }
            val old = gCnHomeFragments.firstOrNull { it.get() === fragment }
            if (old != null) gCnHomeFragments.remove(old)
            gCnHomeFragments.add(java.lang.ref.WeakReference(fragment))
        }
    }

    private fun cnHomeFragmentSnapshot(): List<Any> = synchronized(gCnHomeFragments) {
        val result = mutableListOf<Any>()
        val it = gCnHomeFragments.iterator()
        while (it.hasNext()) {
            val item = it.next().get()
            if (item == null) it.remove() else result.add(item)
        }
        result
    }

    private fun forceCnHomeFragmentMaskInvisible(fragment: Any) {
        if (gPkg != "com.phoenix.read" || !shouldForceShortVideoCleanMask()) return
        try {
            val fieldName = gNames.homeFragmentMaskField
            if (fieldName.isBlank()) return
            val mask = findFieldValue(fragment, fieldName) as? View ?: return
            if (mask.visibility != View.VISIBLE) return
            rememberViewState(mask)
            synchronized(gCleanMaskViews) { gCleanMaskViews[mask] = true }
            setModuleVisibility(mask, View.INVISIBLE)
            LogUtil.incr("homeFragmentMaskHide")
        } catch (e: Throwable) {
            LogUtil.warn("home fragment mask hide failed: $e")
        }
    }

    private fun syncCnHomeFragmentMasks() {
        if (gPkg != "com.phoenix.read" || !shouldForceShortVideoCleanMask()) return
        for (fragment in cnHomeFragmentSnapshot().asReversed()) {
            forceCnHomeFragmentMaskInvisible(fragment)
        }
    }

    private fun setOneShortVideoControlsVisible(holder: Any, visible: Boolean) {
        if (!visible) return
        var managerHandled = false
        try {
            val cleanScreenManager = findFieldValue(holder, gNames.shortCleanManagerField)
            if (cleanScreenManager != null) {
                cleanScreenManager.javaClass.getDeclaredMethod("b", Boolean::class.java).apply {
                    isAccessible = true
                }.invoke(cleanScreenManager, false)
                managerHandled = true
            }
        } catch (e: Throwable) {
            LogUtil.warn("exit short-video clean screen failed: $e")
        }

        try {
            holder.javaClass.getMethod(gNames.shortControlsMethod, Boolean::class.java, Boolean::class.java)
                .invoke(holder, false, true)
        } catch (e: Throwable) {
            if (!managerHandled) {
                LogUtil.warn("show short-video controls failed: ${holder.javaClass.name}: $e")
            }
        }
    }

    private fun restoreShortVideoNativeControls() {
        val holders = shortVideoHolderSnapshot().asReversed()
        var restoredVisibleHolder = false
        for ((holder, _) in holders) {
            if (!isShortVideoHolderVisible(holder)) continue
            setOneShortVideoControlsVisible(holder, true)
            restoreForcedShortVideoMask(holder)
            shortVideoHolderRoot(holder)?.requestLayout()
            restoredVisibleHolder = true
        }

        if (!restoredVisibleHolder) holders.firstOrNull()?.first?.let {
            setOneShortVideoControlsVisible(it, true)
            restoreForcedShortVideoMask(it)
        }
    }

    private fun registerVideoToolbarLayer(layer: Any?) {
        if (layer == null) return
        var added = false
        synchronized(gVideoToolbarLayers) {
            gVideoToolbarLayers.removeAll { it.get() == null }
            val old = gVideoToolbarLayers.firstOrNull { it.get() === layer }
            if (old != null) {

                gVideoToolbarLayers.remove(old)
            } else {
                added = true
            }
            gVideoToolbarLayers.add(java.lang.ref.WeakReference(layer))
        }
        if (added) LogUtil.info("video layer registered: ${layer.javaClass.name}")
        if (added && gMasterOn && gRestoreControlsOnPause && gVideoPaused) {
            mainHandler.post { setOneVideoToolbarVisible(layer, true) }
        }
    }
    private fun videoToolbarLayerSnapshot(): List<Any> = synchronized(gVideoToolbarLayers) {
        val result = mutableListOf<Any>()
        val iterator = gVideoToolbarLayers.iterator()
        while (iterator.hasNext()) {
            val layer = iterator.next().get()
            if (layer == null) iterator.remove() else result.add(layer)
        }
        result
    }

    private fun registerToolbarBaseLayer(layer: Any?) {
        if (layer == null) return
        synchronized(gToolbarBaseLayers) {
            gToolbarBaseLayers.removeAll { it.get() == null }
            val old = gToolbarBaseLayers.firstOrNull { it.get() === layer }
            if (old != null) gToolbarBaseLayers.remove(old)
            gToolbarBaseLayers.add(java.lang.ref.WeakReference(layer))
        }
    }

    private fun toolbarBaseLayerSnapshot(): List<Any> = synchronized(gToolbarBaseLayers) {
        val out = mutableListOf<Any>()
        val it = gToolbarBaseLayers.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) it.remove() else out.add(v)
        }
        out
    }

    private fun setToolbarBaseVisible(visible: Boolean) {
        for (layer in toolbarBaseLayerSnapshot()) {
            try {
                var type: Class<*>? = layer.javaClass
                var method: java.lang.reflect.Method? = null
                while (type != null && method == null) {
                    method = try { type.getDeclaredMethod("a", Boolean::class.java) } catch (_: Throwable) { null }
                    type = type.superclass
                }
                method?.apply { isAccessible = true }?.invoke(layer, visible)
            } catch (_: Throwable) {}
        }
    }

    private fun setOneVideoToolbarVisible(layer: Any, visible: Boolean) {
        try {
            when (layer.javaClass.name) {
                "com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed" ->
                    layer.javaClass.getDeclaredMethod(gNames.fixedToolbarShowMethod, Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
                "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer" ->
                    layer.javaClass.getDeclaredMethod(gNames.customizeToolbarShowMethod, Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
            }
        } catch (e: Throwable) {
            LogUtil.warn("set video toolbar visible=$visible failed: ${layer.javaClass.name}: $e")
        }
    }

    private fun setVideoToolbarsVisible(visible: Boolean) {
        mainHandler.post {
            for (layer in videoToolbarLayerSnapshot()) {
                setOneVideoToolbarVisible(layer, visible)

                if (visible && layer.javaClass.name == "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer") {
                    try {
                        layer.javaClass.getDeclaredMethod(
                            gNames.customizeToolbarApplyMethod,
                            Boolean::class.java,
                            Boolean::class.java,
                            Boolean::class.java,
                        ).apply { isAccessible = true }.invoke(layer, true, false, false)
                    } catch (_: Throwable) {}
                }
            }
            setToolbarBaseVisible(visible)
        }
    }

    private fun detectPausedFromVideoLayers(): Boolean? {

        for (layer in videoToolbarLayerSnapshot().asReversed()) {
            try {
                val inquirer = layer.javaClass.getMethod("getVideoStateInquirer").invoke(layer) ?: continue
                val paused = inquirer.javaClass.getMethod("isPaused").invoke(inquirer) as? Boolean ?: false
                if (paused) return true
                val playing = inquirer.javaClass.getMethod("isPlaying").invoke(inquirer) as? Boolean ?: false
                if (playing) return false
            } catch (_: Throwable) {}
        }
        return null
    }
    private fun refreshVideoPauseState(reason: String, fallback: Boolean? = null) {
        mainHandler.postDelayed({
            val shortVideoState = detectPausedFromShortVideoHolders()
            val detected = shortVideoState ?: detectPausedFromVideoLayers() ?: fallback
            if (detected != null) {
                val source = if (shortVideoState != null) "short-holder" else "layer"
                setVideoPaused(detected, "$reason/$source")
            }
            else LogUtil.warn("video state undetermined: $reason")
        }, 60L)
    }

    private fun setVideoPaused(paused: Boolean, reason: String = "callback") {
        val changed = gVideoPaused != paused
        gVideoPaused = paused
        gLastVideoStateAt = android.os.SystemClock.uptimeMillis()
        gLastVideoStateReason = reason

        if (!gMasterOn || !gRestoreControlsOnPause) return
        if (paused) {
            restoreAllControls()
            restoreNativeBottomWindowColor(gCurrentActivity)
            setVideoToolbarsVisible(true)
            forcePauseRestoreControls()

            val pauseRestoreDelays = if (gNames.profileId == "CN-7.3.2.32") {
                longArrayOf(120L, 360L, 720L)
            } else {
                longArrayOf(120L, 360L)
            }
            for (delay in pauseRestoreDelays) mainHandler.postDelayed({
                if (gMasterOn && gRestoreControlsOnPause && gVideoPaused) {
                    restoreAllControls()
                    setVideoToolbarsVisible(true)
                    forcePauseRestoreControls()
                }
            }, delay)
            if (changed) LogUtil.info("video paused: restore controls, reason=$reason")
        } else {
            restorePauseForcedViews()
            if (gPlayerOn) setVideoToolbarsVisible(false)
            mainHandler.post { scanAllWindows() }
            if (changed) LogUtil.info("video resumed: hide controls, reason=$reason")
        }
    }
    private fun scanTreeRestore(v: View?) {
        if (v == null) return
        if ((quickMatch(v) || isKnownMainBottomNav(v) || isKnownFullSeriesEntry(v) || isKnownBottomBackdrop(v)) && !isRedGuoAd(v)) restoreView(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeRestore(v.getChildAt(i))
    }

    private fun isProgressBar(v: View?): Boolean {
        if (v == null) return false
        try { val id = v.id; if (id > 0 && gProgressIdSet.contains(id)) return true } catch (_: Exception) {}
        try { if (v.javaClass.name == gNames.progressBar) return true } catch (_: Exception) {}
        if (!TargetNames.isUniversal(gNames)) return false
        val activity = gCurrentActivity ?: return false
        if (activity.javaClass.name !in setOf(
            "com.dragon.read.pages.main.MainFragmentActivity",
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
        )) return false
        // Generic video progress only: do not hide settings/loaders or
        // widgets unrelated to the currently visible video screen.
        if (v !is android.widget.SeekBar &&
            v !is android.widget.ProgressBar) return false
        if (!isInsideCurrentActivityDecor(v)) return false
        val dm = activity.resources.displayMetrics
        val w = if (v.width > 0) v.width else v.measuredWidth
        val h = if (v.height > 0) v.height else v.measuredHeight
        if (w < dm.widthPixels * 0.62f || h > dm.density * 42f) return false
        val loc = IntArray(2)
        try { v.getLocationOnScreen(loc) } catch (_: Throwable) { return false }
        return loc[1] > dm.heightPixels * 0.50f &&
            loc[1] < dm.heightPixels * 0.96f
    }
    private fun scanTreeProgress(v: View?) {
        if (v == null || !gMasterOn || !gProgressOff) return
        if (isProgressBar(v)) {
            try {
                if (v.visibility == View.VISIBLE) {
                    rememberViewState(v)
                    setModuleVisibility(v, View.GONE)
                    LogUtil.incr("progressHide")
                }
            } catch (_: Exception) {}
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeProgress(v.getChildAt(i))
    }
    private fun scanTreeProgressRestore(v: View?) {
        if (v == null) return
        if (isProgressBar(v)) restoreView(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeProgressRestore(v.getChildAt(i))
    }

    private fun collectAllWindows(): MutableList<View> {
        val roots = mutableListOf<View>()
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true
            val mViews = mViewsField.get(instance) as? ArrayList<View>
            if (mViews != null) roots.addAll(mViews)
        } catch (_: Throwable) {

        }
        try {
            val decor = gCurrentActivity?.window?.decorView
            if (decor != null && roots.none { it === decor }) roots.add(decor)
        } catch (_: Throwable) {}
        return roots
    }
    private fun scanAllWindows() {
        try {
            if (gCurrentActivity == null) return
            scanShoppingTabIfNeeded(gCurrentActivity)
            val decor = gCurrentActivity!!.window.decorView
            gNames.hideIdNames.forEach { resolveEntryId(it, decor) }
            gNames.progressIdNames.forEach { resolveEntryId(it, decor, gProgressIdSet, "进度条资源") }
            resolvePauseRestoreIds(decor)
            val allRoots = collectAllWindows()
            if (allRoots.isEmpty() && decor != null) allRoots.add(decor)
            for (root in allRoots) {
                if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) scanTreeQuick(root)
                else scanTreeRestore(root)

                if (root === decor) {
                    if (gMasterOn && gPlayerOn && !(gRestoreControlsOnPause && gVideoPaused)) scanTreePlayer(root)
                    else scanTreePlayerRestore(root)
                }
            }
            if (gMasterOn && gProgressOff && !(gRestoreControlsOnPause && gVideoPaused)) for (root in allRoots) scanTreeProgress(root)
            else for (root in allRoots) scanTreeProgressRestore(root)
            if (gMasterOn && gRefreshOff) for (root in allRoots) scanTreeRefreshAccessory(root)
            else if (!gRefreshOff) restoreRefreshAccessories()
            syncShortVideoMasks()
            syncCnHomeFragmentMasks()
            if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                enforceNativeMainBottomHidden(gCurrentActivity)
                enforceKnownFeedViewportBottomMargin()
            }
        } catch (_: Exception) {}
    }

    private var gScanRunnable: Runnable? = null
    private fun startPeriodicScan() {
        stopPeriodicScan()
        gScanRunnable = object : Runnable {
            override fun run() {
                try {
                    scanShoppingTabIfNeeded(gCurrentActivity)
                    if (gMasterOn && gRestoreControlsOnPause && gVideoPaused) {
                        forcePauseRestoreControls()
                    }
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val allRoots = collectAllWindows()
                        for (root in allRoots) scanTreeQuick(root)
                        syncShortVideoMasks()
                        syncCnHomeFragmentMasks()
                        enforceNativeMainBottomHidden(gCurrentActivity)
                        enforceKnownFeedViewportBottomMargin()
                    }
                    if (gMasterOn && gPlayerOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val decor = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null }
                        if (decor != null) scanTreePlayer(decor)
                    }
                    if (gMasterOn && gProgressOff && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val allRoots2 = collectAllWindows()
                        for (root in allRoots2) scanTreeProgress(root)
                    }
                    if (gMasterOn && gRefreshOff) {
                        val allRoots3 = collectAllWindows()
                        for (root in allRoots3) scanTreeRefreshAccessory(root)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 150)
            }
        }
        mainHandler.postDelayed(gScanRunnable!!, 150)
    }
    private fun stopPeriodicScan() {
        gScanRunnable?.let { mainHandler.removeCallbacks(it) }
        gScanRunnable = null
    }
    private fun applyToCurrent() { mainHandler.post { scanAllWindows() } }

    /**
     * 0 = three-button, 1 = two-button, 2 = gesture navigation.
     * Use both secure settings and framework overlay: if either indicates
     * buttons, preserve the button bar. Unknown modes are treated as buttons.
     */
    private fun isGestureNavigation(ctx: Context?): Boolean {
        gGestureNavigationCache?.let { return it }
        if (ctx == null) return false
        val secureMode = try {
            android.provider.Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", -1)
        } catch (_: Throwable) { -1 }
        val overlayMode = try {
            val resId = ctx.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (resId != 0) ctx.resources.getInteger(resId) else -1
        } catch (_: Throwable) { -1 }
        val gestural = if (secureMode == 0 || secureMode == 1 ||
            overlayMode == 0 || overlayMode == 1) false
        else secureMode == 2 || overlayMode == 2
        gGestureNavigationCache = gestural
        return gestural
    }

    private fun hasVerifiedUiProfile(): Boolean =
        TargetNames.isSupported(gPkg, gTargetVersionName)

    /** System status/navigation bars use Android framework APIs, not
     * obfuscated resource IDs. Valid even in universal mode. */
    private fun canApplySystemBars(): Boolean =
        hasVerifiedUiProfile() || TargetNames.isUniversal(gNames)

    private fun keepNativeNavigation(): Boolean =
        !hasVerifiedUiProfile() || !isGestureNavigation(appCtx ?: gCurrentActivity)

    private fun rememberOriginalStatusBar(act: Activity) {
        val window = act.window
        val decor = window.decorView
        synchronized(gOriginalStatusBars) {
            if (gOriginalStatusBars.containsKey(act)) return
            val light = if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.systemBarsAppearance
                    ?.and(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) ?: 0
            } else 0
            val visible = if (Build.VERSION.SDK_INT >= 30) try {
                decor.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars())
            } catch (_: Throwable) { null } else null
            gOriginalStatusBars[act] = OriginalStatusBar(
                flags = window.attributes.flags,
                systemUiVisibility = decor.systemUiVisibility,
                color = window.statusBarColor,
                cutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0,
                lightAppearance = light,
                wasVisible = visible,
            )
        }
    }

    private fun isWindowedMode(act: Activity?): Boolean {
        if (act == null) return false
        try {
            if (Build.VERSION.SDK_INT >= 24 && act.isInMultiWindowMode) return true
            if (Build.VERSION.SDK_INT >= 26 && act.isInPictureInPictureMode) return true

            if (Build.VERSION.SDK_INT >= 30) {
                val current = act.windowManager.currentWindowMetrics.bounds
                val maximum = act.windowManager.maximumWindowMetrics.bounds
                if (maximum.width() > 0 && maximum.height() > 0) {
                    val widthReduced = current.width().toLong() * 100L < maximum.width().toLong() * 88L
                    val heightReduced = current.height().toLong() * 100L < maximum.height().toLong() * 88L
                    if (widthReduced || heightReduced) return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun shouldHideStatusBar(): Boolean {
        return gMasterOn && gStatusOn
    }

    private fun updateSeriesMallTopMargin(act: Activity, statusHidden: Boolean) {
        // 'is7' is a known legacy resource, never touch it on a new APK.
        if (TargetNames.isUniversal(gNames)) return
        try {

            val topBarId = act.resources.getIdentifier("is7", "id", gPkg)
            if (topBarId == 0) return
            val topBar = act.findViewById<View>(topBarId) ?: return
            val params = topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (statusHidden) {
                synchronized(gSavedTopMargins) {
                    if (!gSavedTopMargins.containsKey(topBar)) gSavedTopMargins[topBar] = params.topMargin
                }
                if (params.topMargin != 0) {
                    params.topMargin = 0
                    topBar.layoutParams = params
                    topBar.requestLayout()
                }
            } else {
                val original = synchronized(gSavedTopMargins) { gSavedTopMargins.remove(topBar) } ?: return
                if (params.topMargin != original) {
                    params.topMargin = original
                    topBar.layoutParams = params
                    topBar.requestLayout()
                }
            }
        } catch (_: Exception) {}
    }

    private fun refreshOneShortVideoHolder(
        holder: Any,
        configuration: android.content.res.Configuration,
    ) {
        try {
            holder.javaClass.getMethod(gNames.shortConfigMethod, android.content.res.Configuration::class.java)
                .invoke(holder, configuration)

            holder.javaClass.getMethod(gNames.shortLayoutResetMethod).invoke(holder)
        } catch (_: Throwable) {}
        shortVideoHolderRoot(holder)?.requestLayout()
    }

    private fun refreshShortVideoWindowLayout(act: Activity) {
        val configuration = act.resources.configuration

        for (fragment in shortSeriesFragmentSnapshot().asReversed()) {
            if (activityFromFragment(fragment) !== act) continue
            for (fieldName in gNames.seriesLayoutFields) {
                val view = findFieldValue(fragment, fieldName) as? View ?: continue
                try { view.requestLayout() } catch (_: Throwable) {}
            }
            try {
                val pager = fragment.javaClass.getMethod(gNames.seriesPagerGetter).invoke(fragment) ?: continue
                val holder = pager.javaClass.getMethod(gNames.seriesHolderGetter).invoke(pager) ?: continue
                registerShortVideoHolder(holder)
                refreshOneShortVideoHolder(holder, configuration)
            } catch (_: Throwable) {}
        }

        for ((holder, _) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            refreshOneShortVideoHolder(holder, configuration)
        }
    }

    private fun applyWindowedTop(act: Activity) {
        if (!gMasterOn || !gStatusOn || !canApplySystemBars()) return
        try {
            rememberOriginalStatusBar(act)
            val window = act.window ?: return
            val decor = window.decorView

            fun applyOnce() {

                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                )
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT >= 28) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    window.attributes = attrs
                }

                if (Build.VERSION.SDK_INT >= 30) {

                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                    // Preserve app-owned insets in windowed mode.
                    window.statusBarColor = Color.TRANSPARENT
                    decor.windowInsetsController?.apply {
                        setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                        if (Build.VERSION.SDK_INT >= 35) {
                            setSystemBarsAppearance(
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                            )
                        }
                        var topTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                        if (Build.VERSION.SDK_INT >= 34) {
                            topTypes = topTypes or WindowInsets.Type.systemOverlays()
                        }
                        hide(topTypes)
                        systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {

                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    window.statusBarColor = Color.BLACK
                }
                decor.requestApplyInsets()
                updateSeriesMallTopMargin(act, true)
            }

            applyOnce()

            for (delay in longArrayOf(120L, 360L)) mainHandler.postDelayed({
                if (!act.isFinishing && isWindowedMode(act) && gMasterOn && gStatusOn) {
                    applyOnce()
                    refreshShortVideoWindowLayout(act)
                }
            }, delay)
        } catch (e: Throwable) {
            LogUtil.warn("apply windowed mode failed: $e")
        }
    }

    private fun reapplyAfterWindowModeChange(
        act: Activity?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        if (act == null || !gMasterOn) return
        for (delay in longArrayOf(0L, 180L)) mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            val windowed = isWindowedMode(act)
            if (gLastWindowedMode != windowed) {
                gLastWindowedMode = windowed
                LogUtil.info("window mode -> ${if (windowed) "windowed" else "fullscreen"}, reason=$reason")
            }
            if (gStatusOn) applyCleanTop(act) else showStatusBar(act)
            if (gNavBarOff) applyNavBar(act) else showNavBar(act)
            if (refreshLayout) refreshShortVideoWindowLayout(act)
        }, delay)
    }

    private fun reapplySeriesPageState(
        owner: Any?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        val act = owner as? Activity ?: activityFromFragment(owner) ?: return
        reapplyAfterWindowModeChange(act, reason, refreshLayout)
        mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            if (refreshLayout) refreshShortVideoWindowLayout(act)
            if (gRestoreControlsOnPause) {
                refreshVideoPauseState("series-window:$reason")
            }
            scanAllWindows()
        }, 80L)
    }

    private fun applyCleanTop(act: Activity?) {
        if (act == null || !gMasterOn || !gStatusOn || !canApplySystemBars()) return
        if (isWindowedMode(act)) {
            applyWindowedTop(act)
            return
        }
        try {
            rememberOriginalStatusBar(act)
            val window = act.window ?: return
            val decor = window.decorView
            // Do not modify decorFitsSystemWindows; three-key navigation consumes insets.
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attrs
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.statusBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    hide(WindowInsets.Type.statusBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, true)
        } catch (_: Exception) {}
    }
    private fun showStatusBar(act: Activity?) {
        if (act == null) return
        // When disabled, leave Hongguo's native status bar untouched.
        // The previous unconditional restoration removed LAYOUT_FULLSCREEN,
        // causing white icons on white and shifting the video header down.
        val saved = synchronized(gOriginalStatusBars) { gOriginalStatusBars.remove(act) } ?: return
        try {
            val window = act.window
            val decor = window.decorView
            val fullscreenFlag = WindowManager.LayoutParams.FLAG_FULLSCREEN
            if (saved.flags and fullscreenFlag != 0) window.addFlags(fullscreenFlag)
            else window.clearFlags(fullscreenFlag)
            window.statusBarColor = saved.color
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = saved.cutoutMode
                window.attributes = attrs
            }
            @Suppress("DEPRECATION")
            run { decor.systemUiVisibility = saved.systemUiVisibility }
            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    setSystemBarsAppearance(saved.lightAppearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                    when (saved.wasVisible) {
                        true -> show(WindowInsets.Type.statusBars())
                        false -> hide(WindowInsets.Type.statusBars())
                        else -> Unit
                    }
                }
            }
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, false)
        } catch (e: Throwable) {
            LogUtil.warn("restore original status bar failed: " + e)
        }
    }

    private fun applyBottomEdgeToEdge(act: Activity?, hideNavigationBar: Boolean) {
        if (act == null || !hideNavigationBar || isWindowedMode(act) ||
            !canApplySystemBars() || !isGestureNavigation(act)) return
        try {
            val decor = act.window.decorView
            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.hide(WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                run {
                    decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                }
            }
            synchronized(gHiddenGestureBars) { gHiddenGestureBars[act] = true }
            decor.requestApplyInsets()
        } catch (e: Throwable) {
            LogUtil.warn("gesture-only bar hide failed: " + e)
        }
    }

    private fun extendKnownBottomContentRoot(act: Activity?, expectedGapPx: Int) {
        if (TargetNames.isUniversal(gNames) ||
            act == null || expectedGapPx <= 0) return
        try {
            val id = act.resources.getIdentifier("hsw", "id", gPkg)
            if (id == 0) return
            val content = act.findViewById<View>(id) ?: return
            val parent = content.parent as? View ?: return
            val lp = content.layoutParams ?: return
            val contentLoc = IntArray(2)
            val parentLoc = IntArray(2)
            content.getLocationOnScreen(contentLoc)
            parent.getLocationOnScreen(parentLoc)
            val contentH = if (content.height > 0) content.height else content.measuredHeight
            val parentH = if (parent.height > 0) parent.height else parent.measuredHeight
            if (contentH <= 0 || parentH <= 0) return
            val desired = parentLoc[1] + parentH - contentLoc[1]
            val gap = desired - contentH
            val minGap = (expectedGapPx * 0.55f).toInt().coerceAtLeast(1)
            val maxGap = (expectedGapPx * 1.8f).toInt().coerceAtLeast(expectedGapPx)
            if (gap in minGap..maxGap && lp.height != desired) {
                rememberBottomLayoutState(content)
                lp.height = desired
                content.layoutParams = lp
                content.requestLayout()
                (content.parent as? View)?.requestLayout()
                LogUtil.info("hsw bottom extended: ${contentH}px -> ${desired}px, gap=${gap}px")
                LogUtil.incr("bottomHswExtend")
            }
        } catch (e: Throwable) {
            LogUtil.warn("extend hsw bottom failed: $e")
        }
    }

    private fun applyNavBar(act: Activity?) {
        if (act == null) return
        if (!gMasterOn || !gNavBarOff || !isGestureNavigation(act) ||
            !canApplySystemBars()) {
            showNavBar(act)
            return
        }
        applyBottomEdgeToEdge(act, true)
    }
    private fun showNavBar(act: Activity?) {
        if (act == null) return
        val hiddenByModule = synchronized(gHiddenGestureBars) {
            gHiddenGestureBars.remove(act)
        } == true
        if (!hiddenByModule) return
        try {
            val decor = act.window.decorView
            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.show(WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                run {
                    decor.systemUiVisibility = decor.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
                }
            }
            decor.requestApplyInsets()
        } catch (e: Throwable) {
            LogUtil.warn("restore gesture bar failed: " + e)
        }
    }

    private fun resolutionRank(resolution: Any?): Int {
        if (resolution == null) return Int.MIN_VALUE
        val enumName = try { (resolution as? Enum<*>)?.name ?: "" } catch (_: Throwable) { "" }
        val text = try { (enumName + " " + resolution.toString()).lowercase() } catch (_: Throwable) { enumName.lowercase() }
        if ("auto" in text || "undefine" in text) return Int.MIN_VALUE

        return when {
            "eightk" in text || "8k" in text -> 8000
            "fourk" in text || "4k" in text || "2160" in text -> 4000
            "twok" in text || "2k" in text || "1440" in text -> 2000
            "extremelyhighplus" in text || "1080p+" in text -> 1081
            "extremelyhigh" in text || "1080" in text -> 1080
            "superhigh" in text || "720" in text -> 720
            "h_high" in text || "540" in text -> 540

            enumName.equals("High", true) || " 480p" in " $text" -> 480
            "l_standard" in text || "240" in text -> 240
            "standard" in text || "360" in text -> 360
            else -> {

                try {
                    val m = resolution.javaClass.methods.firstOrNull { it.name == "getIndex" && it.parameterCount == 0 }
                    ((m?.invoke(resolution) as? Number)?.toInt() ?: 0) - 10000
                } catch (_: Throwable) { -10000 }
            }
        }
    }

    private fun findHighestResolution(model: Any?): Any? {
        if (model == null) return null
        return try {
            val getter = model.javaClass.methods.firstOrNull {
                it.name == "getSupportResolutions" && it.parameterCount == 0
            } ?: return null
            val array = getter.invoke(model) ?: return null
            val count = java.lang.reflect.Array.getLength(array)
            var best: Any? = null
            var bestRank = Int.MIN_VALUE
            for (i in 0 until count) {
                val r = java.lang.reflect.Array.get(array, i) ?: continue
                val rank = resolutionRank(r)
                if (rank > bestRank) {
                    bestRank = rank
                    best = r
                }
            }
            best
        } catch (e: Throwable) {
            LogUtil.warn("最高画质：读取支持清晰度失败: $e")
            null
        }
    }

    private fun rememberAndApplyHighestResolution(engine: Any?, model: Any?) {
        if (engine == null || model == null) return
        val highest = findHighestResolution(model) ?: return
        try {
            gEngineMaxResolution[engine] = highest
            LogUtil.info("最高画质：检测到 ${highest} rank=${resolutionRank(highest)}")
        } catch (_: Throwable) {}
        if (!gMasterOn || !gMaxQualityOn) return
        try {
            val method = engine.javaClass.methods.firstOrNull {
                it.name == "configResolution" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(highest)
            } ?: return
            method.invoke(engine, highest)
            LogUtil.incr("maxQualityApply")
        } catch (e: Throwable) {
            LogUtil.warn("最高画质：应用失败: $e")
        }
    }

    private fun applyHighestViaController(controller: Any?, highest: Any?): Boolean {
        if (controller == null || highest == null || gNames.resolutionApplyMethod.isBlank()) return false
        return try {
            var clazz: Class<*>? = controller.javaClass
            var target: java.lang.reflect.Method? = null
            while (clazz != null && target == null) {
                target = clazz.declaredMethods.firstOrNull {
                    it.name == gNames.resolutionApplyMethod && it.parameterCount == 1 &&
                        it.parameterTypes[0].isInstance(highest)
                }?.apply { isAccessible = true }
                clazz = clazz.superclass
            }
            if (target == null) return false
            target.invoke(controller, highest)
            LogUtil.info("最高画质：原生控制器 ${gNames.resolutionApplyMethod}($highest)")
            LogUtil.incr("maxQualityNativeApply")
            true
        } catch (e: Throwable) {
            LogUtil.warn("最高画质：原生控制器应用失败: $e")
            false
        }
    }

    private fun applyHighestToKnownEngines() {
        if (!gMasterOn || !gMaxQualityOn) return

        val controllers = try {
            synchronized(gControllerMaxResolution) { gControllerMaxResolution.entries.map { it.key to it.value } }
        } catch (_: Throwable) { emptyList() }
        for ((controller, highest) in controllers) applyHighestViaController(controller, highest)

        val engines = try {
            synchronized(gEngineMaxResolution) { gEngineMaxResolution.entries.map { it.key to it.value } }
        } catch (_: Throwable) { emptyList() }
        for ((engine, highest) in engines) {
            try {
                val method = engine.javaClass.methods.firstOrNull {
                    it.name == "configResolution" && it.parameterCount == 1 &&
                        it.parameterTypes[0].isInstance(highest)
                } ?: continue
                method.invoke(engine, highest)
                LogUtil.incr("maxQualityApply")
            } catch (_: Throwable) {}
        }
    }

    private fun createNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!gNotificationMenuOn) {
                nm.cancel(9999)
                return
            }
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("lspilot_toggle", "KEJIYU", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            val flag = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            fun pi(code: Int, action: String) = PendingIntent.getBroadcast(ctx, code, Intent(action).apply { setPackage(gPkg); addFlags(Intent.FLAG_RECEIVER_FOREGROUND) }, flag)
            val n = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, "lspilot_toggle") else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            n.setContentTitle("KEJIYU 模块"); n.setContentText(if (gMasterOn) "已启用" else "已停用"); n.setSmallIcon(android.R.drawable.ic_menu_view); n.setOngoing(true)
            n.setContentIntent(pi(9999, "$gPkg.LSPilot.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_view, if (gMasterOn) "隐藏" else "显示", pi(10001, "$gPkg.LSPilot.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_edit, "面板", pi(10002, "$gPkg.LSPilot.OPEN_PANEL"))
            n.addAction(android.R.drawable.ic_menu_close_clear_cancel, "重启", pi(10003, "$gPkg.LSPilot.RESTART"))
            nm.notify(9999, n.build())
        } catch (e: Exception) { LogUtil.error("通知", e) }
    }
    private fun ensureReceiver(ctx: Context) {
        if (gReceiverRegistered) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { when (intent.action) { "$gPkg.LSPilot.TOGGLE" -> doToggle(ctx); "$gPkg.LSPilot.OPEN_PANEL" -> openPanel(); "$gPkg.LSPilot.RESTART" -> restartApp(ctx) } } catch (e: Exception) { LogUtil.error("receiver", e) }
                }
            }
            val filter = IntentFilter("$gPkg.LSPilot.TOGGLE").apply { addAction("$gPkg.LSPilot.OPEN_PANEL"); addAction("$gPkg.LSPilot.RESTART") }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            gReceiverRegistered = true
        } catch (e: Exception) { LogUtil.error("receiver", e) }
    }
    private fun doToggle(ctx: Context) {
        gMasterOn = !gMasterOn
        savePref("master_on", gMasterOn)
        if (!gMasterOn) {
            restoreAllSavedViews()
            restoreShortVideoNativeControls()
            setVideoToolbarsVisible(true)
        }
        createNotification(ctx)
        applyToCurrent()
        LogUtil.info("总开关 → $gMasterOn")
    }
    private fun restartApp(ctx: Context) { try { val i = ctx.packageManager.getLaunchIntentForPackage(gPkg); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK); ctx.startActivity(i) }; mainHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1000) } catch (_: Exception) {} }

    private data class PanelPalette(
        val page: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val accentSoft: Int,
        val divider: Int,
        val switchOffThumb: Int,
        val switchOffTrack: Int,
        val switchOnTrack: Int,
    )

    private fun safeRgb(r: Int, g: Int, b: Int): Int = Color.rgb(r, g, b)
    private fun safeArgb(a: Int, r: Int, g: Int, b: Int): Int = Color.argb(a, r, g, b)
    private fun dp(ctx: Context, value: Float): Int = (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun panelPalette(ctx: Context): PanelPalette {
        val night = ((ctx.resources.configuration.uiMode and 0x30) == 0x20)
        gIsNight = night
        return if (night) {
            PanelPalette(
                page = safeRgb(15, 17, 20),
                surface = safeRgb(25, 28, 32),
                surfaceAlt = safeRgb(35, 39, 45),
                text = safeRgb(245, 247, 250),
                textSecondary = safeRgb(167, 175, 186),
                accent = safeRgb(255, 107, 74),
                accentSoft = safeRgb(52, 35, 31),
                divider = safeArgb(30, 255, 255, 255),
                switchOffThumb = safeRgb(210, 214, 220),
                switchOffTrack = safeRgb(84, 90, 99),
                switchOnTrack = safeRgb(151, 72, 55),
            )
        } else {
            PanelPalette(
                page = safeRgb(246, 247, 249),
                surface = safeRgb(255, 255, 255),
                surfaceAlt = safeRgb(241, 243, 246),
                text = safeRgb(23, 25, 28),
                textSecondary = safeRgb(108, 115, 126),
                accent = safeRgb(255, 90, 60),
                accentSoft = safeRgb(255, 237, 232),
                divider = safeArgb(20, 0, 0, 0),
                switchOffThumb = safeRgb(247, 247, 248),
                switchOffTrack = safeRgb(194, 199, 206),
                switchOnTrack = safeRgb(255, 168, 150),
            )
        }
    }

    private fun roundedBg(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
        }
    }

    private fun styleSwitch(sw: Switch, p: PanelPalette) {
        try {
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
            )
            sw.thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.switchOffThumb))
            sw.trackTintList = ColorStateList(states, intArrayOf(p.switchOnTrack, p.switchOffTrack))
        } catch (_: Throwable) {

        }
    }

    private fun sectionTitle(ctx: Context, title: String, p: PanelPalette): TextView = TextView(ctx).apply {
        text = title
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(p.textSecondary)
        isAllCaps = false
        setPadding(dp(ctx, 4f), dp(ctx, 18f), dp(ctx, 4f), dp(ctx, 5f))
    }

    private fun makePanelRow(
        ctx: Context,
        title: String,
        description: String,
        checked: Boolean,
        p: PanelPalette,
        emphasis: Boolean = false,
        hasSubmenu: Boolean = false,
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 15f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
            background = roundedBg(ctx, if (emphasis) p.accentSoft else p.surfaceAlt, 20f)
            minimumHeight = dp(ctx, 64f)
        }
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(Typeface.DEFAULT, if (emphasis) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(p.text)
            includeFontPadding = false
        })
        labels.addView(TextView(ctx).apply {
            text = description
            textSize = 11.5f
            setTextColor(p.textSecondary)
            includeFontPadding = false
            setPadding(0, dp(ctx, 4f), dp(ctx, 6f), 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (hasSubmenu) {

            val handle = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 5f), 0, dp(ctx, 4f), 0)
            }
            val heights = floatArrayOf(13f, 19f, 15f)
            for (height in heights) {
                handle.addView(View(ctx).apply {
                    background = roundedBg(ctx, p.textSecondary, 1.5f)
                }, LinearLayout.LayoutParams(dp(ctx, 2f), dp(ctx, height)).apply {
                    leftMargin = dp(ctx, 1.5f)
                    rightMargin = dp(ctx, 1.5f)
                })
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 30f)))
        }
        val sw = Switch(ctx).apply {
            isChecked = checked
            setShowText(false)
            setPadding(dp(ctx, 8f), 0, 0, 0)
        }
        styleSwitch(sw, p)
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row to sw
    }

    private fun showDefaultSpeedEditor(act: Activity, p: PanelPalette, onSelected: (Float) -> Unit) {
        try {
            val dialog = Dialog(act)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 18f), dp(act, 18f), dp(act, 18f), dp(act, 16f))
                background = roundedBg(act, p.page, 26f, p.divider, 1f)
            }
            root.addView(TextView(act).apply {
                text = "默认倍速"
                textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(p.text)
                includeFontPadding = false
            })
            root.addView(TextView(act).apply {
                text = "选择后立即生效，并记住为以后播放的默认速度"
                textSize = 11.5f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                setPadding(0, dp(act, 6f), 0, dp(act, 10f))
            })

            DEFAULT_SPEED_OPTIONS.forEach { speed ->
                val selected = kotlin.math.abs(speed - gDefaultSpeed) < 0.001f
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(act, 15f), dp(act, 12f), dp(act, 15f), dp(act, 12f))
                    background = roundedBg(act, if (selected) p.accentSoft else p.surfaceAlt, 16f)
                    minimumHeight = dp(act, 50f)
                }
                row.addView(TextView(act).apply {
                    text = formatSpeed(speed)
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) p.accent else p.text)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(act).apply {
                    text = if (selected) "✓" else ""
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.accent)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(dp(act, 30f), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.setOnClickListener {
                    saveDefaultSpeedValue(speed)

                    if (!gDefaultSpeedOn) {
                        gDefaultSpeedOn = true
                        savePref(DEFAULT_SPEED_PREF, true)
                    }
                    onSelected(speed)
                    dialog.dismiss()
                    scheduleDefaultSpeedApply("menu-select")
                    Toast.makeText(act, "默认倍速已设为 ${formatSpeed(speed)}", Toast.LENGTH_SHORT).show()
                }
                root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(act, 7f)
                })
            }

            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnShowListener {
                try {
                    dialog.window?.apply {
                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        attributes = attributes.apply { dimAmount = 0.62f }
                        setLayout((act.resources.displayMetrics.widthPixels * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                } catch (e: Throwable) { LogUtil.error("默认倍速窗口", e) }
            }
            dialog.show()
        } catch (e: Throwable) {
            LogUtil.error("默认倍速编辑", e)
        }
    }

    private fun downloadLimitSummary(): String {
        val (episode, series, total) = currentDownloadLimitValues()
        return "单日集数 $episode  ·  单日剧数 $series  ·  总缓存 $total"
    }

    private fun showDownloadLimitEditor(act: Activity, p: PanelPalette, onSaved: () -> Unit) {
        try {
            val (episodeNow, seriesNow, totalNow) = currentDownloadLimitValues()
            val dialog = Dialog(act)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 18f), dp(act, 18f), dp(act, 18f), dp(act, 16f))
                background = roundedBg(act, p.page, 26f, p.divider, 1f)
            }
            root.addView(TextView(act).apply {
                text = "自定义下载限制"
                textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(p.text)
                includeFontPadding = false
            })
            root.addView(TextView(act).apply {
                text = "默认 99999。测试时可以改成 2、3 等小数值。"
                textSize = 11.5f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                setPadding(0, dp(act, 6f), 0, dp(act, 12f))
            })

            fun field(label: String, value: Int): EditText {
                root.addView(TextView(act).apply {
                    text = label
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.textSecondary)
                    includeFontPadding = false
                    setPadding(dp(act, 2f), dp(act, 7f), 0, dp(act, 5f))
                })
                return EditText(act).apply {
                    setText(value.toString())
                    textSize = 15f
                    setTextColor(p.text)
                    setHintTextColor(p.textSecondary)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setSelectAllOnFocus(true)
                    setPadding(dp(act, 13f), 0, dp(act, 13f), 0)
                    background = roundedBg(act, p.surfaceAlt, 12f, p.divider, 1f)
                    root.addView(this, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(act, 48f),
                    ))
                }
            }

            val episodeField = field("单日最大下载集数", episodeNow)
            val seriesField = field("单日最大下载剧数", seriesNow)
            val totalField = field("总缓存剧数上限", totalNow)

            root.addView(TextView(act).apply {
                text = "修改数值后需要强停红果并重新打开，确保主进程和 :downloader 的缓存配置全部刷新。"
                textSize = 10.8f
                setTextColor(p.accent)
                includeFontPadding = false
                setPadding(dp(act, 2f), dp(act, 12f), dp(act, 2f), dp(act, 4f))
            })

            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(act, 12f), 0, 0)
            }
            val cancel = TextView(act).apply {
                text = "取消"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(p.text)
                background = roundedBg(act, p.surfaceAlt, 12f)
                setOnClickListener { dialog.dismiss() }
            }
            val save = TextView(act).apply {
                text = "保存"
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundedBg(act, p.accent, 12f)
                setOnClickListener {
                    val episode = episodeField.text?.toString()?.trim()?.toIntOrNull()
                    val series = seriesField.text?.toString()?.trim()?.toIntOrNull()
                    val total = totalField.text?.toString()?.trim()?.toIntOrNull()
                    if (episode == null || series == null || total == null ||
                        episode !in 1..999_999_999 || series !in 1..999_999_999 || total !in 1..999_999_999
                    ) {
                        Toast.makeText(act, "请输入 1 ~ 999999999 的整数", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    saveDownloadLimitValues(episode, series, total)
                    LogUtil.info("download limit custom values saved: episode=$episode series=$series total=$total")
                    onSaved()
                    dialog.dismiss()
                    Toast.makeText(act, "已保存，需要强停红果后重新打开生效", Toast.LENGTH_LONG).show()
                }
            }
            buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(act, 44f), 1f).apply { rightMargin = dp(act, 6f) })
            buttons.addView(save, LinearLayout.LayoutParams(0, dp(act, 44f), 1f).apply { leftMargin = dp(act, 6f) })
            root.addView(buttons)

            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnShowListener {
                try {
                    dialog.window?.apply {
                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        attributes = attributes.apply { dimAmount = 0.62f }
                        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                        setLayout((act.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                } catch (e: Throwable) { LogUtil.error("下载限制编辑窗口", e) }
            }
            dialog.show()
        } catch (e: Throwable) {
            LogUtil.error("下载限制编辑", e)
        }
    }

    private fun installPanelWatermark(target: View, ctx: Context, color: Int) {
        try {
            val text = "免费模块倒卖死全家"
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {

                this.color = Color.argb(24, Color.red(color), Color.green(color), Color.blue(color))
                textSize = 12.5f * ctx.resources.displayMetrics.scaledDensity
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val gapX = dp(ctx, 54f).toFloat()
            val gapY = dp(ctx, 72f).toFloat()
            val drawable = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = bounds
                    if (b.width() <= 0 || b.height() <= 0) return
                    val save = canvas.save()
                    canvas.rotate(-24f, b.exactCenterX(), b.exactCenterY())
                    val textW = paint.measureText(text)
                    val stepX = textW + gapX
                    var row = 0
                    var y = -b.height().toFloat()
                    val maxY = b.height() * 2f
                    while (y < maxY) {
                        var x = -b.width().toFloat() - if (row % 2 == 0) 0f else stepX / 2f
                        val maxX = b.width() * 2f
                        while (x < maxX) {
                            canvas.drawText(text, x, y, paint)
                            x += stepX
                        }
                        y += gapY
                        row++
                    }
                    canvas.restoreToCount(save)
                }

                override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255) }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
                @Suppress("DEPRECATION")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
            target.overlay.add(drawable)
            target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                drawable.setBounds(0, 0, v.width, v.height)
                drawable.invalidateSelf()
            }
            target.post {
                drawable.setBounds(0, 0, target.width, target.height)
                drawable.invalidateSelf()
            }
        } catch (e: Throwable) {
            LogUtil.error("面板水印", e)
        }
    }

    private fun targetCompatLabel(ctx: Context): String {
        try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(gPkg, 0)
            val version = pi.versionName ?: gTargetVersionName
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            gTargetVersionName = version
            gTargetVersionCode = code
            val state = if (TargetNames.isUniversal(gNames))
                "通用 Hook · 未使用旧版本配置"
                else if (TargetNames.isSupported(gPkg, version)) "精确版本映射"
                else "待验证"
            return "$version  ·  versionCode $code  ·  $state\n${gNames.profileId}"
        } catch (_: Throwable) {
            return "$gTargetVersionName  ·  versionCode $gTargetVersionCode\n${gNames.profileId}"
        }
    }

    private fun showPanel(ctx: Context?) {

        val act: Activity = (ctx as? Activity) ?: gCurrentActivity ?: return
        try {
            val p = panelPalette(act)
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 16f), dp(act, 8f), dp(act, 16f), dp(act, 18f))
                background = roundedBg(act, p.page, 28f)
            }

            val header = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 6f), dp(act, 18f), dp(act, 6f), dp(act, 14f))
            }
            header.addView(TextView(act).apply {
                text = "KEJIYU"
                textSize = 25f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(p.text)
                includeFontPadding = false
            })
            header.addView(TextView(act).apply {
                text = "红果综合模块  ·  ${BuildConfig.VERSION_NAME}"
                textSize = 12f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                setPadding(0, dp(act, 5f), 0, 0)
            })
            content.addView(header)

            content.addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(act, p.surface, 22f, p.divider, 1f)
                setPadding(dp(act, 15f), dp(act, 13f), dp(act, 15f), dp(act, 13f))
                addView(TextView(act).apply {
                    text = "当前运行模式"
                    textSize = 11f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.accent)
                    includeFontPadding = false
                })
                addView(TextView(act).apply {
                    text = targetCompatLabel(act)
                    textSize = 12.5f
                    setTextColor(p.text)
                    includeFontPadding = false
                    setPadding(0, dp(act, 6f), 0, 0)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(act, 4f)
            })

            fun addSwitch(
                title: String,
                description: String,
                getter: () -> Boolean,
                setter: (Boolean) -> Unit,
                saveKey: String,
                emphasis: Boolean = false,
                onRowAction: (() -> Unit)? = null,
            ): Pair<LinearLayout, Switch> {
                val (row, sw) = makePanelRow(act, title, description, getter(), p, emphasis, onRowAction != null)
                sw.setOnCheckedChangeListener { _, v ->
                    setter(v)
                    savePref(saveKey, v)
                    if (saveKey != "ad_block") applyToCurrent()
                }
                row.setOnClickListener {
                    if (onRowAction != null) onRowAction() else sw.isChecked = !sw.isChecked
                }
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(act, 7f)
                })
                return row to sw
            }

            content.addView(sectionTitle(act, "核心", p))
            addSwitch("模块总开关", "所有功能的总控制；关闭后恢复模块修改的界面", { gMasterOn }, {
                gMasterOn = it
                InAppMiniPlayer.enabled = it && gMiniPlayerOn
                SystemMediaBridge.configure(act, it && gSystemMediaOn)
                if (!it) {
                    InAppMiniPlayer.restore()
                    restoreAllSavedViews()
                    restoreShortVideoNativeControls()
                    restoreNativeBottomWindowColor(gCurrentActivity)
                    setVideoToolbarsVisible(true)
                }
            }, "master_on", true)

            content.addView(sectionTitle(act, "界面精简", p))
            addSwitch("隐藏状态栏", "视频页面沉浸显示", { gStatusOn }, { gStatusOn = it }, "status_bar")
            addSwitch("系统媒体后台播放", "实验功能：尝试保留原视频声音，在通知栏显示系统媒体卡片（播放/暂停/返回）；不创建悬浮图标", { gSystemMediaOn }, {
                gSystemMediaOn = it
                SystemMediaBridge.configure(act, it && gMasterOn)
            }, "system_media_playback")
            addSwitch("应用内小窗继续播放", "实验功能：切换底部页面继续播放；点击画面返回、拖动吸边、关闭暂停、播放/暂停；默认关闭", { gMiniPlayerOn }, {
                gMiniPlayerOn = it
                InAppMiniPlayer.enabled = it && gMasterOn
                if (!it) InAppMiniPlayer.restore()
            }, "in_app_mini_player")
            addSwitch("禁用商城", "仅隐藏首页商城 Tab，并阻止打开商城页面；保留首页、剧场、我的", { gDisableShoppingMall }, {
                gDisableShoppingMall = it
                if (it) mainHandler.post { scanShoppingTabIfNeeded(gCurrentActivity, force = true) }
                else restoreShoppingTabViews()
            }, "disable_shopping_mall")
            addSwitch("隐藏控件", "精简已适配区域；三键导航保留首页底部导航与全集入口", { gControlOn }, {
                gControlOn = it
                if (!it) {

                    restoreAllSavedViews()
                    restoreShortVideoNativeControls()
                    restoreNativeBottomWindowColor(gCurrentActivity)
                    mainHandler.postDelayed({ restoreShortVideoNativeControls(); scanAllWindows() }, 120L)
                }
            }, "control_hide")
            addSwitch("选集相关功能", "隐藏联播页顶部和底部的选集相关控件", { gPlayerOn }, {
                gPlayerOn = it
                if (!it || (gRestoreControlsOnPause && gVideoPaused)) setVideoToolbarsVisible(true)
                else setVideoToolbarsVisible(false)
            }, "player_bar")
            addSwitch("隐藏视频进度条", "隐藏首页和连续播放页的进度条", { gProgressOff }, {
                gProgressOff = it
                if (!it) {
                    val roots = collectAllWindows()
                    for (root in roots) scanTreeProgressRestore(root)
                }
            }, "progress_off")
            addSwitch("隐藏底部小白条", "仅手势导航生效；三键导航不会隐藏按键", { gNavBarOff }, { gNavBarOff = it }, "nav_bar_off")
            addSwitch("暂停后恢复所有控件", "暂停视频时临时恢复控件，继续播放后按规则隐藏", { gRestoreControlsOnPause }, {
                gRestoreControlsOnPause = it
                if (it) refreshVideoPauseState("switch-enabled", gVideoPaused)
                else mainHandler.post { scanAllWindows() }
            }, "restore_controls_pause")

            content.addView(sectionTitle(act, "播放与手势", p))
            if (gNames.resolutionController.isNotBlank()) {
                addSwitch("默认最高画质", "播放时自动选择当前视频支持的最高画质", { gMaxQualityOn }, {
                    gMaxQualityOn = it
                    if (it) mainHandler.post { applyHighestToKnownEngines() }
                }, "max_quality")
            }
            lateinit var defaultSpeedRow: LinearLayout
            lateinit var defaultSpeedSwitch: Switch
            val defaultSpeedPair = addSwitch(
                "默认倍速",
                defaultSpeedSummary(),
                { gDefaultSpeedOn },
                { enabled ->
                    gDefaultSpeedOn = enabled
                    if (enabled) scheduleDefaultSpeedApply("switch-enabled")
                },
                DEFAULT_SPEED_PREF,
                onRowAction = {
                    showDefaultSpeedEditor(act, p) { _ ->
                        try {
                            val labels = defaultSpeedRow.getChildAt(0) as? ViewGroup
                            (labels?.getChildAt(1) as? TextView)?.text = defaultSpeedSummary()
                            if (!defaultSpeedSwitch.isChecked) defaultSpeedSwitch.isChecked = true
                        } catch (_: Throwable) {}
                    }
                },
            )
            defaultSpeedRow = defaultSpeedPair.first
            defaultSpeedSwitch = defaultSpeedPair.second
            addSwitch("双击打开评论区", "替换原双击点赞动作，双击直接打开评论", { gDoubleTapCommentOn }, { gDoubleTapCommentOn = it }, "double_tap_comment")
            addSwitch("禁用下拉刷新", "禁用下拉手势，并折叠下拉刷新提示区域", { gRefreshOff }, {
                gRefreshOff = it
                if (!it) restoreRefreshAccessories() else mainHandler.post { scanAllWindows() }
            }, "pull_refresh")
            addSwitch("顶部区域拦截下滑", "拦截屏幕顶部区域向下滑动手势", { gTopZoneOn }, { gTopZoneOn = it }, "top_zone")

            content.addView(sectionTitle(act, "内容与账号", p))
            addSwitch("拦截广告 / 挂件", "拦截已适配的广告层、金宝箱和悬浮挂件", { gAdOn }, { gAdOn = it }, "ad_block")
            addSwitch("解锁 VIP", "启用已适配的 VIP 状态 Hook", { gVipOn }, { gVipOn = it }, "vip_unlock")
            addSwitch("显示 VIP 图标", "控制 VIP 图标相关显示逻辑", { gVipIconOn }, { gVipIconOn = it }, "vip_icon")

            content.addView(sectionTitle(act, "实验性功能", p))
            addSwitch("OLED 亮度拦截", "实验功能：仅在需要时手动开启", { gOledProtectOn }, { gOledProtectOn = it }, "oled_protect")
            addSwitch("通知栏快捷菜单", "在通知栏显示模块开关、面板和重启快捷操作。没有效果 记得打开红果的通知权限", { gNotificationMenuOn }, {
                gNotificationMenuOn = it
                createNotification(act.applicationContext)
            }, "notification_menu")
            addSwitch(
                "自定义下载数量限制",
                downloadLimitSummary(),
                { gDownloadLimitUnlimitOn },
                {
                    gDownloadLimitUnlimitOn = it
                    if (it) Toast.makeText(act, "已开启。为确保下载进程读取新配置，请强停红果后重新打开", Toast.LENGTH_LONG).show()
                },
                DOWNLOAD_LIMIT_PREF,
                onRowAction = { showDownloadLimitEditor(act, p) {  } },
            )

            val scroll = ScrollView(act).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            val dialog = Dialog(act)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val shell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(act, p.page, 28f, p.divider, 1f)
                clipToPadding = false
            }
            shell.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))

            val footer = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 16f), dp(act, 10f), dp(act, 16f), dp(act, 16f))
            }
            val done = TextView(act).apply {
                text = "完成"
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundedBg(act, p.accent, 18f)
                isClickable = true
                isFocusable = true
                minimumHeight = dp(act, 46f)
                setOnClickListener {

                    shell.animate().cancel()
                    shell.animate()
                        .alpha(0f)
                        .translationY(dp(act, 14f).toFloat())
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .setDuration(130L)
                        .withEndAction { if (dialog.isShowing) dialog.dismiss() }
                        .start()
                }
            }
            footer.addView(done, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(act, 46f),
            ))
            shell.addView(footer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))

            installPanelWatermark(shell, act, p.textSecondary)

            shell.alpha = 0f
            shell.translationY = dp(act, 18f).toFloat()
            shell.scaleX = 0.975f
            shell.scaleY = 0.975f

            dialog.setContentView(shell)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnShowListener {
                try {
                    val dm = act.resources.displayMetrics
                    dialog.window?.apply {

                        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        attributes = attributes.apply {
                            dimAmount = 0.58f
                            windowAnimations = 0
                        }
                        setLayout((dm.widthPixels * 0.90f).toInt(), (dm.heightPixels * 0.86f).toInt())
                    }
                    shell.post {
                        shell.animate().cancel()
                        shell.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                            .start()
                    }
                } catch (e: Throwable) {
                    LogUtil.error("面板窗口样式", e)
                }
            }
            dialog.show()
        } catch (e: Exception) {
            LogUtil.error("面板", e)
        }
    }
    private fun openPanel() { mainHandler.post { showPanel(gCurrentActivity) } }

    private fun handleTopZoneEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev == null) return false
        try {
            when (ev.actionMasked) { 0 -> { val y = ev.rawY; if (y in 0f..TOP_ZONE_HEIGHT.toFloat()) { gTopZoneTracking = true; gTopZoneStartY = y; gTopZoneActive = false } else { gTopZoneTracking = false; gTopZoneActive = false }; return false } }
            if (gTopZoneTracking) { if (!gTopZoneActive && ev.actionMasked == 2) { if (ev.rawY - gTopZoneStartY > 60) gTopZoneActive = true }; if (gTopZoneActive) { if (ev.actionMasked == 1 || ev.actionMasked == 3) { gTopZoneTracking = false; gTopZoneActive = false }; return true }; if (ev.actionMasked == 1 || ev.actionMasked == 3) gTopZoneTracking = false }
        } catch (_: Exception) {}
        return false
    }

    private fun findNativeSettingsContainer(root: View?): ViewGroup? {
        if (root !is ViewGroup) return null
        var best: ViewGroup? = null
        var bestScore = Int.MIN_VALUE
        fun walk(group: ViewGroup, depth: Int) {
            if (depth > 9) return
            val name = group.javaClass.name
            val isRecycler = name.contains("RecyclerView") || name.contains("ListView")
            val isVerticalLinear = group is LinearLayout && group.orientation == LinearLayout.VERTICAL
            if (!isRecycler && isVerticalLinear && group.childCount in 2..40) {
                var textCount = 0
                var clickableCount = 0
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) textCount++
                    if (child.isClickable) clickableCount++
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) if (child.getChildAt(j) is TextView) { textCount++; break }
                    }
                }
                val widthOk = group.width <= 0 || group.width >= actWidthPx(group.context) * 0.72f
                val score = textCount * 3 + clickableCount * 2 + group.childCount - depth * 2 + if (widthOk) 8 else 0
                if (textCount >= 2 && score > bestScore) {
                    best = group
                    bestScore = score
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ViewGroup && !child.javaClass.name.contains("RecyclerView")) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    private fun actWidthPx(ctx: Context): Int = try {
        ctx.resources.displayMetrics.widthPixels
    } catch (_: Throwable) { 1080 }

    private fun setFieldValue(instance: Any, fieldName: String, value: Any?): Boolean {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                clazz.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
                return true
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return false
    }

    private fun callNoArgMethod(instance: Any, methodName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                val method = clazz.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(instance)
                }
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return null
    }

    private data class NativeSettingsSpec(
        val listMethods: Set<String>,
        val itemClass: String,
        val clickClass: String,
        val checkedClass: String?,
        val style: String,
    )

    private fun nativeSettingsSpec(): NativeSettingsSpec = when {
        TargetNames.isUniversal(gNames) -> NativeSettingsSpec(
            listMethods = emptySet(),
            itemClass = "",
            clickClass = "",
            checkedClass = null,
            style = "universal-no-legacy-settings-items",
        )
        gPkg == TargetNames.OVERSEA_PACKAGE -> NativeSettingsSpec(
            listMethods = setOf("e1", "f1"),
            itemClass = "vr5.e",
            clickClass = "vr5.b",
            checkedClass = null,
            style = "oversea-arrow",
        )
        gNames.profileId == "CN-7.3.3.18" -> NativeSettingsSpec(
            listMethods = setOf("X0", "Y0"),
            itemClass = "mz5.e",
            clickClass = "mz5.b",
            checkedClass = "mz5.c",
            style = "atomic-arrow",
        )
        gNames.profileId == "CN-7.3.2.32" -> NativeSettingsSpec(
            listMethods = setOf("c1", "d1"),
            itemClass = "qv5.e",
            clickClass = "qv5.b",
            checkedClass = "qv5.c",
            style = "grouped-atomic-arrow",
        )
        else -> NativeSettingsSpec(

            listMethods = setOf("c1", "d1"),
            itemClass = "pv5.e",
            clickClass = "pv5.b",
            checkedClass = "pv5.c",
            style = "grouped-atomic-arrow",
        )
    }

    private fun injectNativeSettingsList(
        listObj: Any?,
        classLoader: ClassLoader,
        spec: NativeSettingsSpec = nativeSettingsSpec(),
    ): Boolean {
        val list = listObj as? MutableList<Any?> ?: return false
        try {
            for (item in list) {
                if (item == null) continue
                val title = findFieldValue(item, "e")?.toString()
                if (title == "模块设置") return true
            }

            val itemClass = Class.forName(spec.itemClass, false, classLoader)
            val ctor = itemClass.declaredConstructors.firstOrNull { it.parameterCount == 0 } ?: return false
            ctor.isAccessible = true
            val item = ctor.newInstance()
            if (!setFieldValue(item, "e", "模块设置")) return false
            setFieldValue(item, "f", "点击打开模块菜单")

            when (spec.style) {
                "oversea-arrow" -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "k", true)
                }
                "grouped-atomic-arrow" -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "a", true)
                    setFieldValue(item, "b", true)
                    setFieldValue(item, "c", true)
                    setFieldValue(item, "o", java.util.concurrent.atomic.AtomicBoolean(gMasterOn))
                }
                else -> {

                    setFieldValue(item, "i", true)
                    setFieldValue(item, "o", java.util.concurrent.atomic.AtomicBoolean(gMasterOn))
                }
            }

            list.add(0, item)
            LogUtil.info("原生设置数据项已注入: ${spec.itemClass} / index=0 / profile=${gNames.profileId}")
            return true
        } catch (e: Throwable) {
            LogUtil.warn("原生设置数据项注入失败(${spec.itemClass}): $e")
            return false
        }
    }

    private fun boundSettingsTitle(clickHost: Any): String? {
        return try {
            val holder = findFieldValue(clickHost, "a") ?: return null
            val data = callNoArgMethod(holder, "getBoundData") ?: return null
            findFieldValue(data, "e")?.toString()
        } catch (_: Throwable) { null }
    }

    private fun nearestSettingsTextColor(root: View?, fallback: Int): Int {
        if (root == null) return fallback
        if (root is TextView) {
            val text = try { root.text?.toString().orEmpty() } catch (_: Throwable) { "" }
            if (text.isNotBlank() && root.textSize > 12f) return try { root.currentTextColor } catch (_: Throwable) { fallback }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            val c = nearestSettingsTextColor(root.getChildAt(i), fallback)
            if (c != fallback) return c
        }
        return fallback
    }

    private fun makeNativeSettingsEntry(act: Activity, parent: ViewGroup): View {
        val p = panelPalette(act)
        val textColor = nearestSettingsTextColor(parent, p.text)
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 18f), 0, dp(act, 16f), 0)
            minimumHeight = dp(act, 56f)
            tag = gKejiyuBtnTag
            isClickable = true
            isFocusable = true
            try {
                val tv = android.util.TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
                    setBackgroundResource(tv.resourceId)
                } else {
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                }
            } catch (_: Throwable) {
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            addView(TextView(act).apply {
                text = "模块设置"
                textSize = 15f
                setTextColor(textColor)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(act).apply {
                text = "›"
                textSize = 25f
                setTextColor(p.textSecondary)
                includeFontPadding = false
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(act, 30f), ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { showPanel(gSettingsActivity ?: gCurrentActivity) }
        }
    }

    private fun containsKejiyuEntry(root: View?): Boolean {
        if (root == null) return false
        if (root.tag == gKejiyuBtnTag) return true
        if (root is TextView) {
            val text = try { root.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            if (text == "模块设置" || text == "KEJIYU 模块设置") return true
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) if (containsKejiyuEntry(root.getChildAt(i))) return true
        return false
    }


    // --- Native video long-press menu entry ---
    // Observe the original touch handling and native popup; never consume a gesture.
    private const val VIDEO_LONG_PRESS_SETTINGS_TAG = "XHONGGUO_VIDEO_MENU_SETTINGS"
    private var gVideoPressActive = false
    private var gVideoPressDownAt = 0L
    private var gVideoPressX = 0f
    private var gVideoPressY = 0f
    private var gVideoLongPressMenuUntil = 0L
    private var gVideoLongPressOwner: java.lang.ref.WeakReference<Activity>? = null
    private var gVideoLastEventDownTime = -1L
    private var gVideoLastEventAt = -1L
    private var gVideoLastEventAction = -1
    private val gVideoMenuInjected = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, Boolean>()
    )
    private val gVideoMenuStrongLabels = listOf(
        "不感兴趣", "举报", "播放设置", "画质", "清晰度", "倍速", "倍速播放",
        "弹幕", "静音", "音量", "屏幕锁定", "锁屏", "自动连播", "画面比例",
        "播放速度", "视频设置", "不喜欢",
    )
    private val gVideoMenuOtherLabels = listOf(
        "收藏", "分享", "下载", "缓存", "投屏", "复制链接", "反馈",
        "更多操作", "追剧", "选集", "看下一集", "下一集", "截图",
        "定时关闭", "分享剧集",
    )

    private fun isFullSeriesVideoScreen(act: Activity?): Boolean {
        val name = act?.javaClass?.name ?: return false
        return name.startsWith("com.dragon.read.") && (
            name.contains("ShortSeries") || name.contains("ShortVideo") ||
            name.contains("VideoAlbum") || name.contains("FullScreen") ||
            name.contains("Fullscreen") || name.contains("VideoPlay") ||
            name.contains("SeriesPlayer")
        )
    }

    private fun isLikelyVideoScreen(act: Activity?): Boolean {
        val name = act?.javaClass?.name ?: return false
        if (!name.startsWith("com.dragon.read.") || isLikelySettingsActivity(act)) return false
        return name.contains("MainFragmentActivity") || isFullSeriesVideoScreen(act) ||
            name.contains("Video") || name.contains("FeedActivity")
    }

    private fun markVideoLongPressCandidate(activity: Activity, source: String) {
        if (!gVideoLongPressSettingsOn || !isLikelyVideoScreen(activity)) return
        val owner = gVideoLongPressOwner?.get()
        if (owner != null && owner !== activity) return
        gVideoLongPressMenuUntil = android.os.SystemClock.uptimeMillis() + 3500L
        LogUtil.incr("videoLongPressCandidate")
        LogUtil.info("视频长按菜单候选: " + source + " | " + activity.javaClass.simpleName)
        scheduleLongPressMenuInjection()
    }

    private fun observeVideoLongPress(ev: android.view.MotionEvent?, act: Activity?) {
        val activity = act ?: return
        if (!gVideoLongPressSettingsOn || ev == null || !isLikelyVideoScreen(activity)) return

        // A full-series Activity can override dispatchTouchEvent and call super.
        // Observe the event once even when both hooks see the same MotionEvent.
        val action = ev.actionMasked
        if (gVideoLastEventDownTime == ev.downTime &&
            gVideoLastEventAt == ev.eventTime && gVideoLastEventAction == action) return
        gVideoLastEventDownTime = ev.downTime
        gVideoLastEventAt = ev.eventTime
        gVideoLastEventAction = action

        val now = android.os.SystemClock.uptimeMillis()
        val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gVideoPressActive = true
                gVideoPressDownAt = now
                gVideoPressX = ev.x
                gVideoPressY = ev.y
                gVideoLongPressOwner = java.lang.ref.WeakReference(activity)
                gVideoLongPressMenuUntil = 0L
                val captured = gVideoPressDownAt
                // Set the candidate slightly BEFORE the native long-press menu
                // opens. On fullscreen playback it often CANCELs the touch
                // immediately after opening, before our old delayed callback.
                mainHandler.postDelayed({
                    if (gVideoPressActive && gVideoPressDownAt == captured &&
                        gVideoLongPressOwner?.get() === activity) {
                        markVideoLongPressCandidate(activity, "hold")
                    }
                }, (timeout - 55L).coerceAtLeast(100L))
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val slop = try {
                    android.view.ViewConfiguration.get(activity).scaledTouchSlop * 1.4f
                } catch (_: Throwable) { 24f }
                if (ev.pointerCount > 1 ||
                    kotlin.math.abs(ev.x - gVideoPressX) > slop ||
                    kotlin.math.abs(ev.y - gVideoPressY) > slop) {
                    gVideoPressActive = false
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (gVideoPressActive && now - gVideoPressDownAt >= timeout - 110L) {
                    markVideoLongPressCandidate(activity, if (action ==
                        android.view.MotionEvent.ACTION_CANCEL) "native-cancel" else "release")
                }
                gVideoPressActive = false
            }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> gVideoPressActive = false
        }
    }

    private fun isInLongPressMenuWindow(): Boolean {
        if (!gVideoLongPressSettingsOn || !isLikelyVideoScreen(gCurrentActivity)) return false
        val owner = gVideoLongPressOwner?.get() ?: return false
        if (owner !== gCurrentActivity || owner.isFinishing) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now <= gVideoLongPressMenuUntil) return true
        // Native fullscreen menus may show before long-press ACTION_CANCEL,
        // even when the delayed Runnable has not run yet.
        val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        return gVideoPressActive &&
            now - gVideoPressDownAt in (timeout - 125L).coerceAtLeast(0L)..(timeout + 550L)
    }

    private fun nativeMenuLabels(root: View): Pair<Int, Int> {
        val texts = collectUiTexts(root, 8).map { it.trim().replace(" ", "") }
        val strong = gVideoMenuStrongLabels.count { label ->
            texts.any { it == label || it.startsWith(label + "：") }
        }
        val other = gVideoMenuOtherLabels.count { label ->
            texts.any { it == label || it.startsWith(label + "：") }
        }
        return strong to other
    }

    private fun containsVideoMenuEntry(root: View?): Boolean {
        if (root == null) return false
        if (root.tag == VIDEO_LONG_PRESS_SETTINGS_TAG) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsVideoMenuEntry(root.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun isNativeVideoMenuPanel(v: ViewGroup): Boolean {
        if (v.visibility != View.VISIBLE || v.alpha < 0.2f || v.childCount < 2) return false
        val dm = v.resources.displayMetrics
        val density = dm.density.coerceAtLeast(0.1f)
        val width = if (v.width > 0) v.width else v.measuredWidth
        val height = if (v.height > 0) v.height else v.measuredHeight
        if (width < dm.widthPixels * 0.35f ||
            height < 70f * density || height > dm.heightPixels * 0.85f) return false
        val (strong, other) = nativeMenuLabels(v)
        // Require at least two native video commands. A single Share or Like
        // icon on the player is never enough to inject into the video itself.
        return strong >= 2 || (strong >= 1 && other >= 1)
    }

    private fun openLongPressModuleSettings(act: Activity) {
        try {
            val intent = Intent().apply {
                setClassName("xyz.kejiyu.hongguo", "xyz.kejiyu.hongguo.MainActivity")
                putExtra("xhongguo_open_settings", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            act.startActivity(intent)
            LogUtil.info("视频长按菜单：打开 Xhongguo 独立设置")
        } catch (e: Throwable) {
            LogUtil.warn("视频长按菜单：独立设置启动失败，使用原有面板: " + e.message)
            showPanel(act)
        }
    }

    private fun createLongPressSettingsRow(
        act: Activity, parent: ViewGroup, dismissMenu: () -> Unit,
    ): View {
        val palette = panelPalette(act)
        val textColor = nearestSettingsTextColor(parent, palette.text)
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 16f), 0, dp(act, 16f), 0)
            minimumHeight = dp(act, 48f)
            tag = VIDEO_LONG_PRESS_SETTINGS_TAG
            isClickable = true
            isFocusable = true
            try {
                val attr = android.util.TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
                    && attr.resourceId != 0) setBackgroundResource(attr.resourceId)
            } catch (_: Throwable) {}
            addView(TextView(act).apply {
                text = "⚙"
                textSize = 19f
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(act, 32f), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(act).apply {
                text = "Xhongguo 设置"
                textSize = 15f
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setOnClickListener {
                try { dismissMenu() } catch (_: Throwable) {}
                openLongPressModuleSettings(act)
            }
        }
    }

    /**
     * Full-series menus are sometimes rendered as a ConstraintLayout,
     * RecyclerView or Compose-based BottomSheet rather than the vertical
     * menu used on the feed. Add one small entry inside the *popup window*,
     * never reparent the native menu or change its layout/insets.
     */
    private fun attachFullSeriesPopupEntry(root: View?, dismissMenu: () -> Unit): Boolean {
        val act = gCurrentActivity ?: return false
        if (!isFullSeriesVideoScreen(act) || !isInLongPressMenuWindow() ||
            root == null || containsVideoMenuEntry(root)) return false

        var host: FrameLayout? = root as? FrameLayout
        if (host == null) {
            fun findFrame(view: View?, depth: Int): FrameLayout? {
                if (view == null || depth > 12) return null
                if (view is FrameLayout && view.isShown &&
                    (view.width > act.resources.displayMetrics.widthPixels / 2 ||
                        view.measuredWidth > act.resources.displayMetrics.widthPixels / 2)) {
                    return view
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val found = findFrame(view.getChildAt(i), depth + 1)
                        if (found != null) return found
                    }
                }
                return null
            }
            host = findFrame(root, 0)
        }
        val target = host ?: return false
        if (target.width <= 0 || target.height <= dp(act, 54f)) return false
        if (containsVideoMenuEntry(target)) return true
        synchronized(gVideoMenuInjected) {
            if (gVideoMenuInjected.containsKey(target)) return true
            gVideoMenuInjected[target] = true
        }
        return try {
            val chip = TextView(act).apply {
                tag = VIDEO_LONG_PRESS_SETTINGS_TAG
                text = "⚙ Xhongguo 设置"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(act, 14f), dp(act, 9f), dp(act, 14f), dp(act, 9f))
                gravity = Gravity.CENTER
                background = roundedBg(act, Color.argb(245, 40, 41, 46), 14f)
                elevation = dp(act, 10f).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try { dismissMenu() } catch (_: Throwable) {}
                    openLongPressModuleSettings(act)
                }
            }
            // The popup's own root often occupies only the bottom-sheet bounds;
            // keep the chip near its top, not at the OS navigation-button area.
            val position = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(act, 42f),
                Gravity.TOP or Gravity.END,
            ).apply {
                rightMargin = dp(act, 12f)
                topMargin = dp(act, 10f)
            }
            target.addView(chip, position)
            LogUtil.info("全集视频长按菜单：使用原生弹窗内部入口，host=" +
                target.javaClass.simpleName)
            LogUtil.incr("videoFullSeriesMenuInjected")
            true
        } catch (e: Throwable) {
            synchronized(gVideoMenuInjected) { gVideoMenuInjected.remove(target) }
            LogUtil.warn("全集视频弹窗入口失败: " + e.message)
            false
        }
    }

    /**
     * Prefer append-only injection into the native vertical command container.
     * Other RecyclerView/ConstraintLayout menus are left untouched rather than
     * shifting the series selector or modifying system bars to make room.
     */
    private fun injectLongPressMenuRoot(
        root: View?, dismissMenu: () -> Unit = {}, allowPopupOverlay: Boolean = false,
    ): Boolean {
        if (root == null || !isInLongPressMenuWindow()) return false
        val act = gCurrentActivity ?: return false
        if (containsVideoMenuEntry(root)) return true
        var best: LinearLayout? = null
        var bestDepth = -1
        fun find(v: View?, depth: Int) {
            if (v == null || depth > 13) return
            if (v is LinearLayout && v.orientation == LinearLayout.VERTICAL &&
                isNativeVideoMenuPanel(v) && depth > bestDepth) {
                best = v
                bestDepth = depth
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) find(v.getChildAt(i), depth + 1)
            }
        }
        find(root, 0)
        val menu = best ?: run {
            if (allowPopupOverlay && isFullSeriesVideoScreen(act)) {
                return attachFullSeriesPopupEntry(root, dismissMenu)
            }
            // A full-series in-activity sheet may use non-vertical views; when
            // it contains multiple video actions we can add a popup-only chip.
            // Never put a chip directly on an unrecognized video view.
            return false
        }
        if (containsVideoMenuEntry(menu)) return true
        synchronized(gVideoMenuInjected) {
            if (gVideoMenuInjected.containsKey(menu)) return true
            gVideoMenuInjected[menu] = true
        }
        return try {
            val row = createLongPressSettingsRow(act, menu, dismissMenu)
            // Append as a native menu row; do not wrap/replace the existing children.
            menu.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 48f),
            ))
            LogUtil.info("视频长按菜单：已添加 Xhongguo 设置 | " + menu.javaClass.name)
            LogUtil.incr("videoLongPressMenuInjected")
            true
        } catch (e: Throwable) {
            synchronized(gVideoMenuInjected) { gVideoMenuInjected.remove(menu) }
            LogUtil.warn("视频长按菜单入口注入失败：" + e.message)
            false
        }
    }

    private fun scheduleLongPressMenuInjection(
        preferredRoot: View? = null, dismissMenu: () -> Unit = {},
        allowPopupOverlay: Boolean = false,
    ) {
        if (!isInLongPressMenuWindow()) return
        for (delay in longArrayOf(0L, 70L, 190L, 420L, 760L)) {
            mainHandler.postDelayed({
                if (!isInLongPressMenuWindow()) return@postDelayed
                if (preferredRoot != null &&
                    injectLongPressMenuRoot(preferredRoot, dismissMenu, allowPopupOverlay)) return@postDelayed
                for (root in collectAllWindows()) {
                    if (injectLongPressMenuRoot(root, dismissMenu)) break
                }
            }, delay)
        }
    }

    private fun isLikelySettingsActivity(act: Activity?): Boolean {
        if (act == null) return false
        val name = try { act.javaClass.name } catch (_: Throwable) { return false }
        if (name == "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity" ||
            name == "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity") return true
        val lower = name.lowercase()
        return lower.startsWith("com.dragon.read") && lower.contains(".mine.") &&
            (lower.endsWith("settingsactivity") || lower.endsWith("settingactivity"))
    }

    private fun installSettingsFooterFallback(act: Activity, content: ViewGroup): Boolean {
        if (containsKejiyuEntry(content)) return true
        if (content.childCount <= 0) return false
        try {

            val oldChildren = ArrayList<Pair<View, ViewGroup.LayoutParams?>>(content.childCount)
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                oldChildren.add(child to child.layoutParams)
            }
            content.removeAllViews()

            val wrapper = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                tag = gKejiyuSettingsWrapperTag
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val stage = FrameLayout(act)
            wrapper.addView(stage, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            for ((child, oldLp) in oldChildren) {
                val lp = if (oldLp is FrameLayout.LayoutParams) oldLp else FrameLayout.LayoutParams(
                    oldLp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    oldLp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
                )
                stage.addView(child, lp)
            }

            val entry = makeNativeSettingsEntry(act, wrapper)

            val divider = View(act).apply {
                background = android.graphics.drawable.ColorDrawable(panelPalette(act).divider)
            }
            wrapper.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1f)
            ))
            wrapper.addView(entry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56f)
            ))
            content.addView(wrapper, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            LogUtil.info("设置页入口已使用固定布局 footer 兜底: children=${oldChildren.size}")
            return true
        } catch (e: Throwable) {
            LogUtil.error("设置页 footer 兜底", e)
            return false
        }
    }

    private fun injectSettingsButton(act: Activity, allowFallback: Boolean = true) {
        try {
            if (act.isFinishing || !isLikelySettingsActivity(act)) return
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (containsKejiyuEntry(content)) return

            val nativeContainer = findNativeSettingsContainer(content)
            if (nativeContainer != null) {
                val entry = makeNativeSettingsEntry(act, nativeContainer)
                nativeContainer.addView(entry, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56f),
                ))
                LogUtil.info("设置页入口已注入原生容器: ${nativeContainer.javaClass.name}")
                return
            }

            if (allowFallback) installSettingsFooterFallback(act, content)
        } catch (e: Exception) { LogUtil.error("设置页入口", e) }
    }

    private fun scheduleSettingsEntryInjection(act: Activity?) {
        if (!isLikelySettingsActivity(act)) return
        val a = act ?: return
        gSettingsActivity = a

        val attempts = arrayOf(
            150L to false,
            550L to false,
            1400L to false,
            2800L to true,
            4600L to true,
        )
        for ((delay, allowFallback) in attempts) {
            mainHandler.postDelayed({
                if (!a.isFinishing && isLikelySettingsActivity(a)) injectSettingsButton(a, allowFallback)
            }, delay)
        }
    }

    private fun pollUpdatePrompt(a: Activity, attempt: Int) {
        if (a.isFinishing || attempt > 10) return
        mainHandler.postDelayed({
            try {
                if (UpdateChecker.hasUpdate || UpdateChecker.lastError != null) {
                    UpdateChecker.showUpdateDialogIfNeeded(a)
                } else if (!a.isFinishing) {
                    pollUpdatePrompt(a, attempt + 1)
                }
            } catch (_: Exception) {}
        }, 1000)
    }

    fun showUpdateDialogIfAvailable() {
        val a = gCurrentActivity ?: return
        if (a.isFinishing) return
        mainHandler.post { UpdateChecker.showUpdateDialogIfNeeded(a) }
    }

    private fun detectTargetPackageVersion(pkg: String): Pair<String?, Long> {
        // onPackageReady may run BEFORE ActivityThread.currentApplication()
        // becomes non-null. Read using the system context if possible; otherwise
        // refresh the verified PackageInfo from Activity.onCreate.
        var ctx = appCtx
        if (ctx == null) {
            try {
                ctx = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null) as? Context
            } catch (_: Throwable) {}
        }
        if (ctx == null) {
            try {
                val at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null)
                ctx = at?.javaClass?.getDeclaredMethod("getSystemContext")
                    ?.invoke(at) as? Context
            } catch (e: Throwable) {
                LogUtil.warn("目标版本早期获取系统 Context 失败: " +
                    e.javaClass.simpleName + ": " + e.message)
            }
        }
        if (ctx != null) {
            try {
                @Suppress("DEPRECATION")
                val pi = ctx.packageManager.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                    else pi.versionCode.toLong()
                LogUtil.info("目标版本通过 Context 识别: " + pi.versionName +
                    " versionCode=" + code)
                return pi.versionName to code
            } catch (e: Throwable) {
                LogUtil.warn("读取目标版本失败，稍后在 Activity 确认: " + e)
            }
        } else {
            LogUtil.warn("onPackageReady 时目标 Context 尚不可用，将在 Activity 启动后确认版本")
        }
        return null to -1L
    }

    private fun refreshTargetVersionFromActivity(activity: Activity) {
        if (gTargetVersionName != "未知" || activity.packageName != gPkg) return
        try {
            @Suppress("DEPRECATION")
            val pi = activity.packageManager.getPackageInfo(gPkg, 0)
            gTargetVersionName = pi.versionName ?: "未知"
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else pi.versionCode.toLong()
            gTargetVersionCode = code
            LogUtil.info("Activity 验证目标版本=" + gTargetVersionName +
                " versionCode=" + gTargetVersionCode + " installedProfile=" +
                gNames.profileId)
            if (TargetNames.isUniversal(gNames)) {
                LogUtil.info("当前版本 " + gTargetVersionName +
                    " 使用独立通用 Hook；不加载任何历史版本混淆映射")
            }
        } catch (e: Throwable) {
            LogUtil.warn("Activity 验证目标版本失败: " + e.message)
        }
    }

    fun installBusinessHooks(module: MainHook, classLoader: ClassLoader, pkg: String) {
        LogUtil.info("── installBusinessHooks ── pkg=$pkg")
        InAppMiniPlayer.openHomeTab = { activity ->
            val tab = nativeTabClickForLabel(activity, "首页")
            if (tab != null) tab.performClick()
            else LogUtil.warn("应用内小窗：未找到首页导航点击控件")
        }
        gTargetIdSet.clear()
        gProgressIdSet.clear()
        gPauseRestoreIdSet.clear()
        gPkg = pkg
        gGestureNavigationCache = null

        try {
            val processCtx = try {
                Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Context
            } catch (_: Throwable) {
                try { Class.forName("android.app.AppGlobals").getDeclaredMethod("getInitialApplication").invoke(null) as? Context } catch (_: Throwable) { null }
            }
            if (processCtx != null) {
                appCtx = processCtx.applicationContext
                initPrefs(processCtx.applicationContext)
            }
        } catch (e: Throwable) {
            LogUtil.warn("下载进程设置初始化失败，将使用磁盘直读兜底: ${e.javaClass.simpleName}: ${e.message}")
        }
        val detected = detectTargetPackageVersion(pkg)
        gTargetVersionName = detected.first ?: "未知"
        gTargetVersionCode = detected.second

        gNames = TargetNames.namesFor(pkg, detected.first, classLoader)
        RuntimeCapabilities.inspect(classLoader, pkg, detected.first)
        // Re-read settings now the target version is known.
        appCtx?.let { initPrefs(it) }
        LogUtil.info("系统导航=" + (if (isGestureNavigation(appCtx)) "gesture" else "three-button/unknown") +
            " | verifiedUI=" + hasVerifiedUiProfile())
        if (TargetNames.isUniversal(gNames)) {
            LogUtil.info("通用 Hook 已启用：无旧版资源 ID、旧版混淆方法或版本猜测；" +
                "逐项按运行时接口安装，未发现则跳过")
        } else if (!hasVerifiedUiProfile()) {
            LogUtil.warn("精确版本映射未通过验证，禁用危险的旧版 UI 资源 Hook")
        }

        if (gNames.useLegacySeedIds) seedIds.forEach { gTargetIdSet.add(it) }
        gNames.staticHideIds.forEach { gTargetIdSet.add(it) }
        gNames.staticProgressIds.forEach { gProgressIdSet.add(it) }
        gNames.pauseRestoreIds.forEach { gPauseRestoreIdSet.add(it) }
        LogUtil.info("目标运行模式=${gNames.profileId} | detectedVersion=${gTargetVersionName}(${gTargetVersionCode})" +
            " | legacyObfuscatedMappings=" + (!TargetNames.isUniversal(gNames)) +
            " | oldResourceIds=" + gTargetIdSet.size)
        LogUtil.info("资源兼容：hideIds=${gTargetIdSet.joinToString { "0x%08X".format(it) }} | progressIds=${gProgressIdSet.joinToString { "0x%08X".format(it) }} | pauseIds=${gPauseRestoreIdSet.joinToString { "0x%08X".format(it) }}")

        try {
            if (!UpdateChecker.checking && UpdateChecker.latestVersion == null) {
                LogUtil.info("作用域启动：自动触发更新检测")
                val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
                UpdateChecker.checkUpdate(ver) { latest ->
                    if (latest != null) {
                        LogUtil.info("作用域启动发现新版本 $latest")
                        showUpdateDialogIfAvailable()
                    }
                }
            }
        } catch (e: Exception) { LogUtil.warn("作用域更新检测启动失败: $e") }

        fun ham(clazz: Class<*>, methodName: String, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            clazz.declaredMethods.filter { it.name == methodName }.forEachIndexed { i, m -> try { module.hook(m).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }
        fun hac(clazz: Class<*>, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            clazz.declaredConstructors.forEachIndexed { i, c -> try { module.hook(c).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }

        if (pkg == TargetNames.CN_PACKAGE || pkg == TargetNames.OVERSEA_PACKAGE) {
            try {
                val mainClazz = Class.forName("com.dragon.read.pages.main.MainFragmentActivity", false, classLoader)
                val nativeMethods = when {
                    TargetNames.isUniversal(gNames) -> listOf("onCreate")
                    pkg == TargetNames.OVERSEA_PACKAGE -> {

                        listOf("onCreate", "B2", "N1", "f1", "j2")
                    }
                    gNames.profileId == "CN-7.3.3.18" -> {

                        listOf("onCreate", "X0", "z2", "M1", "h2")
                    }
                    gNames.profileId == "CN-7.3.2.32" -> {

                        listOf("onCreate", "d1", "y2", "L1", "g2")
                    }
                    else -> {

                        listOf("onCreate", "d1", "z2", "L1", "g2")
                    }
                }
                for ((index, methodName) in nativeMethods.withIndex()) {
                    ham(mainClazz, methodName, "nativeBottomFrame_${pkg.hashCode()}_$index") { chain ->
                        val act = chain.thisObject as? Activity
                        val result = chain.proceed()
                        if (act != null && gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                            mainHandler.post { enforceNativeMainBottomHidden(act) }
                        }
                        result
                    }
                }
                LogUtil.info("  ✓ native MainFragmentActivity BottomTabFrameLayout | pkg=$pkg")
            } catch (e: Throwable) {
                LogUtil.warn("  native BottomTabFrameLayout Hook 未命中 | pkg=$pkg: $e")
            }

            try {
                val bottomFrameClazz = Class.forName("com.dragon.read.widget.BottomTabFrameLayout", false, classLoader)
                ham(bottomFrameClazz, "setBottomTabBackground", "nativeBottomBackground_${pkg.hashCode()}") { chain ->
                    val frame = chain.thisObject as? View
                    val result = chain.proceed()
                    if (gMasterOn && gControlOn && !keepNativeNavigation() &&
                        !(gRestoreControlsOnPause && gVideoPaused)) {
                        collapseNativeMainBottomFrame(frame)
                    }
                    if (isShoppingMallDisabled()) mainHandler.post {
                        scanShoppingTabIfNeeded(gCurrentActivity, force = true)
                    }
                    result
                }
                LogUtil.info("  ✓ BottomTabFrameLayout.setBottomTabBackground/windowNavColor | pkg=$pkg")
            } catch (e: Throwable) {
                LogUtil.warn("  BottomTabFrameLayout 背景 Hook 未命中 | pkg=$pkg: $e")
            }

            try {
                val maskClassName: String
                val maskMethodName: String
                val maskIdName: String
                when {
                    pkg == TargetNames.OVERSEA_PACKAGE -> {
                        maskClassName = "vq3.a"
                        maskMethodName = "d"
                        maskIdName = "bottom_tab_mask"
                    }
                    gNames.profileId == "CN-7.3.3.18" -> {
                        maskClassName = "bu3.a"
                        maskMethodName = "e"
                        maskIdName = "ar9"
                    }
                    else -> {

                        maskClassName = "it3.a"
                        maskMethodName = "d"
                        maskIdName = "ar8"
                    }
                }
                val maskController = Class.forName(maskClassName, false, classLoader)
                val bind = maskController.getDeclaredMethod(maskMethodName, View::class.java)
                module.hook(bind).setId("videoFeedTabBottomMask_${pkg.hashCode()}")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val root = try { chain.getArg(0) as? View } catch (_: Throwable) { null }
                        val result = chain.proceed()
                        if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                            try {

                                val controllerMask = findFieldValue(chain.thisObject, "h") as? View
                                val byId = if (root != null) {
                                    val id = root.resources.getIdentifier(maskIdName, "id", gPkg)
                                    if (id != 0) root.findViewById<View>(id) else null
                                } else null
                                collapseNativeVideoFeedBottomMask(controllerMask ?: byId)
                                enforceNativeMainBottomHidden(gCurrentActivity)
                            } catch (_: Throwable) {}
                        }
                        result
                    })
                LogUtil.info("  ✓ VideoFeedTabBottomMask $maskClassName.$maskMethodName | id=$maskIdName")
            } catch (e: Throwable) {
                LogUtil.warn("  VideoFeedTabBottomMask Hook 未命中 | pkg=$pkg: $e")
            }

            try {
                val feedClazz = Class.forName(
                    "com.dragon.read.component.shortvideo.impl.feedtab.VideoFeedTabFragmentImpl",
                    false,
                    classLoader,
                )
                val layoutMethod = when {
                    pkg == TargetNames.OVERSEA_PACKAGE -> "Mf"
                    gNames.profileId == "CN-7.3.3.18" -> "Lg"

                    else -> "Eg"
                }
                ham(feedClazz, layoutMethod, "feedViewportBottomMargin_${pkg.hashCode()}") { chain ->
                    val root = try { chain.getArg(0) as? View } catch (_: Throwable) { null }
                    val exactOldFeed = pkg == TargetNames.CN_PACKAGE &&
                        (gNames.profileId == "CN-7.3.1.32" || gNames.profileId == "CN-7.3.2.32")
                    val oldMarker = gInsideFeedBottomMarginWrite.get() == true
                    if (exactOldFeed) gInsideFeedBottomMarginWrite.set(true)
                    val result = try { chain.proceed() } finally {
                        if (exactOldFeed) gInsideFeedBottomMarginWrite.set(oldMarker)
                    }
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {

                        if (gNames.profileId == "CN-7.3.2.32" || gNames.profileId == "CN-7.3.1.32") {
                            for (delay in longArrayOf(0L, 32L, 120L)) {
                                mainHandler.postDelayed({
                                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                                        reclaimFeedViewportBottomMargin(root)
                                        enforceKnownFeedViewportBottomMargin()
                                    }
                                }, delay)
                            }
                        } else {
                            mainHandler.post { reclaimFeedViewportBottomMargin(root) }
                        }
                    }
                    result
                }

                ham(feedClazz, "onConfigurationChanged", "feedViewportConfig_${pkg.hashCode()}") { chain ->
                    val result = chain.proceed()
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val root = try { gCurrentActivity?.window?.decorView } catch (_: Throwable) { null }
                        for (delay in longArrayOf(0L, 24L, 90L, 180L)) {
                            mainHandler.postDelayed({
                                if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                                    reclaimFeedViewportBottomMargin(root)
                                    enforceKnownFeedViewportBottomMargin()
                                }
                            }, delay)
                        }
                    }
                    result
                }
                LogUtil.info("  ✓ VideoFeedTabFragmentImpl.$layoutMethod(View) bottomMargin=0 | pkg=$pkg")
            } catch (e: Throwable) {
                LogUtil.warn("  VideoFeedTabFragmentImpl bottomMargin Hook 未命中 | pkg=$pkg: $e")
            }

            if (pkg == TargetNames.CN_PACKAGE &&
                (gNames.profileId == "CN-7.3.1.32" || gNames.profileId == "CN-7.3.2.32")) {
                try {
                    val uiUtils = Class.forName("com.bytedance.common.utility.UIUtils", false, classLoader)
                    val updateMargin = uiUtils.getDeclaredMethod(
                        "updateLayoutMargin",
                        View::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    module.hook(updateMargin)
                        .setId("oldFeedBottomMarginWrite_${pkg.hashCode()}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            var replacementArgs: Array<Any?>? = null
                            if (gInsideFeedBottomMarginWrite.get() == true &&
                                gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                                val view = try { chain.getArg(0) as? View } catch (_: Throwable) { null }
                                val bottom = try { chain.getArg(4) as? Int } catch (_: Throwable) { null }
                                if (view != null && bottom != null && bottom > 0) {
                                    replacementArgs = (chain.args as Array<Any?>).copyOf().also { it[4] = 0 }
                                    synchronized(gKnownFeedViewportViews) { gKnownFeedViewportViews[view] = true }
                                    synchronized(gFeedViewportNativeBottomMargins) { gFeedViewportNativeBottomMargins[view] = bottom }
                                    LogUtil.info("旧版首页 bottomMargin 写入已拦截 | profile=${gNames.profileId} | id=${viewEntryName(view)} | ${bottom}px -> 0")
                                    LogUtil.incr("oldFeedBottomMarginWriteBlock")
                                }
                            }
                            if (replacementArgs != null) chain.proceed(replacementArgs) else chain.proceed()
                        })
                    LogUtil.info("  ✓ old CN UIUtils.updateLayoutMargin 精确拦截 | profile=${gNames.profileId}")
                } catch (e: Throwable) {
                    LogUtil.warn("  old CN updateLayoutMargin 精确拦截未命中: $e")
                }
            }
        }

        try {
            val agencyClass = Class.forName(gNames.rightViewAgency, false, classLoader)
            hac(agencyClass, "rightViewAgencyCtor") { chain ->
                val result = chain.proceed()
                registerRightViewAgency(chain.thisObject)
                result
            }

            ham(agencyClass, gNames.rightViewAgencyEventMethod, "rightViewAgencyEvent") { chain ->
                registerRightViewAgency(chain.thisObject)
                chain.proceed()
            }
            LogUtil.info("  ✓ 评论区 Agency ${gNames.rightViewAgency}.${gNames.rightViewAgencyEventMethod}")
        } catch (e: Throwable) {
            LogUtil.warn("  评论区 Agency Hook 失败: $e")
        }

        var doubleTapHookCount = 0
        for ((handlerIndex, handlerName) in gNames.doubleTapHandlers.withIndex()) {
            try {
                val doubleTapClass = Class.forName(handlerName, false, classLoader)
                val methods = doubleTapClass.declaredMethods.filter {
                    it.name == "onDoubleTap" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == android.view.MotionEvent::class.java
                }
                for ((methodIndex, method) in methods.withIndex()) {
                    module.hook(method).setId("doubleTapComment_${handlerIndex}_${methodIndex}")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            if (!gMasterOn || !gDoubleTapCommentOn) return@Hooker chain.proceed()
                            val now = android.os.SystemClock.uptimeMillis()

                            gSuppressDoubleTapLikeUntil = maxOf(gSuppressDoubleTapLikeUntil, now + 1500L)

                            if (now - gLastDoubleTapCommentAt > 350L) {
                                gLastDoubleTapCommentAt = now
                                val opened = openCurrentCommentFromDoubleTap()
                                LogUtil.incr(if (opened) "doubleTapOpenCommentOK" else "doubleTapOpenCommentNoAgency")
                            } else {
                                LogUtil.incr("doubleTapDuplicateConsumed")
                            }

                            LogUtil.incr("doubleTapLikeBlocked")
                            true
                        })
                    doubleTapHookCount++
                    LogUtil.info("  ✓ 双击评论 $handlerName.onDoubleTap")
                }
            } catch (e: Throwable) {
                LogUtil.warn("  双击处理器 $handlerName Hook 失败: $e")
            }
        }
        if (doubleTapHookCount == 0) LogUtil.warn("  双击评论：当前版本没有安装到任何 onDoubleTap Hook")

        if (gNames.doubleTapLikeView.isNotBlank()) {
            try {
                val likeViewClass = Class.forName(gNames.doubleTapLikeView, false, classLoader)
                likeViewClass.declaredMethods.filter {
                    it.name == "a" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name == "kotlin.Pair" && it.returnType == Void.TYPE
                }.forEachIndexed { index, method ->
                    module.hook(method).setId("doubleTapDiggBlock_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (gMasterOn && gDoubleTapCommentOn && now <= gSuppressDoubleTapLikeUntil) {
                                LogUtil.incr("doubleTapDiggActionBlocked")
                                null
                            } else chain.proceed()
                        })
                }
                LogUtil.info("  ✓ 双击点赞动作兜底 ${gNames.doubleTapLikeView}.a(Pair)")
            } catch (e: Throwable) {
                LogUtil.warn("  双击点赞动作兜底安装失败: $e")
            }
        }

        if (gNames.doubleTapHolderLikeMethod.isNotBlank()) {
            try {
                val holderClass = Class.forName(gNames.shortHolder, false, classLoader)
                holderClass.declaredMethods.filter {
                    it.name == gNames.doubleTapHolderLikeMethod &&
                        it.parameterCount == 0 && it.returnType == Void.TYPE
                }.forEachIndexed { index, method ->
                    module.hook(method).setId("doubleTapHolderLikeBlock_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val now = android.os.SystemClock.uptimeMillis()
                            if (gMasterOn && gDoubleTapCommentOn && now <= gSuppressDoubleTapLikeUntil) {
                                LogUtil.incr("doubleTapHolderLikeBlocked")
                                null
                            } else chain.proceed()
                        })
                }
                LogUtil.info("  ✓ 双击最终点赞兜底 ${gNames.shortHolder}.${gNames.doubleTapHolderLikeMethod}()")
            } catch (e: Throwable) {
                LogUtil.warn("  双击最终点赞兜底安装失败: $e")
            }
        }

        // Universal: bind at the SDK model boundary confirmed in 7.3.5.32.
        // This is shared by home feed, series and their preloaded engines.
        if (TargetNames.isUniversal(gNames)) {
            try {
                val engine = Class.forName(
                    "com.ss.ttvideoengine.TTVideoEngine", false, classLoader)
                var modelHooks = 0
                for ((index, method) in engine.declaredMethods.filter {
                    it.name == "setVideoModel" && it.parameterCount == 1 &&
                        it.parameterTypes[0].name.contains("VideoModel")
                }.withIndex()) {
                    module.hook(method)
                        .setId("universalMaxQualityVideoModel_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            if (gMasterOn && gMaxQualityOn) {
                                try {
                                    rememberAndApplyHighestResolution(
                                        chain.thisObject, chain.getArg(0))
                                } catch (e: Throwable) {
                                    LogUtil.warn("通用最高画质失败: " +
                                        e.javaClass.simpleName)
                                }
                            }
                            result
                        })
                    modelHooks++
                }
                LogUtil.info("  ✓ 通用最高画质 SDK setVideoModel Hooks=$modelHooks")
            } catch (e: Throwable) {
                LogUtil.warn("  通用最高画质缺失: " + e.javaClass.simpleName)
            }
        }
        if (gNames.resolutionController.isNotBlank()) {
            try {
                val controllerClass = Class.forName(gNames.resolutionController, false, classLoader)
                val engineField = controllerClass.getDeclaredField(gNames.resolutionEngineField).apply { isAccessible = true }
                for ((methodIndex, methodName) in gNames.resolutionModelMethods.withIndex()) {
                    ham(controllerClass, methodName, "maxQualityModel_${methodIndex}") { chain ->
                        val controller = chain.thisObject
                        val model = try { chain.getArg(0) } catch (_: Throwable) { null }
                        val highest = findHighestResolution(model)

                        if (gNames.resolutionApplyMethod.isBlank()) {

                            try {
                                val engineBefore = if (controller != null) engineField.get(controller) else null
                                if (engineBefore != null && highest != null) gEngineMaxResolution[engineBefore] = highest
                            } catch (_: Throwable) {}
                        } else if (controller != null && highest != null) {
                            gControllerMaxResolution[controller] = highest
                        }

                        val result = chain.proceed()
                        try {
                            if (highest != null) {
                                LogUtil.info("最高画质：检测到 $highest rank=${resolutionRank(highest)}")
                                if (gNames.resolutionApplyMethod.isNotBlank()) {

                                    if (gMasterOn && gMaxQualityOn) applyHighestViaController(controller, highest)
                                } else {
                                    val engine = if (controller != null) engineField.get(controller) else null
                                    rememberAndApplyHighestResolution(engine, model)
                                }
                            }
                        } catch (e: Throwable) {
                            LogUtil.warn("最高画质：应用失败: $e")
                        }
                        result
                    }
                }

                if (gNames.resolutionApplyMethod.isBlank()) {
                    val engineClass = Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, classLoader)
                    ham(engineClass, "configResolution", "maxQualityConfig") { chain ->
                        if (!gMasterOn || !gMaxQualityOn) return@ham chain.proceed()
                        val engine = chain.thisObject
                        val highest = try { gEngineMaxResolution[engine] } catch (_: Throwable) { null }
                        if (highest == null) return@ham chain.proceed()
                        try {
                            val requested = chain.getArg(0)
                            if (requested !== highest) {
                                val args = (chain.args as Array<Any?>).copyOf()
                                args[0] = highest
                                LogUtil.info("最高画质：拦截 $requested -> $highest")
                                LogUtil.incr("maxQualityOverride")
                                return@ham chain.proceed(args)
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                    LogUtil.info("  ✓ 默认最高画质 ${gNames.resolutionController} + TTVideoEngine")
                } else {
                    LogUtil.info("  ✓ 默认最高画质 ${gNames.resolutionController}.${gNames.resolutionApplyMethod}（原生切画质链）")
                }
            } catch (e: Throwable) {
                LogUtil.warn("  默认最高画质 Hook 失败: $e")
            }
        }

        // Hook native popup presentation rather than replacing the video's
        // long-press listener. The original menu and gesture always proceed.
        try {
            module.hook(Dialog::class.java.getDeclaredMethod("show"))
                .setId("xh_video_longpress_dialog")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    try {
                        val dialog = chain.thisObject as? Dialog
                        if (dialog != null && isInLongPressMenuWindow()) {
                            scheduleLongPressMenuInjection(
                                dialog.window?.decorView,
                                { dialog.dismiss() },
                                allowPopupOverlay = true,
                            )
                        }
                    } catch (e: Throwable) { LogUtil.warn("长按菜单 Dialog 观察失败: " + e.message) }
                    result
                })
            LogUtil.info("  ✓ video long-press Dialog observer")
        } catch (e: Throwable) {
            LogUtil.warn("video long-press Dialog hook unavailable: " + e.message)
        }
        try {
            val popup = Class.forName("android.widget.PopupWindow", false, classLoader)
            for (methodName in listOf("showAtLocation", "showAsDropDown")) {
                ham(popup, methodName, "xh_video_longpress_popup_" + methodName) { chain ->
                    val result = chain.proceed()
                    try {
                        val instance = chain.thisObject as? android.widget.PopupWindow
                        if (instance != null && isInLongPressMenuWindow()) {
                            scheduleLongPressMenuInjection(
                                instance.contentView,
                                { instance.dismiss() },
                                allowPopupOverlay = true,
                            )
                        }
                    } catch (e: Throwable) { LogUtil.warn("长按菜单 Popup 观察失败: " + e.message) }
                    result
                }
            }
            LogUtil.info("  ✓ video long-press PopupWindow observers")
        } catch (e: Throwable) {
            LogUtil.warn("video long-press PopupWindow hooks unavailable: " + e.message)
        }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onWindowFocusChanged", Boolean::class.java))
                .setId("wf")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val focused = chain.getArg(0) as? Boolean == true
                    val result = chain.proceed()
                    if (focused) {
                        val a = chain.thisObject as? Activity
                        try {
                            if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                            if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        } catch (_: Exception) {}
                    }
                    result
                })
            LogUtil.info("  ✓ winFocus")
        } catch (e: Exception) { LogUtil.error("wf", e) }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            ham(c, "onMultiWindowModeChanged", "multiWindow") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "multi-window")
                result
            }
            ham(c, "onPictureInPictureModeChanged", "pictureInPicture") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "picture-in-picture")
                result
            }
            ham(c, "onConfigurationChanged", "windowConfiguration") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "configuration")
                result
            }
            LogUtil.info("  ✓ window mode callbacks")
        } catch (e: Throwable) { LogUtil.warn("  window mode callbacks missing: $e") }

        val seriesActivityClasses = listOf(
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity",
        )
        for ((classIndex, className) in seriesActivityClasses.withIndex()) {
            try {
                val c = Class.forName(className, false, classLoader)
                for (methodName in listOf(
                    "onCreate",
                    "onResume",
                    "onConfigurationChanged",
                    "onMultiWindowModeChanged",
                    "onPictureInPictureModeChanged",
                    "onWindowFocusChanged",
                )) {
                    ham(c, methodName, "seriesWindow_${classIndex}_$methodName") { chain ->
                        val result = chain.proceed()

                        val shouldRefreshLayout = methodName == "onConfigurationChanged" ||
                            methodName == "onMultiWindowModeChanged" ||
                            methodName == "onPictureInPictureModeChanged"
                        reapplySeriesPageState(
                            chain.thisObject,
                            "$className#$methodName",
                            refreshLayout = shouldRefreshLayout,
                        )
                        result
                    }
                }
                LogUtil.info("  ✓ series window tracker: $className")
            } catch (e: Throwable) {
                LogUtil.warn("  series window tracker missing: $className: $e")
            }
        }

        try {
            val className =
                "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
            val c = Class.forName(className, false, classLoader)
            hac(c, "seriesFragmentCtor") { chain ->
                val result = chain.proceed()
                registerShortSeriesFragment(chain.thisObject)
                result
            }
            for (methodName in listOf(
                "onCreateContent",
                "onResume",
                "onConfigurationChanged",
                gNames.seriesFragmentRefreshMethod,
            )) {
                ham(c, methodName, "seriesFragment_$methodName") { chain ->
                    val result = chain.proceed()
                    registerShortSeriesFragment(chain.thisObject)
                    reapplySeriesPageState(
                        chain.thisObject,
                        "$className#$methodName",
                        refreshLayout = methodName != gNames.seriesFragmentRefreshMethod,
                    )
                    result
                }
            }
            LogUtil.info("  ✓ series fragment window tracker")
        } catch (e: Throwable) {
            LogUtil.warn("  series fragment window tracker missing: $e")
        }

        try {
            val c = Class.forName("com.dragon.read.base.ui.util.StatusBarUtil", false, classLoader)
            ham(c, "clearFullScreenFlag", "sbC") { chain ->
                val result = chain.proceed()
                if (shouldHideStatusBar()) {
                    val a = chain.getArg(0) as? Activity
                    mainHandler.post { applyCleanTop(a) }
                }
                result
            }
            ham(c, "hideStatusBar", "sbH") { chain ->
                if (!shouldHideStatusBar()) {
                    chain.proceed()
                } else try {
                    if (chain.args.size >= 2 && chain.getArg(1) is Boolean && !(chain.getArg(1) as Boolean)) {
                        val args = (chain.args as Array<Any?>).copyOf()
                        args[1] = true
                        chain.proceed(args)
                    } else chain.proceed()
                } catch (_: Exception) { chain.proceed() }
            }

            ham(c, "getStatusHeight", "sbHeight") { chain ->
                if (shouldHideStatusBar()) 0 else chain.proceed()
            }
            LogUtil.info("  ✓ StatusBarUtil")
        } catch (e: Exception) { LogUtil.warn("  StatusBarUtil 未找到") }

        try {
            val c = Class.forName("com.bytedance.ies.uikit.statusbar.StatusBarUtils", false, classLoader)
            module.hook(c.getDeclaredMethod("getStatusBarHeight", Context::class.java))
                .setId("uikitStatusHeight")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (shouldHideStatusBar()) 0 else chain.proceed()
                })
            LogUtil.info("  ✓ UIKit StatusBarUtils")
        } catch (e: Exception) { LogUtil.warn("  UIKit StatusBarUtils 未找到: $e") }

        if (TargetNames.isUniversal(gNames)) {
            // PlaybackParams is a stable, inspectable SDK type in 7.3.5.32.
            // Observe changes rather than guessing percentage-player classes.
            try {
                val paramsClass = Class.forName(
                    "com.ss.ttm.player.PlaybackParams", false, classLoader)
                module.hook(paramsClass.getDeclaredMethod(
                    "setSpeed", java.lang.Float.TYPE))
                    .setId("universalPlaybackParamsSpeed")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                        val requested = chain.getArg(0) as? Float
                        if (requested == null ||
                            kotlin.math.abs(requested - gDefaultSpeed) < 0.001f)
                            chain.proceed()
                        else {
                            val args = (chain.args as Array<Any?>).copyOf()
                            args[0] = gDefaultSpeed
                            LogUtil.incr("defaultSpeedUniversalForce")
                            chain.proceed(args)
                        }
                    })
                LogUtil.info("  ✓ 通用默认倍速 PlaybackParams.setSpeed(float)" +
                    " + VideoContext.setPlayBackParams")
            } catch (e: Throwable) {
                LogUtil.warn("  通用默认倍速 Hook 无法绑定: " + e.javaClass.simpleName)
            }
        } else try {
            val isNewCn = gNames.profileId == "CN-7.3.3.18"
            val isOversea = gNames.profileId == "OVERSEA-7.3.1.32"

            val percentPlayerClassName = when {
                isNewCn -> "nx4.w"
                isOversea -> "ys4.x"
                else -> "ov4.x"
            }
            val controllerClassName = when {
                isNewCn -> "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"
                isOversea -> "lt4.v"
                else -> "bw4.v"
            }
            val controllerSetMethod = when {
                isNewCn -> "v2"
                isOversea -> "u2"
                else -> "r2"
            }
            val controllerCacheMethod = if (isNewCn) "w" else "getCacheVideoSpeed"

            val playerClass = Class.forName(percentPlayerClassName, false, classLoader)
            hac(playerClass, "defaultSpeedPlayerCtor") { chain ->
                val result = chain.proceed()
                try {
                    gKnownPercentSpeedPlayers[chain.thisObject] = true
                    if (defaultSpeedEnabledNow()) {
                        val player = chain.thisObject
                        mainHandler.postDelayed({ applySpeedToPercentPlayer(player, "player-ctor") }, 120L)
                    }
                } catch (_: Throwable) {}
                result
            }
            val setPlaySpeed = playerClass.getDeclaredMethod("setPlaySpeed", Integer.TYPE)
            module.hook(setPlaySpeed).setId("defaultSpeedPercentPlayer")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    try { gKnownPercentSpeedPlayers[chain.thisObject] = true } catch (_: Throwable) {}
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val wanted = defaultSpeedPercent()
                    val requested = try { chain.getArg(0) as? Int } catch (_: Throwable) { null }
                    if (requested == wanted) return@Hooker chain.proceed()
                    val args = (chain.args as Array<Any?>).copyOf()
                    args[0] = wanted
                    LogUtil.incr("defaultSpeedPercentForce")
                    chain.proceed(args)
                })

            val controllerClass = Class.forName(controllerClassName, false, classLoader)
            val controllerSetter = controllerClass.getDeclaredMethod(
                controllerSetMethod,
                Boolean::class.javaPrimitiveType,
                java.lang.Float.TYPE,
                Boolean::class.javaPrimitiveType,
            )
            module.hook(controllerSetter).setId("defaultSpeedControllerSet")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val args = (chain.args as Array<Any?>).copyOf()
                    args[1] = gDefaultSpeed
                    LogUtil.incr("defaultSpeedControllerForce")
                    chain.proceed(args)
                })
            val cacheGetter = controllerClass.getDeclaredMethod(controllerCacheMethod, String::class.java)
            module.hook(cacheGetter).setId("defaultSpeedCacheGetter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (defaultSpeedEnabledNow()) gDefaultSpeed else chain.proceed()
                })
            val currentGetter = controllerClass.getDeclaredMethod("getCurrentPlaySpeed")
            module.hook(currentGetter).setId("defaultSpeedCurrentGetter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (defaultSpeedEnabledNow()) defaultSpeedPercent() else chain.proceed()
                })

            val autoplayClass = Class.forName("com.dragon.read.component.shortvideo.impl.autoplay.o", false, classLoader)
            hac(autoplayClass, "defaultSpeedAutoplayCtor") { chain ->
                val result = chain.proceed()
                try {
                    gKnownFloatSpeedPlayers[chain.thisObject] = true
                    if (defaultSpeedEnabledNow()) {
                        val player = chain.thisObject
                        mainHandler.postDelayed({ applySpeedToFloatPlayer(player, "autoplay-ctor") }, 120L)
                    }
                } catch (_: Throwable) {}
                result
            }
            val autoSetSpeed = autoplayClass.getDeclaredMethod("setSpeed", java.lang.Float.TYPE)
            module.hook(autoSetSpeed).setId("defaultSpeedFloatPlayer")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    try { gKnownFloatSpeedPlayers[chain.thisObject] = true } catch (_: Throwable) {}
                    if (!defaultSpeedEnabledNow()) return@Hooker chain.proceed()
                    val requested = try { chain.getArg(0) as? Float } catch (_: Throwable) { null }
                    if (requested != null && kotlin.math.abs(requested - gDefaultSpeed) < 0.001f) return@Hooker chain.proceed()
                    val args = (chain.args as Array<Any?>).copyOf()
                    args[0] = gDefaultSpeed
                    LogUtil.incr("defaultSpeedFloatForce")
                    chain.proceed(args)
                })

            LogUtil.info("  ✓ 默认倍速 | profile=${gNames.profileId} | player=$percentPlayerClassName.setPlaySpeed | controller=$controllerClassName.$controllerSetMethod/$controllerCacheMethod | autoplay=setSpeed")
        } catch (e: Throwable) {
            LogUtil.warn("  默认倍速 Hook 失败 | profile=${gNames.profileId}: $e")
        }

        fun hookPlaybackState(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, "onPlaybackStateChanged", hookId) { chain ->
                    val result = chain.proceed()
                    try {
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        when (state) {
                            1 -> {
                                if (className == "com.ss.android.videoshop.controller.VideoController") {
                                    InAppMiniPlayer.rememberPlayingController(chain.thisObject)
                                    SystemMediaBridge.rememberController(chain.thisObject)
                                }
                                SystemMediaBridge.onNativePlayback(true, "$className#state=1",
                                    chain.thisObject)
                                setVideoPaused(false, "$className#state=1")
                                scheduleDefaultSpeedApply("$className#state=1")
                            }
                            2 -> {
                                SystemMediaBridge.onNativePlayback(false,
                                    "$className#state=2", chain.thisObject)
                                setVideoPaused(true, "$className#state=2")
                            }
                            0, 3 -> if (gVideoPaused) refreshVideoPauseState("$className#state=$state", false)
                        }
                    } catch (e: Throwable) { LogUtil.warn("playback state parse failed: $className: $e") }
                    result
                }
                LogUtil.info("  ✓ playback detector: $className")
            } catch (e: Throwable) { LogUtil.warn("  playback detector missing: $className: $e") }
        }

        hookPlaybackState("com.ss.android.videoshop.controller.VideoController", "vcState")
        hookPlaybackState(gNames.playbackState, "nsState")

        // 7.3.5.32 APK confirms these stable VideoShop APIs. The older
        // obfuscated shortHolder (7.3.3.18) no longer exists, so callback
        // observation must not rely on gNames.shortHolder or gVideoPaused.
        try {
            val videoContextClass = Class.forName(
                "com.ss.android.videoshop.context.VideoContext", false, classLoader
            )
            var mediaHookCount = 0
            for ((methodIndex, method) in videoContextClass.declaredMethods
                .filter { it.name in setOf("onVideoPlay", "onVideoPause",
                    "onEnginePlayStart", "onVideoReleased") }
                .withIndex()) {
                try {
                    module.hook(method)
                        .setId("xhNativeVideoContext_${method.name}_$methodIndex")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            try {
                                val action = method.name
                                when (action) {
                                    "onVideoPlay", "onEnginePlayStart" -> {
                                        SystemMediaBridge.onNativeVideoEvent(
                                            chain.thisObject, "VideoContext#$action", true)
                                        SystemMediaBridge.onNativeVideoStart(
                                            chain.thisObject, "VideoContext#$action")
                                        setVideoPaused(false, "VideoContext#$action")
                                    }
                                    "onVideoPause", "onVideoReleased" -> {
                                        SystemMediaBridge.onNativeVideoEvent(
                                            chain.thisObject, "VideoContext#$action", false)
                                        setVideoPaused(true, "VideoContext#$action")
                                    }
                                }
                            } catch (e: Throwable) {
                                LogUtil.warn("系统媒体: VideoContext 事件异常: " + e.message)
                            }
                            result
                        })
                    mediaHookCount++
                } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: VideoContext 回调 Hook 失败 "+
                        method.name + ": " + e.message)
                }
            }
            for (name in listOf("onLifeCycleOnPause", "onLifeCycleOnStop")) {
                videoContextClass.declaredMethods.filter { it.name == name }
                    .forEachIndexed { index, method ->
                        try {
                            module.hook(method)
                                .setId("xhNativeVideoLifecycle_${name}_$index")
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(Hooker { chain ->
                                    LogUtil.info("系统媒体: VideoContext.$name before" +
                                        " isEnabled=" + SystemMediaBridge.enabled)
                                    val result = chain.proceed()
                                    LogUtil.info("系统媒体: VideoContext.$name after")
                                    result
                                })
                        } catch (e: Throwable) {
                            LogUtil.warn("系统媒体: VideoContext lifecycle Hook " +
                                name + " failed: " + e.message)
                        }
                    }
            }
            for ((i, method) in videoContextClass.declaredMethods
                .filter { it.name in setOf("play", "pause") &&
                    it.parameterCount == 0 }.withIndex()) {
                module.hook(method)
                    .setId("xhNativeVideoTransport_$i")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (method.name == "pause" &&
                            SystemMediaBridge.shouldSuppressLifecyclePause(chain.thisObject)) {
                            return@Hooker null
                        }
                        val result = chain.proceed()
                        if (method.name == "play") {
                            SystemMediaBridge.onNativeVideoStart(chain.thisObject, "VideoContext#play")
                        } else {
                            SystemMediaBridge.onNativeVideoEvent(
                                chain.thisObject, "VideoContext#pause", false)
                        }
                        result
                    })
                mediaHookCount++
            }
            LogUtil.info("  ✓ system media native VideoContext hooks: $mediaHookCount")
        } catch (e: Throwable) {
            LogUtil.warn("  system media VideoContext hooks missing: " + e)
        }

        // The real 7.3.5.x player can reach the low-level TTVideoEngine
        // without calling VideoContext.onVideoPlay, as confirmed by the new
        // diagnostic ZIP. Observe actual engine commands, not just installation.
        try {
            val engine = Class.forName(
                "com.ss.ttvideoengine.TTVideoEngine", false, classLoader)
            var installed = 0
            for ((index, method) in engine.declaredMethods.filter {
                it.name in setOf("play", "pause", "stop", "releaseAsync") &&
                    it.parameterCount == 0
            }.withIndex()) {
                try {
                    module.hook(method)
                        .setId("xhSystemMediaTTVideoEngine_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            if (method.name == "play" && gMasterOn &&
                                (gMiniPlayerOn || gSystemMediaOn) &&
                                SinglePlaybackOwner.shouldBlockPlay(chain.thisObject)) {
                                // This is a retired engine from another page.
                                // No media state or autoplay callback is emitted.
                                return@Hooker null
                            }
                            if (method.name == "pause" &&
                                method.returnType == Void.TYPE) {
                                // Mini is still inside MainFragmentActivity. Its
                                // bottom-tab navigation calls low-level engine
                                // pause directly, bypassing VideoController.
                                if (!SinglePlaybackOwner.isOwnershipTransition() &&
                                    !SystemMediaBridge.isExplicitMediaCommandInProgress() &&
                                    InAppMiniPlayer.shouldKeepNativeEnginePlaying(chain.thisObject)) {
                                    return@Hooker null
                                }
                                if (!SinglePlaybackOwner.isOwnershipTransition() &&
                                    SystemMediaBridge.shouldSuppressBackgroundEnginePause(chain.thisObject)) {
                                    return@Hooker null
                                }
                            }
                            val result = chain.proceed()
                            try {
                                if (method.name == "play") {
                                    if (gMasterOn && (gMiniPlayerOn || gSystemMediaOn))
                                        SinglePlaybackOwner.onEnginePlayed(chain.thisObject)
                                    InAppMiniPlayer.rememberNativeEngine(chain.thisObject)
                                }
                                if (method.name == "releaseAsync")
                                    SinglePlaybackOwner.onEngineReleased(chain.thisObject)
                                InAppMiniPlayer.onNativeEngineAction(
                                    chain.thisObject, method.name)
                                SystemMediaBridge.onNativeEngineCommand(
                                    chain.thisObject, method.name)
                            } catch (e: Throwable) {
                                LogUtil.warn("系统媒体: TTVideoEngine 回调异常 " +
                                    method.name + ": " + e.message)
                            }
                            result
                        })
                    installed++
                } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: TTVideoEngine " +
                        method.name + " Hook 失败: " + e.message)
                }
            }
            LogUtil.info("  ✓ system media TTVideoEngine transport observers: $installed")
        } catch (e: Throwable) {
            LogUtil.warn("  system media TTVideoEngine observer unavailable: " + e.message)
        }

        // Read stable SaasVideoDetailModel objects rather than guessing an
        // obfuscated uo4.y0.x Runnable which is absent in Hongguo 7.3.5.32.
        // Constructor observation is a fallback; Gson.fromJson captures the
        // fully populated DTO before the host releases its temporary reference.
        try {
            val detailClass = Class.forName(
                "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel",
                false, classLoader)
            var modelHooks = 0
            detailClass.declaredConstructors.forEachIndexed { index, ctor ->
                try {
                    module.hook(ctor)
                        .setId("xhMediaSeriesModelCtor_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            if (gMasterOn && gSystemMediaOn) {
                                try {
                                    FullSeriesMediaInfo.observeDetailModel(chain.thisObject)
                                } catch (_: Throwable) { }
                            }
                            result
                        })
                    modelHooks++
                } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: 原生详情构造 Hook 失败: " +
                        e.javaClass.simpleName)
                }
            }
            // Named setters/getters are bound to the host's actual model,
            // independently of its obfuscated presenter/fragment classes.
            detailClass.declaredMethods.filter {
                it.name in setOf("setEpisodesTitle", "setEpisodesCover",
                    "setSmallEpisodesCover", "setEpisodesId",
                    "getEpisodesTitle", "getEpisodesCover",
                    "getSmallEpisodesCover") 
            }.forEachIndexed { index, method ->
                try {
                    module.hook(method)
                        .setId("xhMediaSeriesModelAccess_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            if (gMasterOn && gSystemMediaOn) {
                                try {
                                    FullSeriesMediaInfo.observeDetailModel(chain.thisObject)
                                } catch (_: Throwable) { }
                            }
                            result
                        })
                    modelHooks++
                } catch (_: Throwable) { }
            }
            LogUtil.info("  ✓ system media stable series DTO hooks: $modelHooks")
        } catch (e: Throwable) {
            LogUtil.warn("  system media stable series DTO unavailable: " +
                e.javaClass.simpleName)
        }
        try {
            val gsonClass = Class.forName("com.google.gson.Gson", false, classLoader)
            var installed = 0
            gsonClass.declaredMethods.filter {
                it.name == "fromJson" &&
                    it.returnType != Void.TYPE
            }.forEachIndexed { index, method ->
                try {
                    module.hook(method)
                        .setId("xhMediaSeriesGsonResult_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val result = chain.proceed()
                            // Do not inspect JSON, arguments, requests, or
                            // unrelated DTOs. Only the completed series model.
                            if (gMasterOn && gSystemMediaOn &&
                                result?.javaClass?.name ==
                                    "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel") {
                                try {
                                    FullSeriesMediaInfo.observeDetailModel(result)
                                } catch (e: Throwable) {
                                    LogUtil.warn("系统媒体: 系列详情读取失败 " +
                                        e.javaClass.simpleName)
                                }
                            }
                            result
                        })
                    installed++
                } catch (_: Throwable) { }
            }
            LogUtil.info("  ✓ system media series JSON result observers: $installed")
        } catch (e: Throwable) {
            LogUtil.info("  system media Gson series observer unavailable: " +
                e.javaClass.simpleName)
        }

        // 7.3.5.32's details do NOT necessarily pass through Gson.fromJson
        // or public setters: the RPC adapters assemble DTOs and pass completed
        // instances to their consumers. Bind BY ARGUMENT/RETURN TYPE, never
        // by obfuscated method names. Stable consuming classes are validated
        // against the supplied APK; missing classes simply skip independently.
        try {
            val detailType = Class.forName(
                "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel",
                false, classLoader)
            val videoType = Class.forName(
                "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData",
                false, classLoader)
            val nativeConsumers = listOf(
                "com.dragon.read.component.shortvideo.impl.v2.data.SingleSeriesDataCenter",
                "com.dragon.read.component.shortvideo.impl.v2.data.LikeDataCenter",
                "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment",
                "com.dragon.read.component.shortvideo.impl.seriesdetail.BaseSeriesDetailFragment",
                "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailFragment",
                "com.dragon.read.component.shortvideo.impl.rightview.ShortSeriesRightView",
                "com.dragon.read.component.shortvideo.impl.infoheader.ShortSeriesInfoHeaderView",
                "com.dragon.read.component.shortvideo.impl.NsShortVideoImpl",
                "com.dragon.read.component.shortvideo.data.VideoDetailModelWrapper",
                "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDataWrapper",
                "com.dragon.read.component.shortvideo.impl.videotabranklist.VideoFeedRankListModel",
            )
            var detailBindings = 0
            var videoBindings = 0
            for ((classIndex, className) in nativeConsumers.withIndex()) {
                val owner = try { Class.forName(className, false, classLoader) }
                    catch (_: Throwable) { continue }
                val members = owner.declaredMethods.toList() +
                    owner.declaredConstructors.toList()
                members.forEachIndexed { index, member ->
                    val params = when (member) {
                        is java.lang.reflect.Method -> member.parameterTypes
                        is java.lang.reflect.Constructor<*> -> member.parameterTypes
                        else -> emptyArray()
                    }
                    val returnsDetail = member is java.lang.reflect.Method &&
                        detailType.isAssignableFrom(member.returnType)
                    val returnsVideo = member is java.lang.reflect.Method &&
                        videoType.isAssignableFrom(member.returnType)
                    val consumesDetail = params.any { detailType.isAssignableFrom(it) }
                    val consumesVideo = params.any { videoType.isAssignableFrom(it) }
                    if (!returnsDetail && !returnsVideo &&
                        !consumesDetail && !consumesVideo) return@forEachIndexed
                    try {
                        module.hook(member)
                            .setId("xhNativeSeriesTyped_${classIndex}_${index}")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(Hooker { chain ->
                                val result = chain.proceed()
                                if (gMasterOn && gSystemMediaOn) {
                                    try {
                                        if (consumesDetail || consumesVideo) {
                                            for (i in params.indices) {
                                                val arg = chain.getArg(i)
                                                if (detailType.isAssignableFrom(params[i]))
                                                    FullSeriesMediaInfo.observeDetailModel(arg)
                                                if (videoType.isAssignableFrom(params[i]))
                                                    FullSeriesMediaInfo.observeVideoData(arg)
                                            }
                                        }
                                        if (returnsDetail)
                                            FullSeriesMediaInfo.observeDetailModel(result)
                                        if (returnsVideo)
                                            FullSeriesMediaInfo.observeVideoData(result)
                                    } catch (e: Throwable) {
                                        LogUtil.warn("系统媒体: 原生业务 DTO 采集异常 " +
                                            e.javaClass.simpleName)
                                    }
                                }
                                result
                            })
                        if (consumesDetail || returnsDetail) detailBindings++
                        if (consumesVideo || returnsVideo) videoBindings++
                    } catch (_: Throwable) { }
                }
            }
            LogUtil.info("  ✓ system media typed series consumers" +
                " detail=$detailBindings video=$videoBindings" +
                " runtimeMethodNames=discovered")
        } catch (e: Throwable) {
            LogUtil.warn("  system media typed series consumers unavailable: " +
                e.javaClass.simpleName + ": " + e.message)
        }

        // Capture the original VideoShop PlayEntity model rather than
        // guessing title/episode from currently visible recommendations.
        // The PlayEntity APIs are present in the user's 7.3.5.32 APK.
        try {
            val entityClass = Class.forName(
                "com.ss.android.videoshop.entity.PlayEntity", false, classLoader)
            val controllerClass = Class.forName(
                "com.ss.android.videoshop.controller.VideoController", false, classLoader)
            var registrations = 0
            for ((index, method) in controllerClass.declaredMethods
                .filter { it.name == "setPlayEntity" && it.parameterCount == 1 }
                .withIndex()) {
                module.hook(method)
                    .setId("xhMediaEntityBound_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        try { NativePlayEntityInfo.remember(chain.getArg(0)) }
                        catch (e: Throwable) {
                            LogUtil.warn("系统媒体: PlayEntity 关联监听异常: " + e.message)
                        }
                        result
                    })
                registrations++
            }
            for ((index, method) in entityClass.declaredMethods
                .filter { it.name in setOf("setTitle", "setVideoId",
                    "setBusinessModel") && it.parameterCount == 1 }
                .withIndex()) {
                module.hook(method)
                    .setId("xhMediaEntityChanged_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        try { NativePlayEntityInfo.remember(chain.thisObject) }
                        catch (_: Throwable) {}
                        result
                    })
                registrations++
            }
            LogUtil.info("  ✓ system media original PlayEntity observers: $registrations")
        } catch (e: Throwable) {
            LogUtil.warn("  system media PlayEntity observers unavailable: " + e.message)
        }

        try {
            val controller = Class.forName(
                "com.ss.android.videoshop.controller.VideoController", false, classLoader
            )
            for ((i, method) in controller.declaredMethods
                .filter { it.name == "play" && it.parameterCount == 0 }.withIndex()) {
                module.hook(method)
                    .setId("xhSystemMediaControllerPlay_$i")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        SystemMediaBridge.onNativeVideoStart(
                            chain.thisObject, "VideoController#play")
                        result
                    })
            }
            LogUtil.info("  ✓ system media native VideoController.play observer")
        } catch (e: Throwable) {
            LogUtil.warn("  system media VideoController.play observer unavailable: " + e.message)
        }

        try {
            val controller = Class.forName("com.ss.android.videoshop.controller.VideoController", false, classLoader)
            val pausingMethods = controller.declaredMethods.filter { method ->
                method.name in setOf("pause", "pauseVideo") &&
                    method.returnType == Void.TYPE && method.parameterCount == 0
            }
            for ((index, method) in pausingMethods.withIndex()) {
                module.hook(method).setId("miniHoldPause_" + index)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (InAppMiniPlayer.shouldKeepPlaying(chain.thisObject)) {
                            LogUtil.incr("inAppMiniPauseIntercept")
                            null
                        } else if (SystemMediaBridge.shouldSuppressLifecyclePause(chain.thisObject)) {
                            null
                        } else {
                            val result = chain.proceed()
                            SystemMediaBridge.onNativeVideoEvent(
                                chain.thisObject, "VideoController#pause", false)
                            result
                        }
                    })
            }
            LogUtil.info("  ✓ mini player native pause guards: " + pausingMethods.size)
        } catch (e: Throwable) {
            LogUtil.warn("  mini player pause guard unavailable: " + e.message)
        }

        val shortVideoHolderClasses =
            listOf(gNames.holderBaseS1).filter { it.isNotBlank() }
        var shortPlaybackHookCount = 0
        shortVideoHolderClasses.forEachIndexed { classIndex, className ->
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, gNames.shortStateMethod, "shortState_${classIndex}") { chain ->
                    val result = chain.proceed()
                    try {
                        val holder = chain.thisObject
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        registerShortVideoHolder(holder, state)
                        if (state == 1 || state == 2) {
                            mainHandler.post {
                                if (isShortVideoHolderVisible(holder)) {
                                    setVideoPaused(state == 2, "$className#${gNames.shortStateMethod}=$state")
                                    if (state == 1) scheduleDefaultSpeedApply("$className#${gNames.shortStateMethod}=1")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        LogUtil.warn("short-video playback parse failed: $className: $e")
                    }
                    result
                }
                shortPlaybackHookCount++
            } catch (_: Throwable) {}
        }
        if (gNames.shortHolder.isNotBlank()) try {
            val c = Class.forName(gNames.shortHolder, false, classLoader)
            hac(c, "shortHolderCtor") { chain ->
                val result = chain.proceed()
                registerShortVideoHolder(chain.thisObject)
                result
            }
            ham(c, "onBind", "shortHolderBind") { chain ->
                val result = chain.proceed()

                registerShortVideoHolder(chain.thisObject, 0)
                if (gRestoreControlsOnPause) refreshVideoPauseState("short-holder-bind")
                result
            }

            try {
                val nativeClean = c.getDeclaredMethod(
                    gNames.shortControlsMethod,
                    Boolean::class.java,
                    Boolean::class.java,
                )
                module.hook(nativeClean)
                    .setId("shortNativeClean")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (!shouldForceShortVideoCleanMask()) return@Hooker chain.proceed()
                        try {
                            val requested = chain.getArg(0) as? Boolean
                            if (requested == false) {
                                val args = (chain.args as Array<Any?>).copyOf()
                                args[0] = true
                                LogUtil.incr("shortNativeCleanForce")
                                return@Hooker chain.proceed(args)
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    })
                LogUtil.info("  ✓ native clean-screen ${gNames.shortHolder}.${gNames.shortControlsMethod}")
            } catch (e: Throwable) {
                LogUtil.warn("  native clean-screen hook missing: $e")
            }

            module.hook(c.getDeclaredMethod(gNames.shortMaskMethod, Boolean::class.java))
                .setId("shortMask")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val result = chain.proceed()
                    if (shouldForceShortVideoCleanMask()) rememberAndForceShortVideoMaskInvisible(chain.thisObject)
                    result
                })
        } catch (_: Throwable) {}

        if (gPkg == "com.phoenix.read") {
            try {
                val homeFragment = Class.forName(
                    "com.dragon.read.component.shortvideo.impl.v2.SeriesBookMallTabFragment",
                    false,
                    classLoader,
                )
                hac(homeFragment, "cnHomeFragmentCtor") { chain ->
                    val result = chain.proceed()
                    registerCnHomeFragment(chain.thisObject)
                    result
                }
                val homeMaskMethod = gNames.homeFragmentMaskMethod
                if (homeMaskMethod.isBlank()) throw NoSuchMethodException("homeFragmentMaskMethod blank")
                module.hook(homeFragment.getDeclaredMethod(homeMaskMethod, Boolean::class.java))
                    .setId("cnHomeMask")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        registerCnHomeFragment(chain.thisObject)

                        val result = if (shouldForceShortVideoCleanMask()) {
                            try {
                                val requested = chain.getArg(0) as? Boolean
                                if (requested == false) {
                                    val args = (chain.args as Array<Any?>).copyOf()
                                    args[0] = true
                                    LogUtil.incr("homeNativeCleanForce")
                                    chain.proceed(args)
                                } else chain.proceed()
                            } catch (_: Throwable) { chain.proceed() }
                        } else chain.proceed()
                        forceCnHomeFragmentMaskInvisible(chain.thisObject)
                        result
                    })
                LogUtil.info("  ✓ CN home fragment mask ${gNames.homeFragmentMaskMethod}/${gNames.homeFragmentMaskField}")
            } catch (e: Throwable) {
                LogUtil.warn("  CN home fragment mask missing: $e")
            }
        }

        LogUtil.info("  ✓ short-video playback detectors: $shortPlaybackHookCount")

        try {
            val c = Class.forName("com.ss.android.videoshop.mediaview.LayerHostMediaLayout", false, classLoader)
            ham(c, "execCommand", "videoCmd") { chain ->
                var command: Int? = null
                try {
                    val cmd = chain.getArg(0)
                    if (cmd != null) command = cmd.javaClass.getMethod("getCommand").invoke(cmd) as? Int
                } catch (_: Throwable) {}
                val result = chain.proceed()
                when (command) {
                    208 -> refreshVideoPauseState("command=208", true)
                    207, 214 -> refreshVideoPauseState("command=$command", false)
                }
                result
            }
            ham(c, "onVideoPause", "videoPauseDirect") { chain ->
                val result = chain.proceed()
                SystemMediaBridge.rememberLayer(chain.thisObject)
                SystemMediaBridge.onNativeVideoEvent(chain.thisObject,
                    "LayerHostMediaLayout#onVideoPause", false)
                setVideoPaused(true, "LayerHostMediaLayout#onVideoPause")
                result
            }
            ham(c, "onVideoPlay", "videoPlayDirect") { chain ->
                val result = chain.proceed()
                SystemMediaBridge.rememberLayer(chain.thisObject)
                SystemMediaBridge.onNativeVideoEvent(chain.thisObject,
                    "LayerHostMediaLayout#onVideoPlay", true)
                SystemMediaBridge.onNativeVideoStart(
                    chain.thisObject, "LayerHostMediaLayout#onVideoPlay")
                setVideoPaused(false, "LayerHostMediaLayout#onVideoPlay")
                scheduleDefaultSpeedApply("LayerHostMediaLayout#onVideoPlay")
                result
            }
            ham(c, "onEnginePlayStart", "xhMediaLayerEngineStart") { chain ->
                val result = chain.proceed()
                SystemMediaBridge.rememberLayer(chain.thisObject)
                SystemMediaBridge.onNativeVideoEvent(chain.thisObject,
                    "LayerHostMediaLayout#onEnginePlayStart", true)
                SystemMediaBridge.onNativeVideoStart(chain.thisObject,
                    "LayerHostMediaLayout#onEnginePlayStart")
                result
            }
            ham(c, "onVideoReleased", "xhMediaLayerReleased") { chain ->
                val result = chain.proceed()
                SystemMediaBridge.onNativeVideoEvent(chain.thisObject,
                    "LayerHostMediaLayout#onVideoReleased", false)
                result
            }
            ham(c, "setCanPlayBackground", "xhNativeVideoBackgroundPermission") { chain ->
                try {
                    SystemMediaBridge.rememberOriginalBackgroundSetting(
                        chain.thisObject, chain.getArg(0) as? Boolean)
                } catch (_: Throwable) {}
                chain.proceed()
            }
            LogUtil.info("  ✓ video command detector + native background allowance observer")
        } catch (e: Throwable) { LogUtil.warn("  video command detector missing: $e") }

        fun hookToolbarLayer(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                hac(c, hookId) { chain ->
                    val result = chain.proceed()
                    registerVideoToolbarLayer(chain.thisObject)
                    refreshVideoPauseState("layer-created:$className")
                    result
                }
                LogUtil.info("  ✓ toolbar layer tracker: $className")
            } catch (e: Throwable) { LogUtil.warn("  toolbar layer tracker missing: $className: $e") }
        }
        hookToolbarLayer("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", "fixedLayer")
        hookToolbarLayer("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", "customLayer")

        fun trackLayerFromCall(chain: XposedInterface.Chain) {
            try { registerVideoToolbarLayer(chain.thisObject) } catch (_: Throwable) {}
        }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", false, classLoader)
            ham(c, gNames.fixedToolbarShowMethod, "pb") { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("btmBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } }
            LogUtil.info("  ✓ playerBtm") } catch (e: Exception) { LogUtil.warn("  ToolbarLayerFixed 未找到") }

        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            ham(c, gNames.customizeToolbarShowMethod, "pt") { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("topBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } }
            LogUtil.info("  ✓ playerTop") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer 未找到") }

        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            module.hook(c.getDeclaredMethod(gNames.customizeToolbarApplyMethod, Boolean::class.java, Boolean::class.java, Boolean::class.java)).setId("ctS").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("topBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } })
            LogUtil.info("  ✓ CustomizeToolbarLayer.${gNames.customizeToolbarApplyMethod}") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer.${gNames.customizeToolbarApplyMethod} 未找到") }

        try { val c = Class.forName(gNames.toolbarBase, false, classLoader)
            hac(c, "toolbarBaseCtor") { chain -> val r = chain.proceed(); registerToolbarBaseLayer(chain.thisObject); r }
            module.hook(c.getDeclaredMethod("a", Boolean::class.java)).setId("gt7c").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                registerToolbarBaseLayer(chain.thisObject)
                if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("toolbarBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() }
            })
            LogUtil.info("  ✓ ${gNames.toolbarBase}.a") } catch (e: Exception) { LogUtil.warn("  ${gNames.toolbarBase} 未找到") }

        try {
            val mgrClass = Class.forName("com.dragon.read.base.ssconfig.SsConfigMgr", false, classLoader)
            ham(mgrClass, "getABValueJson", "downloadLimitAB") { chain ->
                try {
                    val key = chain.getArg(0)?.toString()
                    if (key == DOWNLOAD_LIMIT_AB_KEY && downloadUnlimitEnabledNow()) {
                        val json = customDownloadLimitJson()
                        LogUtil.incr("downloadLimitABOverride")
                        LogUtil.info("download limit override: SsConfigMgr.getABValueJson -> $json profile=${gNames.profileId}")
                        return@ham json
                    }
                } catch (e: Throwable) {
                    LogUtil.warn("download limit AB override failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                chain.proceed()
            }
            LogUtil.info("  ✓ 下载数量限制 AB Hook SsConfigMgr.getABValueJson")
        } catch (e: Throwable) {
            LogUtil.warn("  下载数量限制 AB Hook 失败: $e")
        }

        if (!TargetNames.isUniversal(gNames)) try {
            val svcClass = Class.forName("s93.q", false, classLoader)
            val a7Methods = svcClass.declaredMethods.filter { it.name == "A7" && it.parameterCount >= 1 }
            a7Methods.forEachIndexed { index, method ->
                module.hook(method).setId("downloadLimitKmpA7_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val key = try { chain.getArg(0)?.toString() } catch (_: Throwable) { null }
                        if (key == DOWNLOAD_LIMIT_AB_KEY && downloadUnlimitEnabledNow()) {
                            val json = customDownloadLimitJson()
                            LogUtil.incr("downloadLimitKmpOverride")
                            LogUtil.info("download limit override: s93.q.A7 -> $json profile=${gNames.profileId}")
                            json
                        } else chain.proceed()
                    })
            }
            if (a7Methods.isNotEmpty()) LogUtil.info("  ✓ 下载数量限制 KMP Hook s93.q.A7")
        } catch (_: Throwable) {}

        if (!TargetNames.isUniversal(gNames)) try {
            val configClass = Class.forName("xh5.f", false, classLoader)
            hac(configClass, "downloadLimitConfigCtor") { chain ->
                val result = chain.proceed()
                if (downloadUnlimitEnabledNow() && overwriteDownloadLimitObject(chain.thisObject)) {
                    LogUtil.incr("downloadLimitObjectOverride")
                    LogUtil.info("download limit override: xh5.f ctor a/b/c=${currentDownloadLimitValues()}")
                }
                result
            }
            try {
                val serializerClass = Class.forName("xh5.f\$a", false, classLoader)
                ham(serializerClass, "deserialize", "downloadLimitDeserialize") { chain ->
                    val result = chain.proceed()
                    if (downloadUnlimitEnabledNow() && overwriteDownloadLimitObject(result)) {
                        LogUtil.incr("downloadLimitDeserializeOverride")
                        LogUtil.info("download limit override: xh5.f\$a.deserialize a/b/c=${currentDownloadLimitValues()}")
                    }
                    result
                }
            } catch (_: Throwable) {}
            LogUtil.info("  ✓ 下载数量限制对象 Hook xh5.f")
        } catch (_: Throwable) {}

        try { val c = Class.forName("com.dragon.read.component.biz.impl.BsGoldBoxServiceImpl", false, classLoader); ham(c, "tryAttach", "gb") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }; LogUtil.info("  ✓ goldBox") } catch (e: Exception) { LogUtil.warn("  BsGoldBoxServiceImpl 未找到") }
        try { val c = Class.forName("com.bytedance.ug.sdk.novel.pendant.PlanPendantServiceImpl", false, classLoader); ham(c, "triggerEvent", "pd") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }; LogUtil.info("  ✓ pendant") } catch (e: Exception) { LogUtil.warn("  PlanPendantServiceImpl 未找到") }

        try { var rc: Class<*>? = null; try { rc = Class.forName("com.dragon.read.component.biz.impl.bookmall.holder.video.VideoRedPacketHolder", false, classLoader) } catch (_: Exception) {}; if (rc != null) { hac(rc, "rp") { chain -> if (!gMasterOn || !gAdOn) chain.proceed() else { val r = chain.proceed(); try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; iv?.visibility = View.GONE; iv?.layoutParams = ViewGroup.LayoutParams(0, 0) } catch (_: Exception) {}; r } }; LogUtil.info("  ✓ redPack") } } catch (e: Exception) { LogUtil.warn("redPack: $e") }

        if (gNames.shortHolder.isNotBlank() &&
            gNames.shortLandscapeMethod.isNotBlank()) try {
            val c = Class.forName(gNames.shortHolder, false, classLoader);
            module.hook(c.getDeclaredMethod(gNames.shortLandscapeMethod, Boolean::class.java)).setId("fb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { val na = (chain.args as Array<Any?>).copyOf(); na[0] = false; chain.proceed(na) } else chain.proceed() } catch (_: Exception) { chain.proceed() } }); LogUtil.info("  ✓ ${gNames.shortHolder}.${gNames.shortLandscapeMethod}") } catch (e: Exception) { LogUtil.warn("  ${gNames.shortHolder} 未找到") }

        try {
            val c = Class.forName("android.view.LayoutInflater", false, classLoader)
            ham(c, "inflate", "inf") { chain ->
                val result = chain.proceed()
                if (result is ViewGroup) {
                    LogUtil.incr("inflate")
                    installSeriesToolbarGuardsInTree(result)
                    gNames.hideIdNames.forEach { resolveEntryId(it, result) }
                    gNames.progressIdNames.forEach { resolveEntryId(it, result, gProgressIdSet, "进度条资源") }
                    resolvePauseRestoreIds(result)
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) scanTreeQuick(result)
                    if (gMasterOn && gRefreshOff) scanTreeRefreshAccessory(result)
                    if (gMasterOn && gPlayerOn && !(gRestoreControlsOnPause && gVideoPaused)) scanTreePlayer(result)
                    if (gMasterOn && gProgressOff && !(gRestoreControlsOnPause && gVideoPaused)) scanTreeProgress(result)
                }
                result
            }
            LogUtil.info("  ✓ inflate")
        } catch (e: Exception) { LogUtil.error("inflate", e) }

        try {
            val c = Class.forName("android.view.View", false, classLoader)
            ham(c, "setVisibility", "sv") { chain ->
                if (gInternalViewMutation.get() == true) return@ham chain.proceed()
                val view = chain.thisObject as? View
                val targetVisibility = chain.getArg(0) as? Int

                if (view != null && targetVisibility != null) {
                    synchronized(gSavedViewStates) {
                        gSavedViewStates[view]?.visibility = targetVisibility
                    }
                }

                if (view != null && isKnownShoppingTabView(view) &&
                    targetVisibility != null && isShoppingMallDisabled()) {
                    synchronized(gOriginalShoppingTabVisibility) {
                        gOriginalShoppingTabVisibility[view] = targetVisibility
                    }
                    if (targetVisibility != View.GONE) {
                        val args = (chain.args as Array<Any?>).copyOf()
                        args[0] = View.GONE
                        return@ham chain.proceed(args)
                    }
                }
                if (!gMasterOn || (gRestoreControlsOnPause && gVideoPaused)) return@ham chain.proceed()
                try {
                    LogUtil.incr("setVis")
                    if (targetVisibility == View.VISIBLE && view != null) {
                        val replacement = when {
                            shouldForceShortVideoCleanMask() && synchronized(gCleanMaskViews) { gCleanMaskViews.containsKey(view) } -> View.INVISIBLE
                            gRefreshOff && (synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews.containsKey(view) } || isRefreshAccessoryContainer(view)) -> {
                                synchronized(gKnownRefreshAccessoryViews) { gKnownRefreshAccessoryViews[view] = true }
                                View.GONE
                            }
                            gControlOn && isNativeMainBottomFrame(view) -> {
                                synchronized(gKnownMainBottomNavViews) { gKnownMainBottomNavViews[view] = true }
                                View.GONE
                            }
                            gControlOn && isNativeVideoFeedBottomMask(view) -> {
                                synchronized(gKnownBottomBackdropViews) { gKnownBottomBackdropViews[view] = true }
                                mainHandler.post { enforceNativeMainBottomHidden(gCurrentActivity) }
                                View.GONE
                            }
                            gControlOn && isHomeBottomBackdropMarker(view) -> {
                                mainHandler.post { collapseHomeBottomBackdrop(view) }
                                View.GONE
                            }
                            gControlOn && (quickMatch(view) || isKnownMainBottomNav(view) || isMainBottomNavContainer(view) || isKnownBottomBackdrop(view)) ->
                                if (shouldCollapseControl(view)) View.GONE else View.INVISIBLE
                            gPlayerOn && seriesToolbarKind(view) != 0 -> {
                                rememberSeriesToolbar(view, seriesToolbarKind(view))
                                View.INVISIBLE
                            }
                            gProgressOff && isProgressBar(view) -> View.GONE
                            else -> null
                        }
                        if (replacement != null) {
                            rememberViewState(view, View.VISIBLE)
                            val args = (chain.args as Array<Any?>).copyOf()
                            args[0] = replacement
                            return@ham chain.proceed(args)
                        }
                    }
                    chain.proceed()
                } catch (_: Exception) { chain.proceed() }
            }
            LogUtil.info("  ✓ setVis")
            // Return false (not consumed by the native click handler) for the
            // removed shopping-tab item and any of its icon/text descendants.
            ham(c, "performClick", "shoppingTabPerformClick") { chain ->
                if (isShoppingMallDisabled() &&
                    isWithinShoppingTab(chain.thisObject as? View)) {
                    false
                } else {
                    try { beforeNativeTabClick(chain.thisObject as? View) } catch (e: Throwable) {
                        LogUtil.warn("应用内小窗：点击触发失败 " + e.message)
                    }
                    chain.proceed()
                }
            }
            ham(c, "callOnClick", "shoppingTabCallOnClick") { chain ->
                if (isShoppingMallDisabled() &&
                    isWithinShoppingTab(chain.thisObject as? View)) {
                    false
                } else {
                    try { beforeNativeTabClick(chain.thisObject as? View) } catch (_: Throwable) {}
                    chain.proceed()
                }
            }
        } catch (e: Exception) { LogUtil.error("setVis", e) }
        try {
            val c = Class.forName("android.view.ViewGroup", false, classLoader)
            ham(c, "addView", "av") { chain ->
                val v = try { chain.getArg(0) as? View } catch (_: Throwable) { null }

                val result = chain.proceed()
                try {
                    if (v != null) installSeriesToolbarGuardsInTree(v)
                    if (!gMasterOn || (gRestoreControlsOnPause && gVideoPaused)) return@ham result
                    LogUtil.incr("addView")
                    if (v != null) {
                        if (gControlOn && isNativeMainBottomFrame(v)) collapseNativeMainBottomFrame(v)
                        else if (gControlOn && isNativeVideoFeedBottomMask(v)) collapseNativeVideoFeedBottomMask(v)
                        else if (gControlOn && (quickMatch(v) || isMainBottomNavContainer(v))) blindView(v)
                        if (gRefreshOff && isRefreshAccessoryContainer(v)) hideRefreshAccessory(v)
                        if (gPlayerOn) hideSeriesToolbarView(v)
                        if (gProgressOff && isProgressBar(v) && v.visibility == View.VISIBLE) {
                            rememberViewState(v)
                            setModuleVisibility(v, View.GONE)
                        }
                    }
                } catch (_: Exception) {}
                result
            }
            LogUtil.info("  ✓ addView")
        } catch (e: Exception) { LogUtil.error("addView", e) }

        try { val c = Class.forName("com.dragon.read.recyler.AbsRecyclerViewHolder", false, classLoader); hac(c, "cc") { chain -> if (gMasterOn) try { val v = chain.getArg(0) as? View; if (v != null) { if (gControlOn) scanTreeQuick(v) else scanTreeRestore(v) } } catch (_: Exception) {}; chain.proceed() }; try { module.hook(c.getDeclaredMethod("onBind", Object::class.java, Int::class.java)).setId("cb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn) try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; if (iv != null) { if (gControlOn) scanTreeQuick(iv) else scanTreeRestore(iv) } } catch (_: Exception) {}; chain.proceed() }) } catch (_: Exception) {}; LogUtil.info("  ✓ card") } catch (e: Exception) { LogUtil.warn("  AbsRecyclerViewHolder 未找到") }

        try { val c = Class.forName("androidx.swiperefreshlayout.widget.SwipeRefreshLayout", false, classLoader); module.hook(c.getDeclaredMethod("onInterceptTouchEvent", android.view.MotionEvent::class.java)).setId("sw").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gRefreshOff) false else chain.proceed() }); LogUtil.info("  ✓ swipe") } catch (e: Exception) { LogUtil.warn("  SwipeRefreshLayout 未找到") }

        if (gNames.topZoneTouch.isNotBlank()) try { val c = Class.forName(gNames.topZoneTouch, false, classLoader); module.hook(c.getDeclaredMethod("onTouchEvent", android.view.MotionEvent::class.java)).setId("tz1").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gTopZoneOn && handleTopZoneEvent(chain.getArg(0) as? android.view.MotionEvent)) true else chain.proceed() }); LogUtil.info("  ✓ ${gNames.topZoneTouch}") } catch (e: Exception) { LogUtil.warn("  ${gNames.topZoneTouch} 未找到") }
        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java))
                .setId("tz2")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val ev = chain.getArg(0) as? android.view.MotionEvent
                    val activity = chain.thisObject as? Activity
                    try { observeNativeTabTouch(ev, activity) } catch (e: Throwable) {
                        LogUtil.warn("应用内小窗触摸导航检测失败: " + e.message)
                    }
                    try { observeVideoLongPress(ev, activity) } catch (_: Throwable) {}
                    if (gMasterOn && gTopZoneOn && handleTopZoneEvent(ev)) true
                    else chain.proceed()
                })
            LogUtil.info("  ✓ dispatchTouch + native long-press menu observer")
        } catch (e: Exception) { LogUtil.error("tz2", e) }

        // Full-series playback uses different Activity classes from the home feed.
        // Some override dispatchTouchEvent without calling Activity.super, so the
        // generic Activity observer never sees their long-press gestures.
        try {
            val candidates = listOf(
                "com.dragon.read.pages.main.MainFragmentActivity",
                "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
                "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
                "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity",
            )
            val hooked = HashSet<Class<*>>()
            for (className in candidates) {
                val clazz = try { Class.forName(className, false, classLoader) }
                    catch (_: Throwable) { continue }
                val method = try {
                    clazz.getMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)
                } catch (_: Throwable) { null } ?: continue
                if (method.declaringClass == Activity::class.java ||
                    !hooked.add(method.declaringClass)) continue
                try {
                    module.hook(method)
                        .setId("xh_fullseries_touch_" + hooked.size)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val activity = chain.thisObject as? Activity
                            try {
                                observeVideoLongPress(chain.getArg(0) as? android.view.MotionEvent,
                                    activity)
                            } catch (e: Throwable) {
                                LogUtil.warn("全集长按触摸观察异常: " + e.message)
                            }
                            chain.proceed()
                        })
                    LogUtil.info("  ✓ fullscreen touch " + method.declaringClass.name)
                } catch (e: Throwable) {
                    LogUtil.warn("  fullscreen touch hook failed: " + e.message)
                }
            }
        } catch (e: Throwable) {
            LogUtil.warn("全集长按监听初始化失败: " + e.message)
        }

        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java)).setId("oc").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onCreate")
                val a = chain.thisObject as? Activity

                try {
                    if (a != null) {

                        gCurrentActivity = a
                        appCtx = a.applicationContext
                        refreshTargetVersionFromActivity(a)
                        initPrefs(a.applicationContext)
                        ensureReceiver(a.applicationContext)
                    }
                } catch (_: Exception) {}
                val result = chain.proceed()
                try {
                    if (a != null) {
                        if (isShoppingMallDisabled() && a.javaClass.name == SHOP_ACTIVITY) {
                            a.finish()
                            LogUtil.info("商城入口已禁用: " + a.javaClass.name)
                            LogUtil.incr("shoppingActivityBlocked")
                        }
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        if (a.javaClass.name == MAIN_TAB_ACTIVITY) mainHandler.postDelayed({
                            scanShoppingTabIfNeeded(a, force = true)
                        }, 200L)
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                result
            })
            LogUtil.info("  ✓ onCreate")
            module.hook(c.getDeclaredMethod("onResume")).setId("or").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onResume")
                val a = chain.thisObject as? Activity
                gCurrentActivity = a
                if (gMasterOn && (gMiniPlayerOn || gSystemMediaOn)) {
                    try { SinglePlaybackOwner.onActivityResuming(a) }
                    catch (e: Throwable) {
                        LogUtil.warn("单播放器: 页面切换前置状态失败: " + e.message)
                    }
                }

                scheduleSettingsEntryInjection(a)

                try {
                    if (a != null) {
                        UpdateChecker.showUpdateDialogIfNeeded(a)
                        pollUpdatePrompt(a, 0)
                    }
                } catch (_: Exception) {}
                try {
                    if (a != null) {
                        // Navigation style may change in Android Settings while paused.
                        gGestureNavigationCache = null
                        initPrefs(a.applicationContext)
                        appCtx = a.applicationContext
                        ensureReceiver(a.applicationContext)
                    }
                } catch (_: Exception) {}
                val result = chain.proceed()
                try {
                    if (a != null) {
                        SystemMediaBridge.onActivityResumed(a)
                        if (gMasterOn && gMiniPlayerOn) {
                            try {
                                InAppMiniPlayer.onSourceActivityResumed(a)
                                // A Window is not always attached at onResume,
                                // so transfer the existing surface after layout.
                                mainHandler.postDelayed({
                                    if (!a.isFinishing &&
                                        InAppMiniPlayer.attachToDetails(a)) {
                                        InAppMiniPlayer.resumeAfterTabSwitch()
                                    }
                                }, 140L)
                            } catch (e: Throwable) {
                                LogUtil.warn("应用内小窗：详情页恢复失败 " + e.message)
                            }
                        }
                        if (isShoppingMallDisabled() && a.javaClass.name == SHOP_ACTIVITY) {
                            a.finish()
                        }
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        if (a.javaClass.name == MAIN_TAB_ACTIVITY) mainHandler.post {
                            scanShoppingTabIfNeeded(a, force = true)
                        }
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed({
                    scanAllWindows()
                    if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                    if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                }, 400)
                startPeriodicScan()
                LogUtil.diagDump(true)
                result
            })
            LogUtil.info("  ✓ onResume")
            module.hook(c.getDeclaredMethod("onPause")).setId("op").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                val a = chain.thisObject as? Activity
                // Probe before native onPause can detach the video engine.
                try { SystemMediaBridge.onActivityPaused(a) } catch (e: Throwable) {
                    LogUtil.warn("系统媒体: Activity.onPause 诊断失败: " + e.message)
                }
                stopPeriodicScan()
                val result = chain.proceed()
                mainHandler.postDelayed({
                    try {

                        if (a != null && a.hasWindowFocus() && !a.isFinishing) refreshVideoPauseState("activity-onPause")
                    } catch (_: Throwable) {}
                }, 120L)
                result
            })
            LogUtil.info("  ✓ onPause")
            try {
                module.hook(c.getDeclaredMethod("onStop"))
                    .setId("xhSystemMediaActivityStop")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        try {
                            SystemMediaBridge.onActivityStopped(chain.thisObject as? Activity)
                        } catch (e: Throwable) {
                            LogUtil.warn("系统媒体: Activity.onStop 诊断失败: " + e.message)
                        }
                        chain.proceed()
                    })
                LogUtil.info("  ✓ system media Activity.onStop observer")
            } catch (e: Throwable) {
                LogUtil.warn("  system media Activity.onStop Hook failed: " + e.message)
            }
            try {
                module.hook(c.getDeclaredMethod("onUserLeaveHint"))
                    .setId("systemMediaUserLeave")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        try {
                            SystemMediaBridge.onUserLeave(chain.thisObject as? Activity)
                        } catch (e: Throwable) {
                            LogUtil.warn("系统媒体后台离开检测异常: " + e.message)
                        }
                        chain.proceed()
                    })
                LogUtil.info("  ✓ system MediaSession background lifecycle")
            } catch (e: Throwable) {
                LogUtil.warn("  system media leave Hook missing: " + e.message)
            }
            try {
                module.hook(c.getDeclaredMethod("onDestroy"))
                    .setId("inAppMiniActivityDestroy")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        (chain.thisObject as? Activity)?.let { activity ->
                            InAppMiniPlayer.onActivityClosed(activity)
                            SystemMediaBridge.onActivityDestroyed(activity)
                        }
                        chain.proceed()
                    })
            } catch (e: Throwable) {
                LogUtil.warn("应用内小窗生命周期清理 Hook 未命中: " + e.message)
            }
            // Reject only the dedicated shopping-mall Activity intent.
            // Shopping-related short-drama classes are deliberately not blocked.
            ham(c, "startActivity", "shoppingStartActivity") { chain ->
                try {
                    beforeNativeDramaDetailsLaunch(
                        chain.thisObject as? Activity, chain.getArg(0) as? Intent)
                } catch (e: Throwable) {
                    LogUtil.warn("应用内小窗：详情导航观察失败 " + e.message)
                }
                if (isShoppingMallDisabled() &&
                    isShoppingActivityIntent(chain.getArg(0) as? Intent)) {
                    LogUtil.incr("shoppingActivityIntentBlocked")
                    LogUtil.info("已拦截打开商城 Activity")
                    null
                } else chain.proceed()
            }
            ham(c, "startActivityForResult", "shoppingStartForResult") { chain ->
                try {
                    beforeNativeDramaDetailsLaunch(
                        chain.thisObject as? Activity, chain.getArg(0) as? Intent)
                } catch (e: Throwable) {
                    LogUtil.warn("应用内小窗：详情导航观察失败 " + e.message)
                }
                if (isShoppingMallDisabled() &&
                    isShoppingActivityIntent(chain.getArg(0) as? Intent)) {
                    LogUtil.incr("shoppingActivityIntentBlocked")
                    null
                } else chain.proceed()
            }
            LogUtil.info("  ✓ targeted shopping activity launch intercept")
        } catch (e: Exception) { LogUtil.error("lifecycle", e) }

        val settingsSpec = nativeSettingsSpec()
        var nativeSettingsListHookCount = 0
        for ((classIndex, settingsClassName) in listOf(
            "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity",
            "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity",
        ).withIndex()) {
            try {
                val settingsClass = Class.forName(settingsClassName, false, classLoader)
                settingsClass.declaredMethods
                    .filter { it.name in settingsSpec.listMethods && java.util.List::class.java.isAssignableFrom(it.returnType) }
                    .forEachIndexed { methodIndex, method ->
                        module.hook(method)
                            .setId("nativeSettingsList_${classIndex}_$methodIndex")
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(Hooker { chain ->
                                val result = chain.proceed()
                                injectNativeSettingsList(result, classLoader, settingsSpec)
                                result
                            })
                        nativeSettingsListHookCount++
                        LogUtil.info("  ✓ native settings list $settingsClassName.${method.name} -> ${settingsSpec.itemClass}")
                    }
            } catch (_: Throwable) {}
        }

        try {
            val clickClass = Class.forName(settingsSpec.clickClass, false, classLoader)
            clickClass.declaredMethods.filter { it.name == "onClick" }.forEachIndexed { index, method ->
                module.hook(method)
                    .setId("nativeSettingsClick_$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val host = chain.thisObject
                        if (host != null && boundSettingsTitle(host) == "模块设置") {
                            LogUtil.info("点击原生模块设置项 | ${settingsSpec.clickClass}")
                            mainHandler.post { showPanel(gSettingsActivity ?: gCurrentActivity) }
                            null
                        } else chain.proceed()
                    })
            }
            LogUtil.info("  ✓ native settings click ${settingsSpec.clickClass}")
        } catch (e: Throwable) {
            LogUtil.warn("  ${settingsSpec.clickClass} 设置点击 Hook 失败: $e")
        }

        settingsSpec.checkedClass?.let { checkedClassName ->
            try {
                val checkedClass = Class.forName(checkedClassName, false, classLoader)
                checkedClass.declaredMethods.filter { it.name == "onCheckedChanged" }.forEachIndexed { index, method ->
                    module.hook(method)
                        .setId("nativeSettingsChecked_$index")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val host = chain.thisObject
                            if (host != null && boundSettingsTitle(host) == "模块设置") {
                                val checked = try { chain.getArg(1) as? Boolean } catch (_: Throwable) { null }
                                if (checked != null) {
                                    gMasterOn = checked
                                    savePref("master_on", checked)
                                    if (!checked) {
                                        restoreAllSavedViews()
                                        restoreShortVideoNativeControls()
                                        setVideoToolbarsVisible(true)
                                    }
                                    val notificationCtx = appCtx ?: gCurrentActivity?.applicationContext
                                    if (notificationCtx != null) createNotification(notificationCtx)
                                    applyToCurrent()
                                    LogUtil.info("原生设置项同步模块总开关 -> $checked")
                                }
                            }
                            chain.proceed()
                        })
                }
                LogUtil.info("  ✓ native settings checked $checkedClassName")
            } catch (e: Throwable) {
                LogUtil.warn("  $checkedClassName 设置开关 Hook 失败: $e")
            }
        }

        if (nativeSettingsListHookCount == 0) {
            LogUtil.warn("  原生设置列表 Hook 未找到 | profile=${gNames.profileId} | methods=${settingsSpec.listMethods}")
        }

        var settingsHookCount = 0
        for ((index, settingsClassName) in listOf(
            "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity",
            "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity",
        ).withIndex()) {
            try {
                val c = Class.forName(settingsClassName, false, classLoader)
                module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java))
                    .setId("st$index")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val a = chain.thisObject as? Activity
                        gSettingsActivity = a
                        val r = chain.proceed()
                        if (a != null) scheduleSettingsEntryInjection(a)
                        r
                    })
                settingsHookCount++
                LogUtil.info("  ✓ settings $settingsClassName")
            } catch (_: Throwable) {}
        }
        if (settingsHookCount == 0) LogUtil.warn("  设置页 Activity 未找到")

        if (gNames.oledBright.isNotBlank() &&
            gNames.oledBrightAction.isNotBlank()) try {
            val hClass = Class.forName(gNames.oledBright, false, classLoader)
            val brightActionClass = Class.forName(gNames.oledBrightAction, false, classLoader)
            module.hook(hClass.getDeclaredMethod("a", java.util.List::class.java)).setId("oled").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                if (!gMasterOn || !gOledProtectOn) return@Hooker chain.proceed()
                try {
                    val list = chain.getArg(0) as? java.util.List<*>
                    if (list != null && list.isNotEmpty()) {
                        val filtered = list.filter { it == null || !brightActionClass.isInstance(it) }
                        if (filtered.size < list.size) {
                            LogUtil.incr("oledBrightBlock")
                            val na = (chain.args as Array<Any?>).copyOf()
                            na[0] = java.util.ArrayList(filtered)
                            return@Hooker chain.proceed(na)
                        }
                    }
                } catch (_: Exception) {}
                chain.proceed()
            })
            LogUtil.info("  ✓ OLED 亮度拦截（默认关闭）")
        } catch (e: Exception) { LogUtil.warn("  ${gNames.oledBright} OLED 未找到: $e") }

        if (gNames.pauseAdEntryClass.isNotBlank() &&
            gNames.pauseAdEntryMethod.isNotBlank()) try {
            val c = Class.forName(gNames.pauseAdEntryClass, false, classLoader)
            ham(c, gNames.pauseAdEntryMethod, "pauseAdEntry") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 暂停广告入口 ${gNames.pauseAdEntryClass}.${gNames.pauseAdEntryMethod}")
        } catch (e: Exception) {
            LogUtil.warn("  暂停广告入口 ${gNames.pauseAdEntryClass}.${gNames.pauseAdEntryMethod} 未找到: $e")
        }

        try { val c = Class.forName("com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", false, classLoader)
            ham(c, "canShowPauseAd", "spCan") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "enablePauseAd", "spEnable") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "requestAd", "spReq") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "onPauseAdShow", "spShow") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "enableCoinBox", "spCoin") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ SeriesPauseAdImpl 拦截") } catch (e: Exception) { LogUtil.warn("  SeriesPauseAdImpl 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer", false, classLoader)
            ham(c, "handleVideoEvent", "veAd") { chain -> if (gMasterOn && gAdOn) { try { val ev = chain.getArg(0); val m = ev?.javaClass?.getMethod("getType"); if (m != null && (m.invoke(ev) as? Int) == 102) return@ham false } catch (_: Exception) {} }; chain.proceed() }
            ham(c, gNames.adVideoEndShowMethod, "veAdI") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 片尾广告层") } catch (e: Exception) { LogUtil.warn("  AdVideoEndLayer 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.pages.video.layers.adiconlayer.AdIconLayer", false, classLoader)
            ham(c, "handleVideoEvent", "aiAd") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ 广告图标层") } catch (e: Exception) { LogUtil.warn("  AdIconLayer 未找到: $e") }

        try { val c = Class.forName("com.dragon.read.component.biz.impl.privilege.PrivilegeManager", false, classLoader)
            ham(c, "isVip", "vipIsVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "isAnyVip", "vipAny") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "canReadShortStory", "vipRead") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasVipShortSeriesPrivilege", "vipSeries") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "b", "vipSubType") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdFollAllScene", "vipNoAd") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdForShortSeries", "vipNoAdS") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }

            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                val fakeModel = { vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) }
                c.declaredMethods.filter { it.name == "getVipInfo" }.forEachIndexed { i, m -> try { module.hook(m).setId("vipGetInfo_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) fakeModel() else chain.proceed() }) } catch (_: Exception) {} }
                module.hook(c.getDeclaredMethod("getAllVipInfo")).setId("vipGetAll").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) java.util.Collections.singletonList(fakeModel()) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfo 模型")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfo 模型 hook 失败: $e2") }
            LogUtil.info("  ✓ VIP 解锁 (PrivilegeManager)") } catch (e: Exception) { LogUtil.warn("  PrivilegeManager 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsUserInfoDependImpl", false, classLoader)
            ham(c, "isVip", "nsVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                module.hook(c.getDeclaredMethod("getVipInfoModel")).setId("vipGetModel").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gVipOn) vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfoModel")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfoModel hook 失败: $e2") }
            LogUtil.info("  ✓ NsUserInfoDependImpl.isVip") } catch (e: Exception) { LogUtil.warn("  NsUserInfoDependImpl 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsComicAdDependImpl", false, classLoader)
            ham(c, "isVipUser", "comicVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsComicAdDependImpl.isVipUser") } catch (e: Exception) { LogUtil.warn("  NsComicAdDependImpl 未找到: $e") }

        if (gNames.kmpVipModel.isNotBlank() &&
            gNames.kmpAcctService.isNotEmpty()) try {
            val eCls = Class.forName(gNames.kmpVipModel, false, classLoader)
            val eCtor = eCls.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, java.lang.Boolean::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java)
            eCtor.isAccessible = true
            var kmpHooked = 0
            for (svc in gNames.kmpAcctService) {
                try {
                    val c = Class.forName(svc, false, classLoader)
                    module.hook(c.getDeclaredMethod("getVipInfo")).setId("kmpVipInfo_$svc").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                        if (gMasterOn && gVipOn) { try { eCtor.newInstance("1", "2099-12-31 23:59:59", "99999999", true, true, 0, true, 0, true, 0, true) } catch (_: Exception) { chain.proceed() } } else chain.proceed()
                    })
                    kmpHooked++
                } catch (_: Exception) {}
            }
            LogUtil.info("  ✓ KMP getVipInfo hooked=$kmpHooked ($gNames.kmpVipModel)") } catch (e: Exception) { LogUtil.warn("  KMP vip 模型 ${gNames.kmpVipModel} 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.biz.impl.NsVipImpl", false, classLoader)
            ham(c, "isVip", "nvVip") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "isSpecificVipOrHigher", "nvSpec") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            ham(c, "canShowVipCenter", "nvCenter") { chain -> if (gMasterOn && gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsVipImpl") } catch (e: Exception) { LogUtil.warn("  NsVipImpl 未找到: $e") }

        LogUtil.info("installBusinessHooks done"); LogUtil.diagDump(true)
    }

    fun installDemoHooks(module: MainHook, classLoader: ClassLoader) {
        LogUtil.info("demo")
        try { val c = Class.forName("xyz.kejiyu.hongguo.MainActivity", false, classLoader); module.hook(c.getMethod("onCreate", android.os.Bundle::class.java)).setPriority(XposedInterface.PRIORITY_DEFAULT).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("dm").intercept(Hooker { chain -> chain.proceed() }); LogUtil.info("demo ok") } catch (e: Exception) { LogUtil.error("demo", e) }
    }
}
