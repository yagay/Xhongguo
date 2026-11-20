package xyz.kejiyu.hongguo.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.util.ArrayList
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook

/**
 * Red Packet / Hongguo CN 7.3.5.32, inspected directly from classes5.dex.
 *
 * The "我的" hamburger drawer contains a ComposeView created by
 * HongguoMineFunctionFragment. Its visible sidebar uses:
 *
 *   MineSidebarController.e(): List<SidebarFunctionItemModel>
 *   MineSidebarController.g():
 *      list = e()
 *      p (MutableStateFlow).setValue(new wr5.c(list))
 *   MineSidebarPageKt.b(Controller.q, ...): Compose sidebar
 *
 * In earlier experiments, filtering Fragment.kh(List) and
 * MineSidebarPageKt.e(List,...) removed four elements from intermediate
 * lists, but Controller.g() could republish the unfiltered source. We only
 * filter the controller's real source and verify the *published* StateFlow.
 *
 * APK-verified model: com.dragon.read.kmp.mine.model.n
 *   b -> cell_name ; c -> jump_url ; d/e -> icon URLs (never match icons).
 *
 * No Android View hiding, LayoutParams mutation, background media, or other
 * app lifecycle hooks. The original menu is not changed when disabled.
 */
internal object MineDrawerDataFilter {
    @Volatile var enabled = false

    private const val CONTROLLER =
        "com.dragon.read.kmp.mine.sidebar.MineSidebarController"
    private const val MODEL = "com.dragon.read.kmp.mine.model.n"
    private const val WRAPPER = "wr5.c"
    private const val FRAGMENT =
        "com.dragon.read.component.biz.impl.mine.functionpage.HongguoMineFunctionFragment"

    private val blockedNames = setOf("创作中心", "我的钱包", "会员中心", "游戏中心")
    private val blockedRoutes = setOf(
        "creator_center", "creatorcenter",
        "wallet_center", "walletcenter",
        "vip_center", "vipcenter",
        "game_center", "gamecenter"
    )

    private var generatedCalls = 0L
    private var publishCalls = 0L
    private var removedTotal = 0L
    private var lastNoMatchAt = 0L

    private fun textField(model: Any, fieldName: String): String? {
        return try {
            val field = model.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(model) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldRemove(model: Any): Boolean {
        if (model.javaClass.name != MODEL) return false
        val name = textField(model, "b")
            ?.trim()?.replace(" ", "")?.replace("\u3000", "").orEmpty()
        if (name in blockedNames) return true
        val url = textField(model, "c")?.lowercase().orEmpty()
        return blockedRoutes.any { token -> token in url }
    }

    private data class FilterResult(
        val value: List<*>,
        val matchedModels: Int,
        val removed: Int,
        val names: Set<String>
    )

    /** Handles both flat models and nested model groups without mutating source. */
    private fun filtered(source: List<*>, depth: Int = 0): FilterResult {
        if (depth > 3 || source.size > 200)
            return FilterResult(source, 0, 0, emptySet())
        val target = ArrayList<Any?>(source.size)
        val names = LinkedHashSet<String>()
        var seen = 0
        var removed = 0

        for (entry in source) {
            when {
                entry != null && entry.javaClass.name == MODEL -> {
                    seen++
                    if (shouldRemove(entry)) {
                        removed++
                        textField(entry, "b")?.let { names.add(it.take(24)) }
                    } else target.add(entry)
                }
                entry is List<*> -> {
                    val nested = filtered(entry, depth + 1)
                    seen += nested.matchedModels
                    removed += nested.removed
                    names.addAll(nested.names)
                    if (nested.removed == 0 || nested.value.isNotEmpty()) {
                        target.add(nested.value)
                    }
                }
                else -> target.add(entry)
            }
        }
        return FilterResult(if (removed > 0) target else source, seen, removed, names)
    }

    private fun logFilter(result: FilterResult, originalSize: Int, source: String) {
        if (result.removed > 0) {
            removedTotal += result.removed
            LogUtil.info("我的右侧菜单数据过滤: " + source +
                " input=" + originalSize +
                " output=" + result.value.size +
                " modelCount=" + result.matchedModels +
                " removed=" + result.removed +
                " labels=" + result.names.joinToString(",") +
                " cumulativeRemoved=" + removedTotal)
            LogUtil.incr("mineDrawerDataItemsRemoved")
        } else {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastNoMatchAt > 8000L) {
                lastNoMatchAt = now
                LogUtil.info("我的右侧菜单数据过滤: " + source +
                    " no-match input=" + originalSize +
                    " modelCount=" + result.matchedModels +
                    " firstType=" + result.value.firstOrNull()?.javaClass?.name +
                    " enabled=" + enabled)
                LogUtil.incr("mineDrawerDataNoMatch")
            }
        }
    }

    fun install(module: MainHook, loader: ClassLoader) {
        try {
            val cls = Class.forName(CONTROLLER, false, loader)
            val build = cls.declaredMethods.singleOrNull {
                it.name == "e" && it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            val publish = cls.declaredMethods.singleOrNull {
                it.name == "g" && it.parameterTypes.isEmpty() &&
                    it.returnType == Void.TYPE
            }
            if (build == null || publish == null) {
                LogUtil.warn("我的右侧菜单数据过滤: APK Controller.e()/g() 签名不匹配，停止安装")
                return
            }

            val wrapper = Class.forName(WRAPPER, false, loader)
            val content = wrapper.getDeclaredField("a").apply { isAccessible = true }
            val constructor = wrapper.getDeclaredConstructor(List::class.java).apply {
                isAccessible = true
            }
            val flowField = cls.getDeclaredField("p").apply { isAccessible = true }
            val flowClass = Class.forName("kotlinx.coroutines.flow.MutableStateFlow", false, loader)
            val getValue = flowClass.getMethod("getValue")
            val setValue = flowClass.getMethod("setValue", Any::class.java)

            module.hook(build)
                .setId("xhMineDrawerControllerBuild")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val original = chain.proceed()
                    if (!enabled || original !is List<*>) return@Hooker original
                    val result = filtered(original)
                    generatedCalls++
                    logFilter(result, original.size, "Controller.e()")
                    result.value
                })

            // g() is the single verified publisher to the StateFlow actually
            // consumed by the Compose drawer. Verify it after the native call.
            // Only repair if another path still supplied blocked model entries.
            module.hook(publish)
                .setId("xhMineDrawerControllerPublishedState")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val outcome = chain.proceed()
                    if (!enabled) return@Hooker outcome
                    publishCalls++
                    try {
                        val flow = flowField.get(chain.thisObject)
                        val state = if (flow == null) null else getValue.invoke(flow)
                        val list = if (state != null && wrapper.isInstance(state))
                            content.get(state) as? List<*> else null
                        if (list == null) {
                            LogUtil.warn("我的右侧菜单数据过滤: g() 发布结构不符" +
                                " flow=" + flow?.javaClass?.name +
                                " state=" + state?.javaClass?.name)
                            return@Hooker outcome
                        }
                        val result = filtered(list)
                        if (result.removed > 0 && flow != null) {
                            setValue.invoke(flow, constructor.newInstance(result.value))
                            logFilter(result, list.size, "Controller.g() final StateFlow")
                            LogUtil.incr("mineDrawerPublishedStateRepaired")
                        }
                        val after = if (flow == null) null else getValue.invoke(flow)
                        val afterList = if (after != null && wrapper.isInstance(after))
                            content.get(after) as? List<*> else null
                        val blockedAfter = afterList?.let { filtered(it).removed } ?: -1
                        LogUtil.info("我的右侧菜单数据过滤: 最终 StateFlow 核验" +
                            " buildCalls=" + generatedCalls +
                            " publishedCalls=" + publishCalls +
                            " originalSize=" + list.size +
                            " finalSize=" + (afterList?.size ?: -1) +
                            " remainingBlocked=" + blockedAfter)
                        LogUtil.incr("mineDrawerPublishedStateChecked")
                    } catch (e: Throwable) {
                        LogUtil.warn("我的右侧菜单数据过滤: g() 核验失败 " +
                            e.javaClass.simpleName + ": " + e.message)
                    }
                    outcome
                })

            // An observation-only hook confirms that the user has opened
            // the correct "我的 -> 三横线" right-slide Fragment.
            try {
                val fragment = Class.forName(FRAGMENT, false, loader)
                val contentMethod = fragment.declaredMethods.firstOrNull {
                    it.name == "onCreateContent" &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == android.view.LayoutInflater::class.java &&
                        it.parameterTypes[1] == android.view.ViewGroup::class.java &&
                        it.parameterTypes[2] == android.os.Bundle::class.java
                }
                if (contentMethod != null) {
                    module.hook(contentMethod)
                        .setId("xhMineDrawerRightSlideObserved")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(Hooker { chain ->
                            val output = chain.proceed()
                            LogUtil.info("我的右侧菜单数据过滤: 真实右侧 Fragment 已打开" +
                                " enabled=" + enabled +
                                " generated=" + generatedCalls +
                                " published=" + publishCalls +
                                " class=" + chain.thisObject.javaClass.name)
                            LogUtil.incr("mineDrawerRightSlideOpened")
                            output
                        })
                }
            } catch (e: Throwable) {
                LogUtil.warn("我的右侧菜单数据过滤: Fragment 观察安装失败 " +
                    e.javaClass.simpleName)
            }

            LogUtil.info("我的右侧菜单数据过滤: APK 7.3.5.32 " +
                "Controller.e/g + StateFlow 已安装")
        } catch (e: Throwable) {
            LogUtil.warn("我的右侧菜单数据过滤: 安装失败 " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }
}
