package com.yagay.xhongguo.hooks

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import com.yagay.xhongguo.LogUtil

/**
 * A conservative, reversible UI cleaner for the native Hongguo "我的" tab.
 *
 * Do not depend on obfuscated field names or fixed resource IDs. The shortcut
 * row is identified only when its THREE known labels share a compact, visible
 * container. Hide the whole row (GONE), never the entire Mine fragment.
 *
 * Ad presentation cleanup is restricted to that confirmed Mine page; generic
 * ads elsewhere are handled by their existing dedicated native Hook points.
 */
internal object MineUiCleaner {
    @Volatile private var hideShortcuts = false
    @Volatile private var hideAds = false

    private const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    private val SHORTCUTS = setOf("福利", "订单", "消息")
    private val rowStates = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val adStates = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private var minePage = WeakReference<ViewGroup>(null)
    private var lastDiagnosticAt = 0L
    private var lastAdScanAt = 0L
    private var lastHiddenRow: WeakReference<View> = WeakReference(null)

    fun configure(shortcuts: Boolean, ads: Boolean) {
        if (hideShortcuts && !shortcuts) restore(rowStates)
        if (hideAds && !ads) restore(adStates)
        hideShortcuts = shortcuts
        hideAds = ads
        if (!shortcuts && !ads) minePage.clear()
    }

    private fun restore(states: MutableMap<View, Int>) {
        val snapshot = synchronized(states) {
            states.entries.map { it.key to it.value }.also { states.clear() }
        }
        for ((view, visibility) in snapshot) {
            try {
                view.visibility = visibility
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        if (snapshot.isNotEmpty()) {
            LogUtil.info("我的页面精简: 已恢复 " + snapshot.size + " 个界面节点")
        }
    }

    private fun hide(view: View, states: MutableMap<View, Int>, reason: String) {
        if (view.visibility == View.GONE) return
        synchronized(states) {
            if (!states.containsKey(view)) states[view] = view.visibility
        }
        view.visibility = View.GONE
        view.requestLayout()
        (view.parent as? View)?.requestLayout()
        LogUtil.info("我的页面精简: 已隐藏 " + reason +
            " class=" + view.javaClass.simpleName +
            " size=" + view.width + "x" + view.height)
        LogUtil.incr("mineUiHidden")
    }

    private fun normalized(text: CharSequence?): String =
        text?.toString()?.trim()?.replace(Regex("\\s+"), "") ?: ""

    private fun isDescendant(child: View, ancestor: ViewGroup): Boolean {
        var current: View? = child
        for (i in 0..18) {
            if (current === ancestor) return true
            current = current?.parent as? View ?: return false
        }
        return false
    }

    private fun boundsMatch(group: ViewGroup, screenWidth: Int, density: Float): Boolean {
        val width = if (group.width > 0) group.width else group.measuredWidth
        val height = if (group.height > 0) group.height else group.measuredHeight
        return width >= screenWidth * .50f &&
            height >= 26 * density && height <= 220 * density &&
            group.isShown && group.isAttachedToWindow
    }

    private fun collectVisibleText(root: View, limit: Int = 1800): List<TextView> {
        val result = ArrayList<TextView>()
        val queue = java.util.ArrayDeque<View>()
        queue.addLast(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < limit) {
            val node = queue.removeFirst()
            if (node.visibility != View.VISIBLE) continue
            if (node is TextView && node.isShown) result.add(node)
            if (node is ViewGroup) {
                for (i in 0 until node.childCount)
                    queue.addLast(node.getChildAt(i))
            }
        }
        return result
    }

    private fun findShortcutRow(root: View): ViewGroup? {
        val labels = collectVisibleText(root).filter { normalized(it.text) in SHORTCUTS }
        if (labels.size < 3 || SHORTCUTS.any { desired ->
                labels.none { normalized(it.text) == desired }
            }) return null
        val width = root.resources.displayMetrics.widthPixels
        val density = root.resources.displayMetrics.density
        var best: ViewGroup? = null
        var bestArea = Long.MAX_VALUE
        for (anchor in labels) {
            var parent = anchor.parent as? ViewGroup
            for (i in 0..11) {
                val group = parent ?: break
                if (boundsMatch(group, width, density) &&
                    SHORTCUTS.all { desired ->
                        labels.any { normalized(it.text) == desired &&
                            isDescendant(it, group) }
                    }) {
                    val area = group.width.toLong() * group.height
                    if (area > 0L && area < bestArea) {
                        bestArea = area
                        best = group
                    }
                }
                parent = group.parent as? ViewGroup
            }
        }
        return best
    }

    private fun findMinePage(row: ViewGroup, screenHeight: Int, screenWidth: Int): ViewGroup? {
        var parent = row.parent as? ViewGroup
        for (i in 0..15) {
            val group = parent ?: break
            if (group.width >= screenWidth * .65f &&
                group.height >= screenHeight * .45f &&
                group.javaClass.name != "com.android.internal.policy.DecorView") {
                return group
            }
            parent = group.parent as? ViewGroup
        }
        return row.parent as? ViewGroup
    }

    private fun adLabel(text: String): Boolean {
        if (text.isBlank()) return false
        return text in setOf(
            "广告", "广告推广", "广告推荐", "赞助广告", "推广", "去看广告",
            "看广告", "观看广告", "看广告领金币", "看广告得奖励", "领红包广告",
            "广告福利", "广告奖励", "金宝箱", "看广告领福利"
        ) || (text.length <= 22 && (text.startsWith("看广告") ||
            text.startsWith("观看广告") || text.contains("广告领取")))
    }

    private fun adResource(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        val name = try { view.resources.getResourceEntryName(view.id).lowercase() }
            catch (_: Throwable) { return false }
        val tokens = name.split('_')
        return tokens.any { it in setOf("ad", "ads", "advert", "advertisement", "sponsor") } ||
            (name.contains("ad_banner") || name.contains("ad_container") ||
             name.contains("advert_view") || name.contains("gold_box_pendant"))
    }

    private fun findAdCard(node: View, page: ViewGroup): View? {
        val density = node.resources.displayMetrics.density
        val screenWidth = node.resources.displayMetrics.widthPixels
        var current: View = node
        var best: View? = null
        repeat(9) {
            val parent = current.parent as? ViewGroup ?: return@repeat
            if (!isDescendant(parent, page) || parent === page) return best
            val w = parent.width
            val h = parent.height
            if (w >= screenWidth * .45f && h >= 32f * density &&
                h <= 280f * density && parent.isShown) {
                best = parent
                if (w >= screenWidth * .70f && h >= 48f * density) return parent
            }
            current = parent
        }
        return best
    }

    private fun hideMineAds(page: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAdScanAt < 700L) return
        lastAdScanAt = now
        val visibleText = collectVisibleText(page, 1300)
        var removed = 0
        val seen = HashSet<View>()
        for (label in visibleText) {
            if (!adLabel(normalized(label.text))) continue
            val candidate = findAdCard(label, page) ?: continue
            if (!seen.add(candidate)) continue
            // Keep account, settings, history and favourites untouched even if
            // a promotion sits near these controls.
            val protected = collectVisibleText(candidate, 120).any {
                normalized(it.text) in setOf("设置", "我的收藏", "观看历史", "历史",
                    "我的", "账号与隐私", "账号设置")
            }
            if (!protected) {
                hide(candidate, adStates, "我的页面广告卡片")
                removed++
            }
        }
        // Some native banners have no accessible text; only hide a subtree
        // when the app supplied an explicit *ad resource name*.
        val queue = java.util.ArrayDeque<View>()
        queue.addLast(page)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < 1000) {
            val node = queue.removeFirst()
            if (node.visibility != View.VISIBLE) continue
            if (node !== page && adResource(node)) {
                val candidate = findAdCard(node, page) ?: node
                if (seen.add(candidate)) {
                    hide(candidate, adStates, "原生广告容器")
                    removed++
                }
                continue
            }
            if (node is ViewGroup) for (i in 0 until node.childCount)
                queue.addLast(node.getChildAt(i))
        }
        if (removed > 0) LogUtil.incr("mineAdCardsHidden")
    }

    fun scan(activity: Activity?) {
        if ((!hideShortcuts && !hideAds) || activity?.javaClass?.name != MAIN ||
            activity.isFinishing || activity.isDestroyed) return
        try {
            val decor = activity.window?.decorView ?: return
            val row = findShortcutRow(decor)
            if (row != null) {
                val page = findMinePage(row, decor.resources.displayMetrics.heightPixels,
                    decor.resources.displayMetrics.widthPixels)
                if (page != null) minePage = WeakReference(page)
                if (hideShortcuts) {
                    lastHiddenRow = WeakReference(row)
                    hide(row, rowStates, "福利 / 订单 / 消息整排")
                }
            } else {
                val now = SystemClock.uptimeMillis()
                if (lastHiddenRow.get() == null && now - lastDiagnosticAt > 12000L) {
                    lastDiagnosticAt = now
                    LogUtil.info("我的页面精简: 未找到同时包含福利/订单/消息的紧凑整排，跳过以免误删")
                }
            }
            val page = minePage.get()
            if (hideAds && page != null && page.isShown && page.isAttachedToWindow) {
                hideMineAds(page)
            }
        } catch (e: Throwable) {
            LogUtil.warn("我的页面精简: 扫描失败 " +
                e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun restoreAll() {
        restore(rowStates)
        restore(adStates)
        minePage.clear()
    }
}
