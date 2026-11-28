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
            listOf("应用内小窗：已移动原生播放器"),
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
            val switched = master && pref.getBoolean(rule.key, ModuleSettingsStore.defaultBoolean(rule.key))
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
        appendLine("音频中断重点排查")
        for (key in listOf("audio_focus_pause", "audio_call_pause", "audio_external_pause",
            "audio_focus_auto_resume", "audio_call_auto_resume", "audio_focus_pause_duck", "audio_external_auto_resume")) {
            appendLine(key + "=" + pref.getBoolean(key, ModuleSettingsStore.defaultBoolean(key)) +
                " origin=" + if (pref.contains(key)) "saved" else "default")
        }
        appendLine("audio_external_resume_delay_ms=" + ModuleSettingsStore.externalResumeDelay(pref))
        for (signal in listOf("音频协调: 已记录外部音频恢复会话",
            "音频协调: 外部音频停止，等待恢复", "音频协调: 外部音频恢复播放已确认",
            "音频协调: 外部音频恢复焦点未获批准", "音频协调: 取消外部音频恢复",
            "音频协调: 实际配置", "音频协调: 焦点回调",
            "音频协调: 系统音频状态同步", "音频协调: 未收到系统音频状态",
            "Read-only system audio activity listener registered", "音频协调: 中断",
            "音频协调: 暂停状态已确认", "音频协调: 已阻止后台自动开始播放")) {
            val matches = evidence.lineSequence().filter { it.contains(signal) }.toList()
            appendLine("  " + signal + " count=" + matches.size)
            matches.lastOrNull()?.let { appendLine("    " + it.take(350)) }
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
        appendLine("官方账号与视频取址诊断（7.4.2.32 及兼容网络层）")
        appendLine("--------------------------------")
        val enabledProbe = pref.getBoolean("official_playback_probe", false)
        appendLine("官方播放探针开关=" + enabledProbe)
        val probeLines = evidence.lineSequence().filter { it.contains("HGPROBE ") }.toList()
        val installedProbe = probeLines.lastOrNull { it.contains("HGPROBE INSTALLED") }
        appendLine("探针安装=" + (installedProbe?.trim()?.take(400) ?: "未发现，检查作用域与重启目标 App"))
        val activatedProbe = probeLines.lastOrNull { it.contains("HGPROBE ACTIVE mode=") }
        appendLine("探针激活=" + (activatedProbe?.trim()?.take(160)
            ?: "未检测到 ACTIVE，可能是在 App 上下文初始化前安装，或没有读取到目标进程的设置"))
        val events = probeLines.filter { it.contains("HGPROBE EVENT") }
        appendLine("捕获 Hook 事件=" + events.size + "（含各层重复记录）")
        val transportEvents = events.filter { it.contains("layer=network ") }
        appendLine("实际网络接口调用=" + transportEvents.size)
        val traces = probeLines.filter { it.contains("HGPROBE TRACE") }
        appendLine("Hook 执行采样=" + traces.size)
        if (events.isEmpty()) {
            val trace = traces.lastOrNull()?.trim()?.take(270)
            appendLine("零事件排查=" + when {
                activatedProbe == null -> "未激活探针，核对设置同步/Activity 初始化"
                traces.isEmpty() -> "已激活但无 Hook 调用采样，可能走了其他网络链路"
                else -> "Hook 已调用但请求路径未命中，最近采样：" + trace
            })
        }
        for (group in listOf("v5", "video_tab", "lite", "legacy", "passport", "account", "device")) {
            // Count each transport request once. Retrofit and decode observations
            // describe the same request, not extra network calls.
            val scoped = transportEvents.filter { it.contains("group=" + group + " ") }
            if (scoped.isEmpty()) continue
            val hasSession = scoped.count { it.contains("loginSessionPresent=true") }
            val hasCookie = scoped.count { it.contains("cookiePresent=true") }
            val withAuth = scoped.count { it.contains("authPresent=true") }
            val statuses = scoped.mapNotNull {
                Regex("http=(\\d{3})").find(it)?.groupValues?.get(1)
            }.groupingBy { it }.eachCount()
            appendLine("  " + group + ": count=" + scoped.size +
                " loginSessionObserved=" + hasSession +
                " cookieObserved=" + hasCookie +
                " authObserved=" + withAuth +
                " http=" + statuses.entries.joinToString { it.key + ":" + it.value })
        }
        appendLine("最近的视频接口事件（不含凭据、URL 参数值和响应正文）：")
        events.filter { it.contains("group=v5 ") || it.contains("group=video_tab ") ||
            it.contains("group=lite ") || it.contains("group=legacy ") }
            .takeLast(12).forEach { appendLine("  " + it.take(500)) }
        appendLine("说明：Cookie/Authorization/签名标志只反映 Retrofit Request 对象。" +
            "下层 TTNet/Cronet 注入的认证或签名不在当前探针观察范围内。")
        appendLine("说明：HTTP 200 不代表业务成功；length=unknown 不等于空响应。" +
            "需结合 parsedModelType、businessCode、parsedDataPresent 再判断。")
        appendLine()
        appendLine("官方完整链路覆盖（HGDEEP）")
        val deepLines = evidence.lineSequence()
            .filter { it.contains("HGDEEP ") }.toList()
        val coverage = deepLines.lastOrNull { it.contains("HGDEEP COVERAGE") }
        appendLine("安装明细=" + (coverage?.trim()?.take(360)
            ?: "未发现。检查最新模块版本以及官方进程是否重启"))
        val deepEvents = deepLines.filter { it.contains("HGDEEP stage=") &&
            it.contains(" index=") }
        for (stage in listOf("dto", "builder", "commonParams", "parsedBody",
            "engineModel", "engineId", "engineDirect", "cachePut", "cacheGet",
            "cookieStore")) {
            val matched = deepEvents.filter { it.contains("stage=" + stage + " ") }
            appendLine("  " + stage + "=" + matched.size)
            if (stage == "builder") {
                val media = matched.filter { it.contains("group=video_") }
                appendLine("    播放取址请求=" + media.size)
                appendLine("    带会话 Cookie=" +
                    media.count { it.contains("loginSessionPresent=true") })
                appendLine("    带认证头=" +
                    media.count { it.contains("authPresent=true") })
                appendLine("    带签名头=" +
                    media.count { Regex("signCount=[1-9]").containsMatchIn(it) })
            } else if (stage == "parsedBody") {
                appendLine("    有视频模型数据=" +
                    matched.count { it.contains("dataPresent=true") })
                appendLine("    非空嵌套模型=" +
                    matched.count { it.contains("nestedModelPresent=true") })
            } else if (stage == "engineModel") {
                appendLine("    提交模型到播放器=" +
                    matched.count { it.contains("modelPresent=true") })
            } else if (stage == "dto") {
                appendLine("    构造后字段仍不可见（不代表请求无参数）=" +
                    matched.count { it.contains("fieldsDeferredPossible=true") })
            }
        }
        appendLine("最后几条独立取址及模型事件：")
        deepEvents.filter {
            it.contains("stage=dto ") || it.contains("stage=builder ") || it.contains("stage=parsedBody ") ||
                it.contains("stage=engineModel ")
        }.takeLast(16).forEach { appendLine("  " + it.trim().take(430)) }
        appendLine("诊断注释：builder 按视频请求/其他请求分别限额；" +
            "fieldsAtConstruction 仅表示构造函数返回时的快照。")
        appendLine("诊断注释：parsedBody=0 表示对应方法未观察到调用，" +
            "并不表示服务端返回空模型；需结合 engineModel 观察。")
        appendLine("观察范围：Android/Java 请求构造、模型解析、TTVideoEngine。" +
            "Native Cronet 内部签名、TLS 明文和 DRM 密钥均未采集。")
        appendLine("模型已送入播放器≠已获得独立客户端可复用的账号播放接口。")
        appendLine()
        appendLine("建议复现：播放首页视频→打开全集→长按→切后台→")
        appendLine("尝试通知栏播放/暂停→切回→立刻导出 ZIP。")
        appendLine("未观测到不代表失败：可能没有复现、日志被轮换或 Root 未授权。")
        appendLine("日志可能包含个人数据，请审查后分享。")
    }
}
