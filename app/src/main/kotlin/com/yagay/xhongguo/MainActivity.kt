package com.yagay.xhongguo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.*
import java.util.concurrent.TimeUnit
import com.yagay.xhongguo.hooks.TargetNames
import com.yagay.xhongguo.ui.SettingsSurface
import com.yagay.xhongguo.ui.UiTheme
import com.yagay.xhongguo.ui.UiIcon
import com.google.android.material.materialswitch.MaterialSwitch

/** Standalone shell. All feature settings share the renderer used by the injected panel. */
open class MainActivity : Activity() {
    private val prefs by lazy { ModuleSettingsStore.prefs(this) }
    private lateinit var shell: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var navigation: LinearLayout
    private lateinit var settings: SettingsSurface
    private var tab = 0
    private var rootStatus = "未检测"
    private var checkingRoot = false
    private var busy = false
    private var stopResult = ""
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (::settings.isInitialized && !isFinishing && !isDestroyed) {
            shell.removeCallbacks(refreshTask)
            shell.postDelayed(refreshTask, 120L)
        }
    }
    private val refreshTask = Runnable {
        if (!isFinishing && !isDestroyed && ::settings.isInitialized) {
            settings.refresh()
            theme().window(window)
            renderNavigation()
            if (tab != 0) showTab(tab)
        }
    }
    private fun theme() = UiTheme(this, prefs)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0)?.coerceIn(0, 2) ?: 0
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body = FrameLayout(this)
        navigation = LinearLayout(this)
        shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        setContentView(shell)
        settings = SettingsSurface(this, prefs, { values ->
            ModuleSettingsStore.saveBatch(this, values).also {
                if (it) {
                    theme().window(window)
                    renderNavigation()
                    if (tab != 0) showTab(tab)
                }
            }
        }, { finish() }, true, { showTab(2) }, factory = { context ->
            val palette = theme()
            MaterialSwitch(context).apply {
                thumbTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.onAccent, palette.secondary))
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(palette.accent, palette.muted))
            }
        })
        savedInstanceState?.getString("page")?.let {
            runCatching { SettingsCatalog.Page.valueOf(it) }.getOrNull()?.let { page -> settings.open(page) }
        }
        showTab(tab)
        routeIntent(intent)
    }
    private fun routeIntent(source: Intent?) {
        if (source?.getBooleanExtra("xhongguo_open_settings", false) == true) {
            source.removeExtra("xhongguo_open_settings")
            showTab(0); settings.open(SettingsCatalog.Page.HOME)
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); routeIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (::settings.isInitialized) { settings.refresh(); showTab(tab) }
    }
    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::shell.isInitialized) shell.removeCallbacks(refreshTask)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); outState.putString("page", settings.page.name)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity navigation")
    override fun onBackPressed() {
        if (tab == 0 && settings.back()) return
        if (tab != 0) { showTab(0); return }
        super.onBackPressed()
    }
    private fun renderNavigation() {
        val t = theme()
        shell.setBackgroundColor(t.page)
        navigation.removeAllViews(); navigation.setBackgroundColor(t.surface)
        listOf("功能", "状态", "工具").forEachIndexed { i, name ->
            val col = t.column().apply {
                gravity = Gravity.CENTER; minimumHeight = t.dp(64); background = t.clickBackground(t.surface)
                isFocusable = true; setOnClickListener { showTab(i) }
                contentDescription = name + if (tab == i) "，已选中" else ""
            }
            val icon = UiIcon(this, listOf("tune", "status", "tools")[i], if (tab == i) t.accent else t.secondary)
            if (tab == i) icon.background = t.shape(t.accentSoft, 18)
            col.addView(icon, LinearLayout.LayoutParams(t.dp(44), t.dp(30)))
            col.addView(t.label(name, 12, if (tab == i) t.accent else t.secondary, tab == i))
            navigation.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    private fun showTab(index: Int) {
        tab = index.coerceIn(0, 2); body.removeAllViews()
        val t = theme()
        if (tab == 0) {
            body.addView(settings.view, FrameLayout.LayoutParams(-1, -1))
        } else {
            val page = t.column()
            val toolbar = t.row().apply {
                minimumHeight = t.dp(56); setPadding(t.dp(16), 0, t.dp(8), 0)
                addView(t.label(if (tab == 1) "运行状态" else "工具", 22, t.text, true), LinearLayout.LayoutParams(0, -2, 1f))
                if (tab == 1) addView(t.action("刷新") { showTab(1) })
            }
            page.addView(toolbar)
            val scroll = ScrollView(this)
            val col = t.column().apply { setPadding(t.dp(12), 0, t.dp(12), t.dp(20)) }
            scroll.addView(col); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            body.addView(page, FrameLayout.LayoutParams(-1, -1))
            fun section(name: String): LinearLayout {
                col.addView(t.label(name, 13, t.accent, true).apply { setPadding(t.dp(10), t.dp(14), 0, t.dp(6)) })
                return t.column().apply { background = t.shape(t.surface, 18); col.addView(this) }
            }
            fun line(group: LinearLayout, title: String, desc: String, action: (() -> Unit)? = null) {
                if (group.childCount > 0) group.addView(View(this).apply { setBackgroundColor(t.muted) }, LinearLayout.LayoutParams(-1, t.dp(1)))
                val row = t.row().apply {
                    minimumHeight = t.dp(if (t.compact) 52 else 64); setPadding(t.dp(14), t.dp(8), t.dp(12), t.dp(8))
                    val labels = t.column()
                    labels.addView(t.label(title, 16))
                    if (desc.isNotBlank()) labels.addView(t.label(desc, 12, t.secondary))
                    addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    if (action != null) {
                        addView(t.label("›", 23, t.secondary)); background = t.clickBackground(); isFocusable = true
                        setOnClickListener { action() }
                    }
                }
                group.addView(row)
            }
            if (tab == 1) {
                val g = section("设备与兼容")
                line(g, "Root 权限", rootStatus) { checkRoot() }
                line(g, "模块版本", BuildConfig.VERSION_NAME + " · " + BuildConfig.BUILD_REVISION)
                for ((name, pkg) in targets()) {
                    val pi = packageInfo(pkg)
                    line(g, name, if (pi == null) "未安装" else
                        "${pi.versionName} · ${pi.longVersionCode}\n" +
                            if (pkg == TargetNames.CN_PACKAGE && pi.versionName == "7.4.2.32") "APK 签名已核对；运行待验证" else "运行时能力检测")
                    if (pi == null) continue
                    val status = section("$name · 最近运行证据")
                    for ((label, key) in listOf("LSPosed Hook 初始化" to "hook_init", "设置同步" to "settings_sync",
                        "原生接口签名匹配" to "native_contract", "播放 Hook 注册（至少一项）" to "native_transport",
                        "系统框架音频快照" to "audio_snapshot", "原生播放确认" to "playback",
                        "系统媒体元数据" to "media_metadata", "外部音频自动恢复" to "audio_resume"))
                        line(status, label, RuntimeStatus.describe(this, pkg, key))
                }
                col.addView(t.label("证据仅表示最近收到的事件，不代表目标进程此刻仍在运行。", 12, t.secondary).apply {
                    setPadding(t.dp(10), t.dp(14), t.dp(10), 0)
                })
            } else {
                val g = section("红果控制")
                line(g, "启动红果", "打开已安装的客户端") { chooseTarget { pkg -> launch(pkg) } }
                line(g, "重新启动红果", if (stopResult.isBlank()) "停止后重新加载 Hook · 需要 Root" else stopResult) { stopTarget(true) }
                line(g, "强制停止红果", "停止进程，不清除账号或缓存") { stopTarget(false) }
                val d = section("诊断与配置")
                line(d, "导出诊断日志", "ZIP · Hook 与运行日志") { ModuleDiagnostics.chooseDestination(this) }
                line(d, "备份设置", "导出 JSON") { SettingsBackup.choose(this, false) }
                line(d, "恢复设置", "导入前预览差异") { SettingsBackup.choose(this, true) }
                line(d, "恢复默认设置", "不清除红果账号或缓存") { settings.confirmReset() }
                val u = section("版本与关于")
                line(u, "检查更新", "Test 通道 · GitHub Actions") { UpdateChecker.openUrl(this, UpdateChecker.TEST_BUILDS_URL) }
                line(u, "Xhongguo 项目", BuildConfig.VERSION_NAME) { UpdateChecker.openUrl(this, UpdateChecker.REPO_URL) }
                line(u, "开源许可证", "保留原项目许可及版权") { UpdateChecker.openUrl(this, UpdateChecker.REPO_URL + "/blob/test/LICENSE") }
            }
        }
        t.window(window); renderNavigation()
    }
    private fun targets() = listOf("红果国版" to TargetNames.CN_PACKAGE, "红果国际版" to TargetNames.OVERSEA_PACKAGE)
    private fun packageInfo(pkg: String): PackageInfo? = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
    private fun chooseTarget(action: (String) -> Unit) {
        val installed = targets().filter { packageInfo(it.second) != null }
        when (installed.size) {
            0 -> Toast.makeText(this, "未找到红果", Toast.LENGTH_SHORT).show()
            1 -> action(installed.single().second)
            else -> AlertDialog.Builder(theme().dialogContext()).setTitle("选择客户端")
                .setItems(installed.map { it.first }.toTypedArray()) { _, i -> action(installed[i].second) }.show()
        }
    }
    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent) else Toast.makeText(this, "无法打开红果", Toast.LENGTH_SHORT).show()
    }
    private fun stopTarget(relaunch: Boolean) {
        if (busy) return
        chooseTarget { pkg ->
            AlertDialog.Builder(theme().dialogContext()).setTitle(if (relaunch) "重新启动红果" else "强制停止红果")
                .setMessage("会中断当前播放，需要 Root，不清除账号或缓存。")
                .setNegativeButton("取消", null).setPositiveButton("确认") { _, _ ->
                    busy = true; stopResult = "正在执行…"; showTab(2)
                    Thread {
                        val success = runCatching { root("id -u").let { it.first == 0 && it.second.trim() == "0" } &&
                            root("am force-stop $pkg").first == 0 }.getOrDefault(false)
                        runOnUiThread {
                            busy = false; stopResult = if (success) "已停止" else "操作失败，请检查 Root 授权"
                            if (isDestroyed || isFinishing) return@runOnUiThread
                            if (tab == 2) showTab(2)
                            if (success && relaunch) launch(pkg)
                        }
                    }.start()
                }.show()
        }
    }
    private fun root(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(20L, TimeUnit.SECONDS)) { process.destroyForcibly(); return -1 to "timeout" }
            return process.exitValue() to process.inputStream.bufferedReader().use { it.readText().take(400) }
        } finally { process.destroy() }
    }
    private fun checkRoot() {
        if (checkingRoot) return
        checkingRoot = true; rootStatus = "正在检测…"; showTab(1)
        Thread {
            val result = runCatching { root("id -u") }.getOrNull()
            runOnUiThread {
                checkingRoot = false
                rootStatus = if (result?.first == 0 && result.second.trim() == "0") "已授权" else "未获得 Root"
                if (!isFinishing && !isDestroyed && tab == 1) showTab(1)
            }
        }.start()
    }
    @Deprecated("Document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SettingsBackup.EXPORT || requestCode == SettingsBackup.IMPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) Thread {
                val result = runCatching {
                    if (requestCode == SettingsBackup.EXPORT) { SettingsBackup.export(this, uri); null }
                    else SettingsBackup.read(this, uri)
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    val values = result.getOrNull()
                    if (result.isFailure) Toast.makeText(this, "操作失败：" + result.exceptionOrNull()?.message, Toast.LENGTH_LONG).show()
                    else if (values == null) Toast.makeText(this, "备份已导出", Toast.LENGTH_SHORT).show()
                    else {
                        val changes = SettingsBackup.diff(this, values)
                        val preview = if (changes.isEmpty()) "没有配置差异" else changes.joinToString("\n")
                        AlertDialog.Builder(theme().dialogContext()).setTitle("恢复配置 · ${changes.size} 项变化")
                            .setMessage(preview).setNegativeButton("取消", null).setPositiveButton("确认恢复") { _, _ ->
                                val ok = runCatching { SettingsBackup.apply(this, values) }
                                Toast.makeText(this, if (ok.isSuccess) "配置已恢复" else "恢复失败", Toast.LENGTH_LONG).show()
                                settings.refresh(); showTab(tab)
                            }.show()
                    }
                }
            }.start()
            return
        }
        if (ModuleDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }
}
