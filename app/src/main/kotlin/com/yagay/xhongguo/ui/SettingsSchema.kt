package com.yagay.xhongguo.ui

import com.yagay.xhongguo.SettingsCatalog
import com.yagay.xhongguo.ModuleSettingsStore

/** UI-only schema. Existing configuration keys remain authoritative. */
internal object SettingsSchema {
    enum class Kind { SWITCH, MODE, SPEED, DELAY, LIMITS, THEME, DENSITY, COLOR }
    data class Item(val key: String, val title: String, val summary: String, val group: String,
        val kind: Kind = Kind.SWITCH, val aliases: String = "", val note: String = "") {
        val page get() = SettingsCatalog.page(key)
        val parents get() = SettingsCatalog.definition(key).parentKeys
    }
    val items = listOf(
        Item("master_on", "模块总开关", "控制模块功能", "常用"),
        Item("system_media_playback", "系统媒体后台播放", "通过系统媒体卡片控制原播放器", "后台播放", note = "实验功能 · 原生生命周期可能限制播放"),
        Item("app_playback_mode", "应用内播放模式", "持续播放或小窗", "应用内播放", Kind.MODE, "小窗 后台 保活"),
        Item("audio_external_pause", "其他应用播放时暂停", "检测实际媒体音频", "其他应用音频", note = "需要系统框架作用域；首次启用后重启手机"),
        Item("audio_external_auto_resume", "停止后自动恢复", "仅恢复被中断的原视频", "其他应用音频"),
        Item("audio_external_resume_delay_ms", "恢复延迟", "其他应用停止后等待", "其他应用音频", Kind.DELAY),
        Item("audio_call_pause", "通话时暂停", "检测铃声与通话", "通话"),
        Item("audio_call_auto_resume", "通话结束自动恢复", "同一视频才恢复", "通话"),
        Item("audio_focus_pause", "丢失焦点时暂停", "其他应用抢占音频焦点", "音频焦点"),
        Item("audio_focus_auto_resume", "恢复焦点后播放", "仅临时焦点中断", "音频焦点"),
        Item("audio_focus_pause_duck", "降低音量请求时暂停", "普通提示音默认不暂停", "音频焦点"),
        Item("max_quality", "自动最高画质", "选择当前视频支持的最高画质", "播放"),
        Item("default_speed", "默认播放倍速", "为短剧设置默认倍速", "播放"),
        Item("default_speed_value", "播放倍速", "选择具体倍速", "播放", Kind.SPEED),
        Item("double_tap_comment", "双击打开评论", "替换双击点赞", "手势", note = "7.4.2.32 评论分发待适配"),
        Item("pull_refresh", "禁用下拉刷新", "关闭刷新手势与提示", "手势"),
        Item("top_zone", "拦截顶部下滑", "拦截顶部区域下滑手势", "手势"),
        Item("status_bar", "隐藏状态栏", "沉浸显示", "沉浸显示"),
        Item("nav_bar_off", "隐藏底部小白条", "仅手势导航有效", "沉浸显示"),
        Item("control_hide", "隐藏播放控件", "三键导航保留底栏及全集入口", "播放控件"),
        Item("player_bar", "隐藏选集相关控件", "精简联播页面", "播放控件"),
        Item("progress_off", "隐藏视频进度条", "隐藏已适配进度条", "播放控件"),
        Item("restore_controls_pause", "暂停后恢复控件", "播放恢复时按规则隐藏", "播放控件"),
        Item("disable_shopping_mall", "禁用商城", "隐藏商城入口，保留剧场", "首页"),
        Item("hide_mine_shortcuts", "隐藏我的快捷入口", "福利、订单、消息；保留账号和历史", "我的"),
        Item("hide_all_ads", "隐藏广告与挂件", "已适配的广告层和悬浮挂件", "内容"),
        Item("vip_unlock", "VIP 状态 Hook", "原有兼容设置", "历史兼容", note = "最新 APK 行为未验证"),
        Item("vip_icon", "VIP 图标", "控制相关图标和挂件", "历史兼容"),
        Item("ui_theme", "主题", "浅色、深色、纯黑或跟随系统", "外观", Kind.THEME),
        Item("ui_density", "界面密度", "标准或紧凑", "外观", Kind.DENSITY),
        Item("ui_color", "主题颜色", "品牌红色或系统取色", "外观", Kind.COLOR),
        Item("notification_menu", "通知栏快捷菜单", "需要红果通知权限", "快捷入口"),
        Item("video_longpress_settings", "视频长按菜单入口", "在原生菜单中添加模块设置", "快捷入口"),
        Item("oled_protect", "OLED 亮度保护", "实验性亮度拦截", "实验", note = "最新 APK 待适配"),
        Item("official_playback_probe", "官方播放接口诊断", "仅记录脱敏接口信息；独立于总开关", "诊断", note = "建议仅在排查时开启"),
        Item("download_limit_unlimit", "自定义下载数量限制", "原有下载设置", "下载", note = "修改后需要重新启动红果"),
        Item("download_limit_values", "下载限制数值", "单日集数、剧数及缓存剧数", "下载", Kind.LIMITS)
    )
    val speedOptions = floatArrayOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
    val limitKeys = listOf("download_limit_one_day_episode", "download_limit_one_day_series", "download_limit_total_series")
    fun defaults(item: Item): Map<String, Any> = when (item.kind) {
        Kind.SWITCH -> mapOf(item.key to SettingsCatalog.definition(item.key).defaultEnabled)
        Kind.MODE -> mapOf("in_app_continuous_playback" to false, "in_app_mini_player" to false)
        Kind.SPEED -> mapOf(item.key to 1f)
        Kind.DELAY -> mapOf(item.key to 1000)
        Kind.LIMITS -> limitKeys.associateWith { 99999 }
        else -> mapOf(item.key to 0)
    }
    fun search(query: String): List<Item> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return items.filter { item ->
            val all = "${item.title} ${item.summary} ${item.page.title} ${item.group} ${item.aliases} ${item.key}"
            tokens.all { all.contains(it, ignoreCase = true) }
        }
    }
}
