package xyz.kejiyu.hongguo

/**
 * Stable diagnostic taxonomy. These are feature categories, not UI names or
 * raw native class names. No user account, URL, video ID or title is stored.
 */
object FeatureTraceCatalog {
    val categories = listOf(
        "playback", "media", "artwork", "background", "mini",
        "ui", "navigation", "mine", "quality", "speed", "ads",
        "downloads", "menu", "settings", "hook", "diagnostics"
    )

    fun forSetting(key: String): String = when {
        key == "diagnostic_verbose" -> "diagnostics"
        key == "master_on" -> "settings"
        key.startsWith("mine_hide_") -> "mine"
        key in setOf("system_media_playback") -> "media"
        key in setOf("in_app_mini_player") -> "mini"
        key in setOf("max_quality") -> "quality"
        key.startsWith("default_speed") -> "speed"
        key in setOf("ad_block", "vip_unlock", "vip_icon") -> "ads"
        key.startsWith("download_limit") -> "downloads"
        key in setOf("video_longpress_settings", "notification_menu") -> "menu"
        key in setOf("disable_shopping_mall") -> "navigation"
        key in setOf("status_bar", "control_hide", "player_bar", "progress_off",
            "restore_controls_pause", "nav_bar_off", "pull_refresh",
            "top_zone", "oled_protect") -> "ui"
        key == "double_tap_comment" -> "menu"
        else -> "settings"
    }

    /** Return null for generic hot-path scan/inflate counters. */
    fun forCounter(name: String): String? = when {
        name.startsWith("systemMediaArtwork") -> "artwork"
        name.startsWith("systemMediaBackground") ||
            name.startsWith("systemMediaAppSwitch") ||
            name.startsWith("systemMediaHostLifecycle") ||
            name.startsWith("systemMediaEarly") ||
            name.startsWith("systemMediaEpisodeEndStall") ||
            name.startsWith("systemMediaNativeCompletionHook") ||
            name.startsWith("systemMediaFeedPause") ||
            name.startsWith("systemMediaLifecyclePause") ||
            name.startsWith("systemMediaPrePause") -> "background"
        name.startsWith("systemMedia") -> "media"
        name.startsWith("singlePlayback") -> "playback"
        name.startsWith("inAppMini") -> "mini"
        name.startsWith("mine") -> "mine"
        name.startsWith("defaultSpeed") -> "speed"
        name.startsWith("maxQuality") -> "quality"
        name.startsWith("downloadLimit") -> "downloads"
        name.startsWith("shopping") -> "navigation"
        name.startsWith("videoLongPress") ||
            name.startsWith("videoFullSeriesMenu") ||
            name.startsWith("moduleSettingsInHost") ||
            name.startsWith("doubleTap") -> "menu"
        name.startsWith("hook") -> "hook"
        name.startsWith("uiCompat") ||
            name.startsWith("progressHide") ||
            name.startsWith("refreshAccessory") ||
            name.startsWith("nativeBottom") ||
            name.startsWith("nativeVideoFeed") ||
            name.startsWith("feedViewport") ||
            name.startsWith("homeFragmentMask") ||
            name.startsWith("shortMask") ||
            name.startsWith("shortNative") ||
            name.startsWith("collapseControl") ||
            name.startsWith("toolbarBlock") ||
            name.startsWith("bottom") ||
            name.startsWith("oledBright") ||
            name.startsWith("topBlock") ||
            name.startsWith("btmBlock") -> "ui"
        name.startsWith("adBlock") -> "ads"
        else -> null
    }

    /** Optional native hook identifiers whose execution is diagnostically useful.
     * Do not trace framework inflate/setVisibility hot paths per call. */
    fun forHookId(id: String): String? = when {
        id.startsWith("vip") || id.startsWith("nsVip") ||
            id.startsWith("nvVip") || id.startsWith("nvSpec") ||
            id.startsWith("nvCenter") || id.startsWith("comicVip") -> "ads"
        id.startsWith("sp") && id.length > 2 ||
            id.startsWith("veAd") || id.startsWith("aiAd") -> "ads"
        id.startsWith("downloadLimit") -> "downloads"
        id.startsWith("maxQuality") -> "quality"
        id.startsWith("shopping") -> "navigation"
        id.startsWith("videoCmd") || id.startsWith("videoPause") ||
            id.startsWith("videoPlay") -> "playback"
        else -> null
    }

    fun outcomeForCounter(name: String): String = when {
        name.contains("Failed", true) ||
            name.contains("Failure", true) ||
            name.contains("Unavailable", true) ||
            name.contains("Error", true) ||
            name.contains("Missing", true) -> "fail"
        name.contains("NoRenderer", true) ||
            name.contains("Skip", true) ||
            name.contains("CircuitBreakerOpened", true) -> "degraded"
        else -> "observed"
    }
}
