package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import xyz.kejiyu.hongguo.LogUtil

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
    private val RIGHT_MENU_ITEMS = setOf("创作中心", "我的钱包", "会员中心", "游戏中心")
    private val PROTECTED_MENU_ITEMS = setOf(
        "设置", "账号与隐私", "账号设置", "我的收藏", "观看历史",
        "帮助与反馈", "意见反馈", "客服中心", "个人主页", "我的"
    )
    private val rowStates = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val adStates = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val menuStates = Collections.synchronizedMap(WeakHashMap<View, Int>())

    private data class SavedGeometry(
        val width: Int,
        val height: Int,
        val minimumHeight: Int,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?
    )
    private val savedGeometry = Collections.synchronizedMap(
        WeakHashMap<View, SavedGeometry>()
    )
    private var minePage = WeakReference<ViewGroup>(null)
    private var lastDiagnosticAt = 0L
    private var lastAdScanAt = 0L
    private var lastHiddenRow: WeakReference<View> = WeakReference(null)

    fun configure(shortcuts: Boolean, ads: Boolean) {
        if (hideShortcuts && !shortcuts) {
            restore(rowStates)
            restore(menuStates)
        }
        if (hideAds && !ads) restore(adStates)
        hideShortcuts = shortcuts
        hideAds = ads
        if (!shortcuts && !ads) minePage.clear()
    }

    private fun rememberGeometry(view: View) {
        val params = view.layoutParams ?: return
        synchronized(savedGeometry) {
            if (savedGeometry.containsKey(view)) return
            val margins = params as? ViewGroup.MarginLayoutParams
            savedGeometry[view] = SavedGeometry(
                params.width, params.height, view.minimumHeight,
                margins?.leftMargin, margins?.topMargin,
                margins?.rightMargin, margins?.bottomMargin
            )
        }
    }

    private fun zeroGeometry(view: View) {
        rememberGeometry(view)
        val params = view.layoutParams
        if (params != null) {
            params.height = 0
            if (params is ViewGroup.MarginLayoutParams) {
                params.leftMargin = 0
                params.topMargin = 0
                params.rightMargin = 0
                params.bottomMargin = 0
            }
            view.layoutParams = params
        }
        view.minimumHeight = 0
    }

    private fun restoreGeometry(view: View) {
        val saved = synchronized(savedGeometry) {
            savedGeometry.remove(view)
        } ?: return
        val params = view.layoutParams
        if (params != null) {
            params.width = saved.width
            params.height = saved.height
            if (params is ViewGroup.MarginLayoutParams) {
                if (saved.leftMargin != null) params.leftMargin = saved.leftMargin
                if (saved.topMargin != null) params.topMargin = saved.topMargin
                if (saved.rightMargin != null) params.rightMargin = saved.rightMargin
                if (saved.bottomMargin != null) params.bottomMargin = saved.bottomMargin
            }
            view.layoutParams = params
        }
        view.minimumHeight = saved.minimumHeight
    }

    private fun restore(states: MutableMap<View, Int>) {
        val snapshot = synchronized(states) {
            states.entries.map { it.key to it.value }.also { states.clear() }
        }
        // The item was hidden before its one-child wrapper. Restore the
        // wrapper first to avoid displaying half of a recycled item.
        for ((view, visibility) in snapshot.asReversed()) {
            try {
                restoreGeometry(view)
                view.visibility = visibility
                view.requestLayout()
                (view.parent as? View)?.requestLayout()
            } catch (_: Throwable) {}
        }
        if (snapshot.isNotEmpty()) {
            LogUtil.info("我的页面精简: 已恢复 " + snapshot.size + " 个界面节点及原始占位")
        }
    }

    private fun collapseEmptyWrappers(
        hidden: View,
        states: MutableMap<View, Int>,
        reason: String
    ) {
        var item = hidden
        val density = hidden.resources.displayMetrics.density
        val screenHeight = hidden.resources.displayMetrics.heightPixels
        repeat(4) {
            val parent = item.parent as? ViewGroup ?: return
            // A ScrollView, RecyclerView or a full-page root must NEVER be
            // collapsed. Their child positions are owned by the system.
            val type = parent.javaClass.name
            if (type.contains("RecyclerView") ||
                type.contains("ScrollView") ||
                type.contains("ViewPager") ||
                type.contains("DecorView") ||
                parent.height > minOf(screenHeight * .40f, 340f * density))
                return

            // Only collapse the wrapper if no actual visible sibling remains.
            // Thin decorative divider/spacer views do not count as content.
            val substantiveSibling = (0 until parent.childCount)
                .map { parent.getChildAt(it) }
                .any { child ->
                    child !== item && child.visibility == View.VISIBLE &&
                        child.height > 7f * density && child.width > 7f * density
                }
            if (substantiveSibling) return
            if (parent.visibility != View.GONE) {
                synchronized(states) {
                    if (!states.containsKey(parent)) states[parent] = parent.visibility
                }
                zeroGeometry(parent)
                parent.visibility = View.GONE
                LogUtil.info("我的页面精简: 收起空白外层容器 " +
                    reason + " class=" + parent.javaClass.simpleName)
                LogUtil.incr("mineUiWrapperCollapsed")
            }
            item = parent
        }
    }

    private fun hide(view: View, states: MutableMap<View, Int>, reason: String) {
        if (view.visibility == View.GONE) return
        synchronized(states) {
            if (!states.containsKey(view)) states[view] = view.visibility
        }
        val originalHeight = view.height
        zeroGeometry(view)
        view.visibility = View.GONE
        collapseEmptyWrappers(view, states, reason)
        view.requestLayout()
        (view.parent as? View)?.requestLayout()
        LogUtil.info("我的页面精简: 已移除占位 " + reason +
            " class=" + view.javaClass.simpleName +
            " originalHeight=" + originalHeight)
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

    /**
     * The hamburger menu is often an overlay Window, not part of the
     * MainFragmentActivity's decorView. Scope these exact four labels to a
     * menu root where at least two of them are visible simultaneously.
     * This prevents removing the same-named buttons on unrelated pages.
     */
    private fun findRightMenuItem(
        label: TextView,
        rootLabels: List<TextView>
    ): ViewGroup? {
        val density = label.resources.displayMetrics.density
        val screenWidth = label.resources.displayMetrics.widthPixels
        var current = label.parent as? ViewGroup
        var candidate: ViewGroup? = null
        repeat(8) {
            val group = current ?: return@repeat
            val width = group.width
            val height = group.height
            if (group.isShown && width >= .28f * screenWidth &&
                width <= screenWidth * 1.02f &&
                height >= 28f * density && height <= 116f * density) {
                val otherTarget = rootLabels.any { other ->
                    other !== label && isDescendant(other, group) &&
                        normalized(other.text) in RIGHT_MENU_ITEMS
                }
                val protectedLabel = rootLabels.any { other ->
                    other !== label && isDescendant(other, group) &&
                        normalized(other.text) in PROTECTED_MENU_ITEMS
                }
                if (!otherTarget && !protectedLabel) {
                    // Prefer the outermost single-entry row. It includes the
                    // icon, its margins and background, not just the TextView.
                    candidate = group
                }
            }
            current = group.parent as? ViewGroup
        }
        return candidate
    }

    private fun cleanRightMenu(roots: Collection<View>) {
        if (!hideShortcuts) return
        val processed = HashSet<View>()
        var removed = 0
        for (root in roots) {
            if (!root.isAttachedToWindow || !root.isShown) continue
            val labels = collectVisibleText(root, 1600)
            val targets = labels.filter { normalized(it.text) in RIGHT_MENU_ITEMS }
            val distinct = targets.map { normalized(it.text) }.distinct()
            if (distinct.size < 2) continue
            for (label in targets) {
                val item = findRightMenuItem(label, labels) ?: continue
                if (!processed.add(item)) continue
                hide(item, menuStates, "右侧菜单 " + normalized(label.text))
                removed++
            }
        }
        if (removed > 0) {
            LogUtil.info("我的页面精简: 右侧菜单本轮移除 " + removed +
                " 项 (创作中心/我的钱包/会员中心/游戏中心)")
            LogUtil.incr("mineRightMenuItemsHidden")
        }
    }

    fun scan(activity: Activity?, windows: Collection<View>) {
        if ((!hideShortcuts && !hideAds) || activity?.javaClass?.name != MAIN ||
            activity.isFinishing || activity.isDestroyed) return
        try {
            val decor = activity.window?.decorView ?: return
            cleanRightMenu(windows)
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
        restore(menuStates)
        restore(rowStates)
        restore(adStates)
        minePage.clear()
    }
}
