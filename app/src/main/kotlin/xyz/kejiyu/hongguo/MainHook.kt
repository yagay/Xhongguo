package xyz.kejiyu.hongguo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import xyz.kejiyu.hongguo.hooks.Hooks
import xyz.kejiyu.hongguo.hooks.OfficialPlaybackProbe
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "ZongHe"
        @Volatile private var currentProcessName: String = ""
        private const val DEMO_PACKAGE = "xyz.kejiyu.hongguo"

        private val TARGET_PACKAGES = setOf(
            "com.phoenix.read",
            "com.phoenix.read.oversea.gp",
        )
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        currentProcessName = param.processName ?: ""
        LogUtil.init(param.processName)
        LogUtil.info("══════════ 模块加载 ══════════")
        LogUtil.info("进程=${param.processName} | systemServer=${param.isSystemServer}")
        LogUtil.info("框架=${frameworkName} v${frameworkVersion} | API=${apiVersion}")
        LogUtil.info("日志文件=${LogUtil.getFilePath()}")
        LogUtil.info("══════════════════════════════")
        log(Log.INFO, TAG, "模块已加载 | 进程=${param.processName} | API=${apiVersion}")

        if (param.isSystemServer) return // no network/update checks in system_server
        try {
            val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
            LogUtil.info("更新检查启动 | 当前版本=$ver")
            UpdateChecker.checkUpdate(ver) { latest ->
                if (latest != null) {
                    LogUtil.info("发现新版本 $latest（当前 $ver）")
                    Hooks.showUpdateDialogIfAvailable()
                } else if (UpdateChecker.lastError != null) {
                    LogUtil.warn("更新检查失败: ${UpdateChecker.lastError}")
                }
            }
        } catch (e: Exception) {
            LogUtil.error("更新检查启动失败", e)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageLoaded: $pkg | first=${param.isFirstPackage}")
        val cl = param.defaultClassLoader
        if (cl == null) { LogUtil.warn("defaultClassLoader=null"); return }

        if (pkg == DEMO_PACKAGE) {
            LogUtil.info("  → 安装演示 Hook")
            try { Hooks.installDemoHooks(this, cl); LogUtil.info("  ✓ 完成") }
            catch (e: Exception) { LogUtil.error("演示 Hook 失败", e) }
        }
        if (pkg in TARGET_PACKAGES) {
            LogUtil.info("  → 目标包，等待 onPackageReady")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageReady: $pkg")
        LogUtil.info("  classLoader=${param.classLoader}")
        LogUtil.info("  appComponentFactory=${param.appComponentFactory}")

        if (pkg in TARGET_PACKAGES) {

            val proc = currentProcessName
            val isWebViewSandbox = proc.contains(":sandboxed_process") ||
                proc.contains(":privileged_process") ||
                proc.contains(":renderer")
            if (isWebViewSandbox) {
                LogUtil.info("  → WebView 沙箱/渲染进程，跳过 UI 业务 Hook | process=$proc")
                return
            }
            LogUtil.info("  → 安装业务 Hook | process=$proc | main=${proc == pkg}")
            try {
                Hooks.installBusinessHooks(this, param.classLoader, pkg)
                LogUtil.info("  ✓ 业务 Hook 安装完成")
            } catch (e: Exception) {
                LogUtil.error("业务 Hook 安装失败", e)
            }
            // Independent of playback/UI modifications and the master toggle.
            // On-demand, passive metadata probes never alter requests or bodies.
            try {
                val app = try {
                    Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null)
                        as? android.content.Context
                } catch (_: Throwable) { null }
                if (app != null) OfficialPlaybackProbe.attach(app.applicationContext)
                val version = try {
                    @Suppress("DEPRECATION")
                    app?.packageManager?.getPackageInfo(pkg, 0)?.versionName ?: "unknown"
                } catch (_: Throwable) { "unknown" }
                OfficialPlaybackProbe.install(this, param.classLoader, pkg, version)
            } catch (e: Throwable) {
                LogUtil.warn("HGPROBE install skipped: " + e.javaClass.simpleName)
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        LogUtil.info("system_server 启动")
        xyz.kejiyu.hongguo.hooks.SystemAudioActivity.install()
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        LogUtil.info("热重载中...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        LogUtil.info("热重载完成, 旧 hook=${param.oldHookHandles.size}")
        LogUtil.diagDump(true)
    }
}
