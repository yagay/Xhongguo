package com.yagay.xhongguo

/** Shared settings metadata, dependency and search definitions. */
internal object SettingsCatalog {
    enum class Page(val title: String, val summary: String) {
        HOME("Xhongguo 设置", ""),
        BACKGROUND("后台与小窗", "应用内持续播放、小窗"),
        AUDIO("音频中断与恢复", "其他应用音频、通话、音频焦点"),
        PLAYER("播放器与手势", "画质、倍速、评论、刷新手势"),
        DISPLAY("界面精简", "状态栏、进度条、选集、导航栏"),
        CONTENT("内容与账号", "商城、我的页面、广告、账号"),
        TOOLS("工具与诊断", "通知快捷菜单、下载限制、日志")
    }

    val categories = listOf(Page.BACKGROUND, Page.AUDIO, Page.PLAYER,
        Page.DISPLAY, Page.CONTENT, Page.TOOLS)

    fun page(key: String): Page = when (key) {
        "master_on", "system_media_playback" -> Page.HOME
        "in_app_continuous_playback", "in_app_mini_player" -> Page.BACKGROUND
        "audio_focus_pause", "audio_focus_auto_resume", "audio_focus_pause_duck",
        "audio_call_pause", "audio_call_auto_resume", "audio_external_pause",
        "audio_external_auto_resume", "audio_external_resume_delay_ms" -> Page.AUDIO
        "max_quality", "default_speed", "default_speed_value", "double_tap_comment",
        "pull_refresh", "top_zone" -> Page.PLAYER
        "status_bar", "control_hide", "player_bar", "progress_off", "nav_bar_off",
        "restore_controls_pause", "video_longpress_settings" -> Page.DISPLAY
        "disable_shopping_mall", "hide_mine_shortcuts", "hide_all_ads", "ad_block",
        "vip_unlock", "vip_icon" -> Page.CONTENT
        else -> Page.TOOLS
    }

    data class Definition(val key: String, val page: Page, val order: Int,
        val defaultEnabled: Boolean, val parentKeys: List<String>, val restartRequired: Boolean)

    private val defaultOn = setOf("video_longpress_settings", "disable_shopping_mall",
        "hide_mine_shortcuts", "hide_all_ads", "audio_focus_pause", "audio_call_pause",
        "audio_call_auto_resume", "audio_external_pause", "audio_external_auto_resume")
    fun definition(key: String): Definition {
        val parents = when (key) {
            "audio_external_auto_resume" -> listOf("audio_external_pause")
            "audio_external_resume_delay_ms" -> listOf("audio_external_pause", "audio_external_auto_resume")
            "audio_focus_auto_resume", "audio_focus_pause_duck" -> listOf("audio_focus_pause")
            "audio_call_auto_resume" -> listOf("audio_call_pause")
            "default_speed_value" -> listOf("default_speed")
            "download_limit_values" -> listOf("download_limit_unlimit")
            else -> emptyList()
        }
        return Definition(key, page(key), rank(key), key in defaultOn, parents,
            key in setOf("audio_external_pause", "download_limit_unlimit", "official_playback_probe"))
    }

    fun otherMode(key: String): String? = when (key) {
        "in_app_continuous_playback" -> "in_app_mini_player"
        "in_app_mini_player" -> "in_app_continuous_playback"
        else -> null
    }

    // Consistent grouping order even when a host does not expose every item.
    fun rank(key: String): Int = when (key) {
        "audio_external_pause" -> 10
        "audio_external_auto_resume" -> 11
        "audio_external_resume_delay_ms" -> 12
        "audio_focus_pause" -> 20
        "audio_focus_auto_resume" -> 21
        "audio_focus_pause_duck" -> 22
        "audio_call_pause" -> 30
        "audio_call_auto_resume" -> 31
        "in_app_continuous_playback" -> 10
        "in_app_mini_player" -> 20
        "max_quality" -> 10
        "default_speed" -> 20
        "default_speed_value" -> 21
        "double_tap_comment" -> 30
        "pull_refresh" -> 40
        "top_zone" -> 41
        else -> 100
    }
}
