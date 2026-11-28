package com.yagay.xhongguo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Standalone settings UI. Nothing needs to be opened inside the target app. */
class SettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var screen: LinearLayout
    private lateinit var heading: TextView
    private lateinit var subtitle: TextView
    private var currentPage = SettingsCatalog.Page.HOME
    private val categoryRows = linkedMapOf<SettingsCatalog.Page, MutableList<Pair<String, View>>>()
    private val pages = linkedMapOf<SettingsCatalog.Page, LinearLayout>()
    private val switches = linkedMapOf<String, Switch>()
    private val searchItems = mutableListOf<SettingsSearch.Item>()
    private var refreshing = false
    private val prefs by lazy { ModuleSettingsStore.prefs(this) }
    private var speedText: TextView? = null
    private var speedSwitch: Switch? = null
    private var limitText: TextView? = null
    private var externalDelayText: TextView? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun color(id: Int) = getColor(id)
    private fun background(id: Int) = GradientDrawable().apply {
        setColor(color(id))
        cornerRadius = dp(16).toFloat()
    }
    private fun label(value: String, size: Int, colorId: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color(colorId))
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.app_bg)
        window.navigationBarColor = color(R.color.app_bg)
        scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.app_bg))
            isVerticalScrollBarEnabled = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("‹  返回", 15, R.color.accent, true).apply {
            setPadding(0, 0, 0, dp(18))
            setOnClickListener { goBack() }
        })
        heading = label("Xhongguo 设置", 26, R.color.text_primary, true)
        root.addView(heading)
        subtitle = label("直接在模块 App 中管理红果的 LSPosed 功能", 13, R.color.text_secondary).apply {
            setPadding(0, dp(5), 0, dp(15))
        }
        root.addView(subtitle)
        screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(screen, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        
        toggle("模块总开关", "统一启用或停用模块功能", "master_on")

        toggle("隐藏状态栏", "视频页面沉浸显示", "status_bar")
        toggle("隐藏控件", "三键导航下保留首页底部栏和全集入口", "control_hide")
        toggle("隐藏选集相关控件", "适用于联播页面", "player_bar")
        toggle("隐藏视频进度条", "隐藏已适配的视频进度条", "progress_off")
        toggle("隐藏系统小白条", "仅手势导航有效，三键导航始终保留按键", "nav_bar_off")
        toggle("视频长按菜单入口", "在原生视频长按菜单中添加 Xhongguo 设置", "video_longpress_settings", true)
        toggle("禁用商城", "隐藏底部「商城」Tab，并阻止打开独立商城页面；不影响剧场", "disable_shopping_mall", true)
        toggle("隐藏我的快捷入口", "隐藏「福利、订单、消息」整排，保留账号、收藏和历史", "hide_mine_shortcuts", true)
        toggle("应用内持续播放（无小窗）", "实验功能：保护明确的非播放页面切换，不显示小窗；全集退出共享暂未启用", "in_app_continuous_playback")
        toggle("应用内小窗继续播放", "实验功能：跨应用内页面复用原播放器；整窗拖动贴边、点击控制、双击缩放；原播放页面销毁时安全退出", "in_app_mini_player")
        toggle("系统媒体后台播放", "实验功能：使用红果原播放器，通知栏显示系统媒体卡片（播放/暂停/返回），无悬浮图标；后台仍可能受原生播放器生命周期限制", "system_media_playback")
        toggle("音频焦点丢失时暂停", "其他应用抢占音频焦点时暂停短剧", "audio_focus_pause", ModuleSettingsStore.defaultBoolean("audio_focus_pause"))
        toggle("通话时自动暂停", "监听系统铃声、通话和语音通信模式", "audio_call_pause", ModuleSettingsStore.defaultBoolean("audio_call_pause"))
        toggle("焦点恢复后自动播放", "仅临时中断且同一视频，默认关闭", "audio_focus_auto_resume", ModuleSettingsStore.defaultBoolean("audio_focus_auto_resume"))
        toggle("通话结束自动恢复", "仅恢复同一视频；永久失焦仍需手动播放", "audio_call_auto_resume", ModuleSettingsStore.defaultBoolean("audio_call_auto_resume"))
        toggle("降低音量请求时暂停", "默认不额外暂停普通提示音", "audio_focus_pause_duck", ModuleSettingsStore.defaultBoolean("audio_focus_pause_duck"))
        toggle("其他应用音频检测", "检测实际媒体音频（含未抢焦点的 App）；需 LSPosed 系统框架作用域及重启，默认开启", "audio_external_pause", ModuleSettingsStore.defaultBoolean("audio_external_pause"))
        toggle("其他应用停止后自动恢复", "仅恢复被外部音频暂停的原视频；手动暂停不恢复", "audio_external_auto_resume", ModuleSettingsStore.defaultBoolean("audio_external_auto_resume"))
        externalDelayText = action("外部音频恢复延迟", externalDelayDescription(), ModuleSettingsStore.EXTERNAL_RESUME_DELAY) { chooseExternalDelay() }
        toggle("暂停后恢复控件", "暂停视频时临时恢复控件", "restore_controls_pause")

        toggle("自动最高画质", "使用当前视频支持的最高画质", "max_quality")
        speedSwitch = toggle("默认播放倍速", "可在下一项选择具体倍速", "default_speed")
        speedText = action("选择倍速", speedDescription(), "default_speed_value") { chooseSpeed() }
        toggle("双击打开评论", "替换默认的双击点赞", "double_tap_comment")
        toggle("禁用下拉刷新", "禁用刷新手势与提示区域", "pull_refresh")
        toggle("拦截顶部下滑", "拦截顶部区域的下滑手势", "top_zone")

        toggle("隐藏广告与挂件", "默认开启：已适配广告层、金宝箱、悬浮挂件和我的页面广告卡片", "hide_all_ads", true)
        toggle("VIP 状态 Hook", "启用现有的 VIP 适配逻辑", "vip_unlock")
        toggle("VIP 图标", "控制 VIP 图标与挂件", "vip_icon")

        toggle("官方播放/账号接口诊断", "仅记录接口路径、参数名、账号 Cookie 是否存在、签名头是否存在、状态码、耗时和响应长度；不保存任何凭据或视频地址。无需开启模块总开关。", "official_playback_probe")
        action("导出诊断日志 ZIP", "Root + LSPosed Hook 日志、红果进程 logcat、功能成功/失败分析", "diagnostic_export") {
            ModuleDiagnostics.chooseDestination(this)
        }

        toggle("OLED 亮度保护", "实验性亮度拦截", "oled_protect")
        toggle("通知栏快捷菜单", "红果需要授予通知权限", "notification_menu")
        toggle("自定义下载数量限制", "覆盖默认的下载数量限制", "download_limit_unlimit")
        limitText = action("下载限制数值", limitDescription(), "download_limit_values") { editLimits() }

        action("备份设置", "导出 JSON，仅包含模块配置", "backup_settings") {
            SettingsBackup.choose(this, false)
        }
        action("恢复设置", "导入 JSON，将替换当前模块配置", "restore_settings") {
            AlertDialog.Builder(this).setTitle("恢复设置")
                .setMessage("将替换当前模块的设置，不会修改红果账号及缓存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择备份") { _, _ -> SettingsBackup.choose(this, true) }.show()
        }
        buildPages()
        refreshRows()
        val restored = savedInstanceState?.getString("settings_page")?.let {
            runCatching { SettingsCatalog.Page.valueOf(it) }.getOrNull()
        } ?: SettingsCatalog.Page.HOME
        showPage(restored)
    }

    private fun goBack() {
        if (currentPage == SettingsCatalog.Page.HOME) finish()
        else showPage(SettingsCatalog.Page.HOME)
    }

    @Deprecated("Activity back navigation")
    override fun onBackPressed() { goBack() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_page", currentPage.name)
        super.onSaveInstanceState(outState)
    }

    private fun showPage(page: SettingsCatalog.Page) {
        currentPage = page
        heading.text = page.title
        subtitle.text = if (page == SettingsCatalog.Page.HOME) "常用功能与分类设置" else page.summary
        screen.removeAllViews()
        pages[page]?.let { screen.addView(it) }
        scroll.scrollTo(0, 0)
    }

    private fun buildPages() {
        for (page in listOf(SettingsCatalog.Page.HOME) + SettingsCatalog.categories) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pages[page] = container
            if (page == SettingsCatalog.Page.HOME) {
                section(container, "常用开关")
            }
            for ((_, row) in categoryRows[page].orEmpty().sortedBy { SettingsCatalog.rank(it.first) }) {
                appendTo(container, row)
            }
            if (page == SettingsCatalog.Page.HOME) {
                section(container, "功能分类")
                val search = row()
                addText(search, "搜索设置", "按功能名称、分类或关键词查找")
                search.addView(label("›", 23, R.color.text_secondary))
                search.setOnClickListener {
                    SettingsSearch.show(this, searchItems) { item ->
                        showPage(item.page)
                        val target = categoryRows[item.page]?.firstOrNull { it.first == item.key }?.second
                        target?.post { if (target.visibility == View.VISIBLE) target.requestFocus()
                            else Toast.makeText(this, "请先启用该项的上级开关", Toast.LENGTH_SHORT).show() }
                    }
                }
                appendTo(container, search)
                for (category in SettingsCatalog.categories) {
                    val entry = row()
                    addText(entry, category.title, category.summary)
                    entry.addView(label("›", 23, R.color.text_secondary))
                    entry.setOnClickListener { showPage(category) }
                    appendTo(container, entry)
                }
                container.addView(label(
                    "设置自动同步到红果。系统框架音频检测需要 LSPosed 系统框架作用域及重启；部分 Hook 需要重新打开红果。",
                    12, R.color.text_secondary
                ).apply { setPadding(dp(4), dp(18), dp(4), 0) })
            }
        }
    }

    @Deprecated("Android document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == SettingsBackup.EXPORT || requestCode == SettingsBackup.IMPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) Thread {
                val outcome = runCatching {
                    if (requestCode == SettingsBackup.EXPORT) SettingsBackup.export(this, uri)
                    else SettingsBackup.restore(this, uri)
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, if (outcome.isSuccess) "操作成功"
                            else "操作失败：" + outcome.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                        if (outcome.isSuccess && requestCode == SettingsBackup.IMPORT) recreate()
                    }
                }
            }.start()
            return
        }
        if (ModuleDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun section(container: LinearLayout, name: String) {
        container.addView(label(name, 14, R.color.accent, true).apply {
            setPadding(dp(5), dp(12), 0, dp(6))
        })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(9), dp(14), dp(9))
        minimumHeight = dp(56)
        background = background(R.color.surface)
        isClickable = true
        isFocusable = true
    }

    private fun addText(row: LinearLayout, title: String, sub: String): TextView {
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(label(title, 15, R.color.text_primary, true))
        val summary = label(sub, 12, R.color.text_secondary).apply {
            setPadding(0, dp(4), dp(6), 0)
        }
        stack.addView(summary)
        row.addView(stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return summary
    }

    private fun refreshRows() {
        refreshing = true
        try {
            for ((key, sw) in switches) sw.isChecked = prefs.getBoolean(key, ModuleSettingsStore.defaultBoolean(key))
            for ((_, rows) in categoryRows) for ((key, view) in rows) {
                val definition = SettingsCatalog.definition(key)
                view.visibility = if (definition.parentKeys.all {
                    prefs.getBoolean(it, ModuleSettingsStore.defaultBoolean(it))
                }) View.VISIBLE else View.GONE
            }
        } finally { refreshing = false }
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun append(key: String, row: View) {
        categoryRows.getOrPut(SettingsCatalog.page(key)) { mutableListOf() }.add(key to row)
    }

    private fun appendTo(container: LinearLayout, row: View) {
        container.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7) })
    }

    @Suppress("DEPRECATION")
    private fun toggle(name: String, sub: String, key: String, defaultValue: Boolean = false): Switch {
        val row = row()
        addText(row, name, sub)
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                if (refreshing) return@setOnCheckedChangeListener
                if (!ModuleSettingsStore.save(this@SettingsActivity, key, checked)) {
                    Toast.makeText(this@SettingsActivity, "保存设置失败", Toast.LENGTH_SHORT).show()
                }
                refreshRows()
            }
        }
        switches[key] = toggle
        searchItems.add(SettingsSearch.Item(key, name, sub, SettingsCatalog.page(key)))
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        append(key, row)
        return toggle
    }

    private fun action(name: String, sub: String, key: String, click: () -> Unit): TextView {
        val row = row()
        val summary = addText(row, name, sub)
        searchItems.add(SettingsSearch.Item(key, name, sub, SettingsCatalog.page(key)))
        row.addView(label("›", 23, R.color.text_secondary))
        row.setOnClickListener { click() }
        append(key, row)
        return summary
    }

    private fun externalDelayDescription(): String =
        "当前 " + ModuleSettingsStore.externalResumeDelay(prefs) / 1000.0 + " 秒"

    private fun chooseExternalDelay() {
        val options = ModuleSettingsStore.EXTERNAL_RESUME_DELAYS
        val selected = options.indices.minByOrNull {
            kotlin.math.abs(options[it] - ModuleSettingsStore.externalResumeDelay(prefs))
        } ?: 0
        AlertDialog.Builder(this)
            .setTitle("外部音频恢复延迟")
            .setSingleChoiceItems(options.map { "${it / 1000} 秒" }.toTypedArray(), selected) { dialog, index ->
                if (ModuleSettingsStore.save(this, ModuleSettingsStore.EXTERNAL_RESUME_DELAY, options[index]))
                    externalDelayText?.text = externalDelayDescription()
                else Toast.makeText(this, "保存设置失败", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }

    private val speeds = floatArrayOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
    private fun speedDescription(): String =
        "当前 " + prefs.getFloat("default_speed_value", 1f).toString() + "×"

    private fun chooseSpeed() {
        val current = prefs.getFloat("default_speed_value", 1f)
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - current) } ?: 1
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("默认播放倍速")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                ModuleSettingsStore.save(this, "default_speed_value", speeds[index])
                ModuleSettingsStore.save(this, "default_speed", true)
                speedSwitch?.isChecked = true
                speedText?.text = speedDescription()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val limitKeys = arrayOf(
        "download_limit_one_day_episode", "download_limit_one_day_series",
        "download_limit_total_series"
    )
    private fun limitDescription(): String =
        "单日集数 " + prefs.getInt(limitKeys[0], 99999) +
        " · 单日剧数 " + prefs.getInt(limitKeys[1], 99999) +
        " · 总缓存 " + prefs.getInt(limitKeys[2], 99999)

    private fun editLimits() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        val fields = arrayOf("单日最大下载集数", "单日最大下载剧数", "总缓存剧数上限")
            .mapIndexed { index, name ->
                form.addView(label(name, 13, R.color.text_secondary).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setText(prefs.getInt(limitKeys[index], 99999).toString())
                    form.addView(this)
                }
            }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义下载数量限制")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = fields.map { it.text.toString().trim().toIntOrNull() }
            if (values.any { it == null || it !in 1..999_999_999 }) {
                Toast.makeText(this, "请输入 1～999999999 的整数", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            values.forEachIndexed { index, value ->
                ModuleSettingsStore.save(this, limitKeys[index], value!!)
            }
            limitText?.text = limitDescription()
            Toast.makeText(this, "已保存。重新启动红果后下载进程生效", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
    }
}
