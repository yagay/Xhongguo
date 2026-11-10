package xyz.kejiyu.hongguo

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.TextView
import android.widget.Toast
import xyz.kejiyu.hongguo.hooks.TargetNames

class MainActivity : Activity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvTargetVersions: TextView
    private lateinit var tvStatus: TextView
    private var currentVersion: String = ""

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
            val pkg = when {
                packageInfo(TargetNames.CN_PACKAGE) != null -> TargetNames.CN_PACKAGE
                packageInfo(TargetNames.OVERSEA_PACKAGE) != null -> TargetNames.OVERSEA_PACKAGE
                else -> null
            }
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "未找到已安装的红果 App", Toast.LENGTH_SHORT).show()
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
    }

    override fun onResume() {
        super.onResume()
        updateTargetVersionText()
        UpdateChecker.showUpdateDialogIfNeeded(this)
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
        val status = if (versionName in supported) "✓ 已适配" else "! 待验证（已启用布局保护）"
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
