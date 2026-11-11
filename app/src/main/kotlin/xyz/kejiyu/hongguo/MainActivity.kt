package xyz.kejiyu.hongguo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.TimeUnit
import xyz.kejiyu.hongguo.hooks.TargetNames

class MainActivity : Activity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvTargetVersions: TextView
    private lateinit var tvStatus: TextView
    private var currentVersion: String = ""
    private var forceStopRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBars()

        tvVersion = findViewById(R.id.tv_version)
        tvTargetVersions = findViewById(R.id.tv_target_versions)
        tvStatus = findViewById(R.id.tv_update_status)

        currentVersion = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
        tvVersion.text = "模块 ${BuildConfig.VERSION_NAME}  ·  versionCode ${BuildConfig.VERSION_CODE}"
        updateTargetVersionText()

        findViewById<View>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_open_target).setOnClickListener {
            val launch = targetPackage()?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "未找到已安装的红果 App", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_force_stop_target).setOnClickListener {
            confirmForceStop()
        }

        findViewById<View>(R.id.btn_export_diagnostics).setOnClickListener {
            ModuleDiagnostics.chooseDestination(this)
        }

        findViewById<View>(R.id.btn_check_update).setOnClickListener { manualCheck() }
        findViewById<View>(R.id.btn_github).setOnClickListener {
            UpdateChecker.openUrl(this, UpdateChecker.REPO_URL)
        }
        findViewById<View>(R.id.btn_telegram).setOnClickListener {
            UpdateChecker.openUrl(this, UpdateChecker.TG_CHANNEL_URL)
        }

        UpdateChecker.resetShown()
        silentCheck()
        openSettingsFromMenuIntent(intent)
    }

    private fun openSettingsFromMenuIntent(source: Intent?) {
        if (source?.getBooleanExtra("xhongguo_open_settings", false) != true) return
        source.removeExtra("xhongguo_open_settings")
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSettingsFromMenuIntent(intent)
    }

    @Deprecated("Android document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (ModuleDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        updateTargetVersionText()
        UpdateChecker.showUpdateDialogIfNeeded(this)
    }

    private fun targetPackage(): String? = when {
        packageInfo(TargetNames.CN_PACKAGE) != null -> TargetNames.CN_PACKAGE
        packageInfo(TargetNames.OVERSEA_PACKAGE) != null -> TargetNames.OVERSEA_PACKAGE
        else -> null
    }

    /**
     * Force-stop only the explicitly allowlisted Hongguo package selected by
     * the adjacent "Launch Hongguo" button. Never kill all user processes.
     */
    private fun confirmForceStop() {
        if (forceStopRunning) return
        val target = targetPackage()
        if (target == null) {
            Toast.makeText(this, "未找到已安装的红果 App", Toast.LENGTH_SHORT).show()
            return
        }
        val label = if (target == TargetNames.CN_PACKAGE) "红果短剧（国版）"
            else "红果短剧（国际版）"
        AlertDialog.Builder(this)
            .setTitle("强制停止红果")
            .setMessage("将停止 $label 的全部进程和正在播放的视频，以便下次启动加载新模块 Hook。不会清除账号或缓存。\n\n需要先在 KernelSU Next 中授予 Xhongguo Root 权限。")
            .setNegativeButton("取消", null)
            .setPositiveButton("强制停止") { _, _ -> forceStopWithRoot(target) }
            .show()
    }

    private fun forceStopWithRoot(target: String) {
        if (forceStopRunning) return
        // Hardcode allowed package identities, regardless of the supplied value.
        if (target != TargetNames.CN_PACKAGE && target != TargetNames.OVERSEA_PACKAGE) return
        forceStopRunning = true
        val button = findViewById<View>(R.id.btn_force_stop_target)
        val status = findViewById<TextView>(R.id.tv_force_stop_status)
        button.isEnabled = false
        status.visibility = View.VISIBLE
        status.text = "正在请求 Root 并停止红果…"
        Thread({
            val result = try {
                val check = runRootCommand("id -u")
                if (check.first != 0 || check.second.trim() != "0") {
                    "强制停止失败：未获得 Root 授权，请在 KernelSU Next 中允许 Xhongguo"
                } else {
                    val stop = runRootCommand("am force-stop $target")
                    if (stop.first == 0) "已强制停止红果。点击「启动红果」即可重新加载模块。"
                    else "停止失败：Root 执行 am force-stop 未成功（退出码 ${stop.first}）"
                }
            } catch (e: Throwable) {
                "强制停止失败：${e.javaClass.simpleName} ${e.message ?: ""}"
            }
            runOnUiThread {
                forceStopRunning = false
                button.isEnabled = true
                status.text = result
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }, "Xhongguo-ForceStop").start()
    }

    private fun runRootCommand(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        try {
            // Launching su for the first time may show a KernelSU permission
            // prompt. Never block UI or wait indefinitely.
            if (!process.waitFor(20L, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1 to "timeout"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.exitValue() to output.take(400)
        } finally {
            process.destroy()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo? = try {
        packageManager.getPackageInfo(pkg, 0)
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    private fun installedLine(label: String, pkg: String, supported: List<String>): String {
        val pi = packageInfo(pkg) ?: return "$label  未安装\n$pkg"
        val versionName = pi.versionName ?: "未知"
        val code = versionCodeOf(pi)
        val status = if (versionName in supported) "✓ 已适配" else "! 待验证（已保护原生布局）"
        return "$label  $versionName  ·  $status\nversionCode $code  ·  $pkg"
    }

    private fun updateTargetVersionText() {
        if (!::tvTargetVersions.isInitialized) return
        tvTargetVersions.text = buildString {
            append("已适配版本\n")
            append("国版  ${TargetNames.SUPPORTED_CN_VERSIONS.joinToString("  ·  ")}\n")
            append("国际版  ${TargetNames.SUPPORTED_OVERSEA_VERSIONS.joinToString("  ·  ")}\n\n")
            append("当前设备\n")
            append(installedLine("国版", TargetNames.CN_PACKAGE, TargetNames.SUPPORTED_CN_VERSIONS))
            append("\n\n")
            append(installedLine("国际版", TargetNames.OVERSEA_PACKAGE, TargetNames.SUPPORTED_OVERSEA_VERSIONS))
        }
    }

    private fun silentCheck() {
        if (!UpdateChecker.checking && UpdateChecker.latestVersion != null) {
            UpdateChecker.showUpdateDialogIfNeeded(this)
            return
        }
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            if (latest != null) UpdateChecker.showUpdateDialogIfNeeded(this)
        }
    }

    private fun manualCheck() {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "正在检查更新…"
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = when {
                latest != null -> {
                    UpdateChecker.showUpdateDialogIfNeeded(this)
                    "发现新版本：$latest"
                }
                UpdateChecker.lastError != null -> "检查更新失败：${UpdateChecker.lastError}"
                else -> "已是最新版本"
            }
        }
    }

    private fun applySystemBars() {
        val bg = getColor(R.color.app_bg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) {
            val night = (resources.configuration.uiMode and 0x30) == 0x20
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (night) 0 else lightBars,
                lightBars
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                val night = (resources.configuration.uiMode and 0x30) == 0x20
                window.decorView.systemUiVisibility =
                    if (night) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
        }
    }
}
