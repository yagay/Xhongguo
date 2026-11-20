package xyz.kejiyu.hongguo.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Modifier
import java.util.ArrayList
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook

/**
 * APK-verified Hongguo 7.3.5.32 native sidebar model filter.
 *
 * classes5.dex:
 *   com.dragon.read.kmp.mine.sidebar.MineSidebarController
 *     b(java.util.List): java.util.List
 *   com.dragon.read.kmp.mine.sidebar.MineSidebarPageKt
 *     static e(java.util.List, androidx.compose.runtime.Composer, int): void
 *   com.dragon.read.kmp.mine.model.n
 *     SidebarFunctionItemModel: a(Integer), b/c/d/e(String), f(Boolean)
 *
 * The right drawer is a ComposeView inside MainPageDrawerLayout.
 * Removing TextViews or their parent ViewGroups cannot remove its Compose
 * list entries. We only remove verified matching model entries from a copy
 * of the list, before Compose measures the remaining rows.
 */
internal object NativeMineSidebarFilter {
    @Volatile var enabled = false
    private const val MODEL = "com.dragon.read.kmp.mine.model.n"
    private const val CONTROLLER =
        "com.dragon.read.kmp.mine.sidebar.MineSidebarController"
    private const val COMPOSE =
        "com.dragon.read.kmp.mine.sidebar.MineSidebarPageKt"

    private val blockedTitles = setOf("创作中心", "我的钱包", "会员中心", "游戏中心")
    private val blockedRoutes = setOf(
        "creator_center", "creatorcenter", "wallet_center",
        "walletcenter", "vip_center", "vipcenter", "game_center", "gamecenter"
    )
    // Verified against n$a serializer in classes5.dex:
    //   b = cell_name, c = jump_url; d/e are icon URLs and must not
    //   drive filtering (other items can reuse the same image asset).
    private val modelStringFields = arrayOf("b", "c")
    private var lastProbeAt = 0L
    private var inspectedItems = 0
    private var filteredItems = 0

    private fun isBlockedModel(value: Any): Boolean {
        if (value.javaClass.name != MODEL) return false
        for (fieldName in modelStringFields) {
            val str = try {
                val field = value.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(value) as? String
            } catch (_: Throwable) { null } ?: continue
            val title = str.trim().replace(" ", "").replace("\u3000", "")
            if (title in blockedTitles) return true
            val route = str.lowercase()
            if (blockedRoutes.any { marker -> route.contains(marker) }) return true
        }
        return false
    }

    private data class Result(val value: Any?, val removed: Int, val found: Int)

    /**
     * Lists may be grouped as List<List<SidebarFunctionItemModel>>.
     * Drop the empty subgroup if all of its elements were filtered,
     * preserving all unrelated lists and original model objects.
     */
    private fun filterValue(value: Any?, depth: Int = 0): Result {
        if (value == null) return Result(null, 0, 0)
        if (value.javaClass.name == MODEL) {
            return if (isBlockedModel(value)) Result(null, 1, 1)
            else Result(value, 0, 1)
        }
        if (value !is List<*> || depth >= 3 || value.size > 200) {
            return Result(value, 0, 0)
        }
        var removed = 0
        var found = 0
        val result = ArrayList<Any?>(value.size)
        for (item in value) {
            val filtered = filterValue(item, depth + 1)
            removed += filtered.removed
            found += filtered.found
            // A removed model or an emptied model-only subgroup contributes
            // neither an item nor any Compose placeholder.
            if (filtered.removed > 0 &&
                (filtered.value == null ||
                    (filtered.value is List<*> && filtered.value.isEmpty()))) {
                continue
            }
            result.add(filtered.value)
        }
        return Result(if (removed > 0) result else value, removed, found)
    }

    private fun filterList(input: List<*>, source: String): List<*>? {
        val result = filterValue(input)
        if (result.found > 0) {
            inspectedItems += result.found
            filteredItems += result.removed
        }
        if (result.removed > 0) {
            LogUtil.info("原生侧边菜单: 在 Compose 数据源中剔除 " +
                result.removed + " 项 source=" + source +
                " input=" + input.size +
                " output=" + (result.value as? List<*>)?.size)
            LogUtil.incr("nativeMineSidebarItemsFiltered")
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastProbeAt > 7000L) {
            lastProbeAt = now
            LogUtil.info("原生侧边菜单: 模型过滤探测" +
                " source=" + source + " listSize=" + input.size +
                " matchedModelCount=" + result.found +
                " removed=" + result.removed +
                " firstType=" + input.firstOrNull()?.javaClass?.name +
                " cumulativeSeen=" + inspectedItems +
                " cumulativeRemoved=" + filteredItems +
                " enabled=" + enabled)
            LogUtil.incr("nativeMineSidebarModelProbe")
        }
        return if (result.removed > 0) result.value as? List<*> else null
    }

    fun install(module: MainHook, loader: ClassLoader) {
        var installed = 0
        try {
            val clazz = Class.forName(CONTROLLER, false, loader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "b" && it.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            if (method != null) {
                module.hook(method)
                    .setId("xhNativeMineSidebarControllerList")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        val result = chain.proceed()
                        if (!enabled || result !is List<*>) result
                        else filterList(result, "controller.b") ?: result
                    })
                installed++
                LogUtil.info("原生侧边菜单: 已安装 Controller.b(List):List Hook")
            } else LogUtil.warn("原生侧边菜单: Controller.b(List):List 不存在")
        } catch (e: Throwable) {
            LogUtil.warn("原生侧边菜单: Controller Hook 安装失败 " +
                e.javaClass.simpleName + ": " + e.message)
        }

        try {
            val clazz = Class.forName(COMPOSE, false, loader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "e" && Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 3 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    it.parameterTypes[1].name == "androidx.compose.runtime.Composer" &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            }
            if (method != null) {
                module.hook(method)
                    .setId("xhNativeMineSidebarComposeList")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(Hooker { chain ->
                        if (!enabled) return@Hooker chain.proceed()
                        val source = chain.getArg(0) as? List<*>
                            ?: return@Hooker chain.proceed()
                        val updated = filterList(source, "compose.e")
                            ?: return@Hooker chain.proceed()
                        val args = (chain.args as Array<Any?>).copyOf()
                        args[0] = updated
                        chain.proceed(args)
                    })
                installed++
                LogUtil.info("原生侧边菜单: 已安装 Compose.e(List,Composer,int) Hook")
            } else LogUtil.warn("原生侧边菜单: Compose.e(List,Composer,int) 不存在")
        } catch (e: Throwable) {
            LogUtil.warn("原生侧边菜单: Compose Hook 安装失败 " +
                e.javaClass.simpleName + ": " + e.message)
        }
        LogUtil.info("原生侧边菜单: 安装完成 hooks=" + installed +
            " (APK=7.3.5.32; renderer=Jetpack Compose)")
    }
}
