package xyz.kejiyu.hongguo

/**
 * The SINGLE setting taxonomy shared by the module app and the in-Hongguo
 * floating settings sheet. No version/obfuscation mappings or second store.
 *
 * Root displays only the module switch and categories. Individual options
 * appear after opening a category, with the same key/default in both UIs.
 */
object SettingsCatalog {
    enum class Kind { SWITCH, SPEED, DOWNLOAD_VALUES, DIAGNOSTICS }
    data class Item(
        val title: String,
        val detail: String,
        val key: String,
        val defaultValue: Boolean = false,
        val kind: Kind = Kind.SWITCH,
        val subsection: String = ""
    )
    data class Group(
        val id: String,
        val icon: String,
        val title: String,
        val detail: String,
        val items: List<Item>
    )

    val master = Item("启用 Xhongguo", "关闭时恢复红果原有界面和功能",
        "master_on")

    val groups: List<Group> = listOf(
        Group("playback", "▶", "播放与小窗", "后台媒体、画中小窗、画质和倍速",
            listOf(
                Item("系统媒体后台播放", "使用红果原播放器提供通知栏媒体控制",
                    "system_media_playback"),
                Item("应用内小窗", "切换页面时继续播放，支持拖动和返回",
                    "in_app_mini_player"),
                Item("自动最高画质", "默认选择当前视频支持的最高画质",
                    "max_quality"),
                Item("默认倍速", "为播放器启用自定义默认速度",
                    "default_speed"),
                Item("倍速数值", "选择 0.75×～3×",
                    "default_speed_value", kind = Kind.SPEED),
                Item("双击打开评论", "用评论替代双击点赞",
                    "double_tap_comment")
            )),
        Group("interface", "▦", "界面与导航", "控件、进度条、手势、商城入口",
            listOf(
                Item("隐藏状态栏", "视频页采用沉浸式布局", "status_bar"),
                Item("隐藏视频控件", "保留必要的底部导航和全集入口",
                    "control_hide"),
                Item("隐藏选集控件", "精简全集播放页的选集工具栏",
                    "player_bar"),
                Item("隐藏视频进度条", "隐藏已适配的进度条",
                    "progress_off"),
                Item("隐藏系统手势条", "只在手势导航下生效",
                    "nav_bar_off"),
                Item("暂停时恢复控件", "暂停后暂时显示已隐藏的控件",
                    "restore_controls_pause"),
                Item("禁用商城", "移除商城 Tab 并拦截商城页面",
                    "disable_shopping_mall", true),
                Item("禁用下拉刷新", "防止播放器被刷新手势打断",
                    "pull_refresh"),
                Item("拦截顶部下滑", "拦截视频顶部区域的下滑手势",
                    "top_zone"),
                Item("视频长按设置入口", "在原生长按菜单里打开应用内设置弹窗",
                    "video_longpress_settings", true)
            )),
        Group("mine", "◉", "我的页面", "分别管理快捷入口与右侧菜单",
            listOf(
                Item("隐藏福利", "隐藏入口并禁用点击",
                    "mine_hide_welfare", true, subsection = "快捷入口"),
                Item("隐藏订单", "隐藏入口并禁用点击",
                    "mine_hide_orders", true),
                Item("隐藏消息", "隐藏入口并禁用点击",
                    "mine_hide_messages", true),
                Item("隐藏游戏", "隐藏入口并禁用点击",
                    "mine_hide_games", true),
                Item("隐藏追更", "隐藏入口并禁用点击",
                    "mine_hide_following", true),
                Item("隐藏我的钱包", "右上角三横线菜单",
                    "mine_hide_wallet", true, subsection = "右侧菜单"),
                Item("隐藏会员中心", "右上角三横线菜单",
                    "mine_hide_membership", true),
                Item("隐藏游戏中心", "右上角三横线菜单",
                    "mine_hide_game_center", true),
                Item("隐藏创作中心", "右上角三横线菜单",
                    "mine_hide_creator_center", true)
            )),
        Group("content", "✧", "内容与会员", "广告拦截、VIP 状态和图标",
            listOf(
                Item("拦截广告与挂件", "减少广告层与悬浮挂件",
                    "ad_block"),
                Item("解锁 VIP", "启用已适配的 VIP 状态 Hook",
                    "vip_unlock"),
                Item("显示 VIP 图标", "控制 VIP 图标与挂件",
                    "vip_icon")
            )),
        Group("advanced", "⚙", "工具与高级", "下载限制、OLED 保护和通知",
            listOf(
                Item("自定义下载限制", "开启下载数量限制覆盖功能",
                    "download_limit_unlimit"),
                Item("下载限制数值", "设置单日集数、剧数及缓存上限",
                    "download_limit_values", kind = Kind.DOWNLOAD_VALUES),
                Item("OLED 亮度保护", "实验性亮度拦截",
                    "oled_protect"),
                Item("通知栏快捷菜单", "启用模块开关和面板快捷操作",
                    "notification_menu")
            )),
        Group("diagnostics", "▤", "诊断与日志", "导出诊断 ZIP 用于排查问题",
            listOf(
                Item("导出诊断日志 ZIP", "包含 Hook、Root 与进程运行信息",
                    "export_diagnostics", kind = Kind.DIAGNOSTICS)
            ))
    )

    val allItems: List<Item> = listOf(master) +
        groups.flatMap { it.items }

    fun switchItem(key: String): Item? = allItems.firstOrNull {
        it.key == key && it.kind == Kind.SWITCH
    }
}
