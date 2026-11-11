package xyz.kejiyu.hongguo

import android.content.Context

/** Evidence-based report; no positive result solely from a saved preference. */
object FeatureDiagnostics {
    private data class Rule(
        val key: String,
        val title: String,
        val installed: List<String>,
        val triggered: List<String>,
        val problems: List<String>
    )

    private val rules = listOf(
        Rule("system_media_playback", "系统媒体后台播放",
            listOf("Android MediaSession 已初始化", "video command detector"),
            listOf("MediaStyle 通知已发布", "系统媒体: 原播放器控制成功"),
            listOf("系统媒体: 通知发布失败", "系统媒体: 创建 MediaSession 失败",
                "系统媒体: 没有可用的原播放器", "没有可用的原播放器")),
        Rule("in_app_mini_player", "应用内小窗",
            listOf("dispatchTouch + native long-press", "应用内小窗 Tab:"),
            listOf("应用内小窗：已移动原生播放器",
                "应用内小窗：阻止页面切换引发的 TTVideoEngine.pause",
                "应用内小窗：原生引擎确认暂停，尝试恢复当前播放",
                "应用内小窗：用户暂停", "应用内小窗：用户继续播放",
                "应用内小窗：用户关闭", "inAppMiniEdgeSnap"),
            listOf("应用内小窗未启动", "应用内小窗跳过",
                "应用内小窗：移动播放器失败")),
        Rule("video_longpress_settings", "长按菜单入口（首页/全集）",
            listOf("video long-press Dialog observer", "fullscreen touch"),
            listOf("视频长按菜单：已添加", "全集视频长按菜单："),
            listOf("视频长按菜单入口注入失败", "全集视频弹窗入口失败")),
        Rule("disable_shopping_mall", "隐藏商城",
            listOf("shopping tab", "shopping activity launch intercept"),
            listOf("首页仅隐藏商城 Tab", "已拦截打开商城"),
            listOf("商城 Tab 隐藏失败")),
        Rule("max_quality", "自动最高画质",
            listOf("默认最高画质", "通用最高画质 SDK setVideoModel"),
            listOf("maxResolutionForce", "maxQualityApply", "最高画质：检测到"),
            listOf("默认最高画质 Hook 失败")),
        Rule("default_speed", "默认倍速",
            listOf("默认倍速 Hook", "默认倍速 |", "通用默认倍速 PlaybackParams"),
            listOf("defaultSpeedImmediate", "defaultSpeedFloatForce", "通用默认倍速:"),
            listOf("默认倍速 Hook 失败")),
        Rule("double_tap_comment", "双击评论",
            listOf("rightViewAgency"), listOf("双击评论事件已发送"),
            listOf("双击打开评论区失败")),
        Rule("control_hide", "隐藏界面控件",
            listOf("shortNativeClean"), listOf("toolbarBlock", "底部原生栏已折叠"),
            listOf("force short-video mask invisible failed")),
        Rule("player_bar", "选集控件",
            listOf("series toolbar tracker"), listOf("toolbarBlock"),
            listOf("series toolbar failed")),
        Rule("progress_off", "进度条清理",
            listOf("progress bar"), listOf("progressHide"),
            listOf("progress error")),
        Rule("ad_block", "广告挂件拦截",
            listOf("pendant"), listOf("adBlock"),
            listOf("ad hook failed")),
        Rule("nav_bar_off", "隐藏系统手势栏",
            listOf("系统导航="), listOf("navBar hidden"),
            listOf("navigation bar failed")),
        Rule("restore_controls_pause", "暂停恢复控件",
            listOf("playback detector"), listOf("video paused: restore controls"),
            listOf("show short-video controls failed"))
    )

    fun analyze(ctx: Context, evidence: String, rootGranted: Boolean): String = buildString {
        val pref = ModuleSettingsStore.prefs(ctx)
        val master = pref.getBoolean("master_on", false)
        appendLine("Xhongguo 诊断报告 | Hook + Root")
        appendLine("================================")
        appendLine("ROOT=" + rootGranted)
        appendLine("模块总开关=" + master)
        appendLine("可用日志行数=" + evidence.lineSequence().count())
        appendLine("发现模块初始化日志=" + evidence.contains("模块加载"))
        appendLine("发现业务 Hook 安装完成日志=" + evidence.contains("业务 Hook 安装完成"))
        val mode = evidence.lineSequence().lastOrNull {
            it.contains("目标运行模式=")
        } ?: "未记录目标运行模式"
        appendLine("版本映射：" + mode.trim().take(300))
        appendLine("旧版本回退：" +
            if (mode.contains("AUTO-")) "已禁止；仅通用模式" else "请核对是否使用精确旧版")
        appendLine("注意：开关已开≠Hook成功；Hook已安装≠功能运行成功。")
        appendLine()
        appendLine("通用运行时能力清单")
        for (signal in listOf(
            "Android framework UI/lifecycle",
            "VideoShop playback and background",
            "VideoShop play entity",
            "VideoShop event controller",
            "TTVideoEngine timeline",
            "TTVideoEngine video identity",
            "Native playback resolution",
            "Native speed control",
            "Full-series current episode",
            "Official series title/poster",
            "Settings screen",
            "Download limit config"
        )) {
            val entry = evidence.lineSequence().lastOrNull {
                it.contains("通用能力: $signal=")
            }
            appendLine("  " + (entry?.substringAfter("通用能力: ")?.take(190)
                ?: "$signal=未看到探测记录"))
        }
        appendLine()
        for (rule in rules) {
            val switched = master && pref.getBoolean(rule.key,
                rule.key == "video_longpress_settings" || rule.key == "disable_shopping_mall")
            val hooks = rule.installed.filter { evidence.contains(it, true) }
            val events = rule.triggered.filter { evidence.contains(it, true) }
            val errors = rule.problems.filter { evidence.contains(it, true) }
            val verdict = when {
                !switched -> "未启用"
                errors.isNotEmpty() -> "观察到异常"
                events.isNotEmpty() -> "有运行事件（效果仍需实测）"
                hooks.isNotEmpty() -> "仅观察到安装/检测事件"
                else -> "未观察到，检查作用域或复现步骤"
            }
            appendLine(rule.title + "： " + verdict)
            if (hooks.isNotEmpty()) appendLine("  安装/探测：" + hooks.joinToString())
            if (events.isNotEmpty()) appendLine("  触发事件：" + events.joinToString())
            if (errors.isNotEmpty()) appendLine("  失败线索：" + errors.joinToString())
        }
        appendLine()
        appendLine("系统媒体重点排查")
        for (signal in listOf(
            "Android MediaSession 已初始化",
            "MediaStyle 通知已发布",
            "系统媒体: 离开红果",
            "系统媒体: 检测到切后台暂停",
            "系统媒体: 忽略后台切换引发",
            "系统媒体: 原播放器控制成功",
            "系统媒体: 没有可用的原播放器",
            "系统媒体: 通知发布失败",
            "系统媒体: 全集 Activity 已销毁",
            "系统媒体: 原生播放器生命周期暂停",
            "系统媒体: 后台检测晚于原生 pause",
            "系统媒体: 统一媒体信息采集",
            "系统媒体: 统一媒体元数据已绑定",
            "system media stable series DTO hooks",
            "system media series JSON result observers",
            "system media typed series consumers",
            "系统媒体: 原生视频数据已观察",
            "系统媒体: 首页当前播放器模型",
            "系统媒体: 首页已绑定当前原生视频",
            "系统媒体: 已捕获官方全集详情",
            "系统媒体: 播放进度采样",
            "系统媒体: 全集原生元数据解析",
            "系统媒体: 全集内层视频索引",
            "系统媒体: 已发现原生系列海报",
            "系统媒体: 媒体封面已写入 MediaSession",
            "系统媒体: 封面加载失败"
        )) {
            appendLine((if (evidence.contains(signal)) "找到" else "未找到") + " - " + signal)
        }
        appendLine()
        appendLine("建议复现：播放首页视频→打开全集→长按→切后台→")
        appendLine("尝试通知栏播放/暂停→切回→立刻导出 ZIP。")
        appendLine("未观测到不代表失败：可能没有复现、日志被轮换或 Root 未授权。")
        appendLine("日志可能包含个人数据，请审查后分享。")
    }
}
