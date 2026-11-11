package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.FeatureTrace

/**
 * Scope-limited My-page cleanup for the official Hongguo APK.
 *
 * Two renderers coexist in 7.3.5.32:
 *  - classic HongguoMine/VariantMine fragments with native Views
 *  - KmpHongguoMineFragment + Compose, whose text is NOT a TextView
 *
 * Data models are filtered BEFORE the Compose renderer and classic Views
 * are hidden only within a VERIFIED mine fragment / its sidebar. Never
 * remove an Activity, strip a parent navigation tab, or modify unrelated
 * feed, theater, comment or drama-detail screens.
 */
internal object MinePageCleaner {
    enum class Area { MAIN, SIDEBAR }

    data class Entry(
        val key: String,
        val title: String,
        val area: Area,
        val aliases: Set<String> = setOf(title)
    )

    val mainEntries = listOf(
        Entry("mine_hide_welfare", "福利", Area.MAIN),
        Entry("mine_hide_orders", "订单", Area.MAIN),
        Entry("mine_hide_messages", "消息", Area.MAIN),
        Entry("mine_hide_games", "游戏", Area.MAIN),
        Entry("mine_hide_following", "追更", Area.MAIN),
    )
    val sidebarEntries = listOf(
        Entry("mine_hide_wallet", "我的钱包", Area.SIDEBAR,
            setOf("我的钱包", "钱包")),
        Entry("mine_hide_membership", "会员中心", Area.SIDEBAR),
        Entry("mine_hide_game_center", "游戏中心", Area.SIDEBAR),
        Entry("mine_hide_creator_center", "创作中心", Area.SIDEBAR),
    )
    val allEntries = mainEntries + sidebarEntries

    @Volatile private var enabled = false
    @Volatile private var hidden: Set<String> = emptySet()
    private val roots = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )
    @Volatile private var visibleMine = WeakReference<Any>(null)
    private data class Saved(val visibility: Int, val enabled: Boolean,
        val clickable: Boolean)
    private val saved = Collections.synchronizedMap(
        WeakHashMap<View, Saved>()
    )
    private val hiddenClickRoots = Collections.synchronizedMap(
        WeakHashMap<View, Boolean>()
    )
    private var lastScanAt = 0L

    fun configure(prefs: SharedPreferences, master: Boolean) {
        val oldEnabled = enabled
        val oldHidden = hidden
        enabled = master
        hidden = if (!master) emptySet()
        else allEntries.filter { prefs.getBoolean(it.key, true) }
            .mapTo(linkedSetOf()) { it.key }
        if (oldEnabled != enabled || oldHidden != hidden)
            FeatureTrace.event("mine", "policy", "changed",
                "enabled=" + enabled + ",hidden=" + hidden.size)
    }

    private fun disabled(area: Area): List<Entry> =
        if (!enabled) emptyList()
        else allEntries.filter { it.area == area && it.key in hidden }

    private fun blockedLabel(text: String?, area: Area): Entry? {
        val value = text?.trim()?.replace('　', ' ') ?: return null
        return disabled(area).firstOrNull { value in it.aliases }
    }

    /** Stable API enum, not obfuscated method names:
     * com.dragon.read.kmp.mine.config.JgZoneItemId
     */
    private fun blockedZoneId(value: Any?): Entry? {
        if (value !is Enum<*> ||
            value.javaClass.name !=
            "com.dragon.read.kmp.mine.config.JgZoneItemId") return null
        val key = when (value.name) {
            "Gold" -> "mine_hide_welfare"
            "Order" -> "mine_hide_orders"
            "Message" -> "mine_hide_messages"
            "GameCenter" -> "mine_hide_games"
            "FollowUpdate" -> "mine_hide_following"
            else -> return null
        }
        return if (enabled && key in hidden) allEntries.firstOrNull {
            it.key == key
        } else null
    }

    fun blocksZoneAction(zone: Any?): Boolean = blockedZoneId(zone) != null

    private fun fields(item: Any): Sequence<java.lang.reflect.Field> = sequence {
        var cl: Class<*>? = item.javaClass
        var depth = 0
        while (cl != null && cl != Any::class.java && depth++ < 3) {
            for (f in cl.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers))
                    yield(f)
            }
            cl = cl.superclass
        }
    }

    private fun value(item: Any, field: java.lang.reflect.Field): Any? =
        try {
            field.isAccessible = true
            field.get(item)
        } catch (_: Throwable) { null }

    /** Match a single item, never its entire parent section or arbitrary
     * nested content. The DTO exposes myTabCellName; Compose item models
     * contain either a direct JgZoneItemId or a direct String label.
     * This prevents deleting a whole list due to one nested hidden item. */
    private fun blockedItem(item: Any?, area: Area): Entry? {
        if (!enabled || item == null) return null
        if (item is CharSequence) return blockedLabel(item.toString(), area)
        if (item is Enum<*> && area == Area.MAIN) return blockedZoneId(item)
        val cl = item.javaClass.name
        if (cl.startsWith("android.") || cl.startsWith("androidx.") ||
            cl.startsWith("java.") || cl.startsWith("kotlinx.")) return null
        if (!cl.startsWith("com.dragon.read.") &&
            !cl.startsWith("gr5.") && !cl.startsWith("fr5.")) return null

        if (area == Area.MAIN) {
            if (cl == "gr5.g") {
                val typed = fields(item).firstOrNull {
                    it.type.name == "com.dragon.read.kmp.mine.config.JgZoneItemId"
                }?.let { value(item, it) }
                blockedZoneId(typed)?.let { return it }
            }
        }
        for (f in fields(item)) {
            if (f.type == String::class.java ||
                CharSequence::class.java.isAssignableFrom(f.type)) {
                blockedLabel(value(item, f)?.toString(), area)?.let { return it }
            }
            if (area == Area.MAIN &&
                f.type.name == "com.dragon.read.kmp.mine.config.JgZoneItemId") {
                blockedZoneId(value(item, f))?.let { return it }
            }
        }
        return null
    }

    /** Used by semantic native List endpoints (KMP sidebar/repository and
     * classic Mine function list). Lists of unrelated model types are passed
     * through unchanged. Never mutate an adapter's live list in place. */
    fun filterList(list: List<*>?, area: Area, source: String): List<*>? {
        if (list == null || !enabled || hidden.isEmpty()) return list
        var removed = 0
        val retained = ArrayList<Any?>(list.size)
        for (item in list) {
            val blocked = blockedItem(item, area)
            if (blocked != null) {
                removed++
                LogUtil.incr("mineEntryDataFiltered")
            } else retained.add(item)
        }
        if (removed == 0) return list
        LogUtil.info("我的页面精简: 数据入口已过滤" +
            " source=$source area=$area count=$removed")
        FeatureTrace.event("mine", "data_filter", "committed",
            "area=" + area + ",removed=" + removed + ",remaining=" +
                retained.size, minIntervalMs = 750L)
        return retained
    }

    /** Official KMP main-page models contain List<JgZoneItem> fields.
     * Inspect only direct fields with precisely the expected item type.
     * Fields are written back only when a list was actually filtered. */
    fun filterKmpMineState(model: Any?, source: String) {
        if (model == null || !enabled || hidden.isEmpty()) return
        for (f in fields(model)) {
            if (!List::class.java.isAssignableFrom(f.type)) continue
            val list = value(model, f) as? List<*> ?: continue
            if (list.none { it?.javaClass?.name == "gr5.g" }) continue
            val result = filterList(list, Area.MAIN, source + ".state")
            if (result !== list && result != null) {
                try {
                    f.isAccessible = true
                    f.set(model, result)
                    LogUtil.incr("mineKmpStateFiltered")
                } catch (e: Throwable) {
                    LogUtil.warn("我的页面精简: KMP 列表写入失败 " +
                        e.javaClass.simpleName)
                    FeatureTrace.event("mine", "kmp_write", "fail",
                        "errorType=" + e.javaClass.simpleName)
                }
            }
        }
    }

    /** MyTabCellData has native fields myTabCellName / myTabSubCell*.
     * Filter a parent DTO's own nested list; never erase the whole parent
     * just because a child contains a disabled entry. */
    fun filterRpcCellContainer(dto: Any?, area: Area): Int {
        if (dto == null || !enabled || hidden.isEmpty()) return 0
        if (dto.javaClass.name !in setOf(
            "com.dragon.read.rpc.model.GetMyTabPlanRespData",
            "com.dragon.read.rpc.model.MyTabCellData"
        )) return 0
        var changed = 0
        for (f in fields(dto)) {
            if (f.name !in setOf("cellViewList", "myTabSubCell",
                    "myTabSubCellData")) continue
            val items = value(dto, f) as? List<*> ?: continue
            val result = filterList(items, area, "rpc." + f.name)
            if (result !== items && result != null) {
                try {
                    f.isAccessible = true
                    f.set(dto, result)
                    changed++
                } catch (_: Throwable) {}
            }
        }
        return changed
    }

    fun rememberMineView(fragment: Any?, view: View?) {
        if (fragment == null || view == null) return
        roots[fragment] = WeakReference(view)
        view.post { scan(view, Area.MAIN) }
    }

    fun onMineVisible(fragment: Any?) {
        if (fragment == null) return
        visibleMine = WeakReference(fragment)
        roots[fragment]?.get()?.let { root ->
            root.post { scan(root, Area.MAIN) }
        }
    }

    fun onMineInvisible(fragment: Any?) {
        if (visibleMine.get() === fragment) visibleMine.clear()
    }

    fun isMineVisible(): Boolean {
        val f = visibleMine.get() ?: return false
        val root = roots[f]?.get() ?: return false
        return root.isAttachedToWindow && root.isShown
    }

    /** Do not scan other tabs or other Activity windows for short labels
     * ("消息", "游戏" etc). Only the registered native Mine fragment root
     * may hide those. A right-side menu is scanned separately and only
     * when the Mine fragment is visibly active. */
    fun scanVisibleMine(activity: Activity?, force: Boolean = false) {
        if (!enabled || activity?.javaClass?.name !=
            "com.dragon.read.pages.main.MainFragmentActivity") return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastScanAt < 800L) return
        lastScanAt = now
        val fragment = visibleMine.get() ?: return
        val mineRoot = roots[fragment]?.get() ?: return
        if (!mineRoot.isAttachedToWindow || !mineRoot.isShown) return
        scan(mineRoot, Area.MAIN)
        // Do not hide a single "会员中心" tile elsewhere on Mine as
        // though it were the right sidebar. Only scan native sidebar text
        // when a visible menu contains at least two known sidebar labels.
        // Compose sidebars are filtered at the model/List boundary instead.
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val labels = linkedSetOf<String>()
        val pending = ArrayDeque<View>()
        pending.add(decor)
        var count = 0
        while (pending.isNotEmpty() && count++ < 1600) {
            val view = pending.removeFirst()
            if (!view.isShown) continue
            if (view is TextView) {
                val t = view.text?.toString()?.trim().orEmpty()
                if (sidebarEntries.any { t in it.aliases }) labels.add(t)
            }
            if (view is ViewGroup && view.childCount < 90)
                for (i in 0 until view.childCount) pending.addLast(view.getChildAt(i))
        }
        if (labels.size >= 2) scan(decor, Area.SIDEBAR)
    }

    private fun pickRow(text: TextView, root: View, area: Area): View {
        val maxHeight = (text.resources.displayMetrics.density * 150f).toInt()
        var candidate: View = text
        var cur: View = text
        var depth = 0
        while (depth++ < 4) {
            val parent = cur.parent as? ViewGroup ?: break
            val h = if (parent.height > 0) parent.height else parent.measuredHeight
            if (parent === root || h <= 0 || h > maxHeight) break

            // Never hide a whole five-icon quick-access row when the user
            // toggles just one item. A candidate container must contain only
            // the ONE entry being removed; otherwise use its smaller child.
            var matchingLabels = 0
            val nodes = ArrayDeque<Pair<View, Int>>()
            nodes.add(parent to 0)
            while (nodes.isNotEmpty() && matchingLabels < 2) {
                val (node, nest) = nodes.removeFirst()
                if (node is TextView &&
                    allEntries.any { node.text?.toString()?.trim() in it.aliases })
                    matchingLabels++
                if (node is ViewGroup && nest < 3 && node.childCount < 20)
                    for (i in 0 until node.childCount)
                        nodes.addLast(node.getChildAt(i) to (nest + 1))
            }
            if (matchingLabels > 1) break
            if (parent.isClickable || parent.childCount in 1..5)
                candidate = parent
            cur = parent
            if (parent.isClickable) break
        }
        return candidate
    }

    private fun hideNative(view: View, entry: Entry) {
        if (!enabled || entry.key !in hidden) return
        synchronized(saved) {
            if (!saved.containsKey(view)) saved[view] =
                Saved(view.visibility, view.isEnabled, view.isClickable)
        }
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
            view.isEnabled = false
            view.isClickable = false
            hiddenClickRoots[view] = true
            LogUtil.info("我的页面精简: 原生入口已隐藏 " +
                entry.area + "/" + entry.title)
            LogUtil.incr("mineEntryViewHidden")
            FeatureTrace.event("mine", "view_hide", "committed",
                "area=" + entry.area + ",item=" + entry.key,
                minIntervalMs = 1000L)
        }
    }

    private fun scan(root: View, area: Area) {
        if (!enabled || disabled(area).isEmpty()) return
        val pending = ArrayDeque<View>()
        pending.add(root)
        var inspected = 0
        while (pending.isNotEmpty() && inspected++ < 1800) {
            val v = pending.removeFirst()
            if (v is TextView && v.isShown) {
                val entry = blockedLabel(v.text?.toString(), area)
                if (entry != null) hideNative(pickRow(v, root, area), entry)
            }
            if (v is ViewGroup && v.childCount < 90) {
                for (i in 0 until v.childCount) pending.addLast(v.getChildAt(i))
            }
        }
    }

    fun blocksNativeClick(view: View?): Boolean {
        if (!enabled || view == null) return false
        var cur: View? = view
        repeat(6) {
            if (cur == null) return false
            if (hiddenClickRoots[cur] == true) return true
            cur = cur?.parent as? View
        }
        return false
    }

    /** User can turn each switch off without restarting the whole app. */
    fun refresh(activity: Activity?) {
        val toRestore = synchronized(saved) {
            saved.entries.map { it.key to it.value }
        }
        for ((view, state) in toRestore) {
            try {
                view.visibility = state.visibility
                view.isEnabled = state.enabled
                view.isClickable = state.clickable
            } catch (_: Throwable) {}
        }
        saved.clear()
        hiddenClickRoots.clear()
        lastScanAt = 0L
        scanVisibleMine(activity, force = true)
        FeatureTrace.event("mine", "restore_and_rescan", "committed",
            "restored=" + toRestore.size, minIntervalMs = 1000L)
    }
}
