package com.yagay.xhongguo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import com.yagay.xhongguo.SettingsCatalog
import com.yagay.xhongguo.ModuleSettingsStore

/** Shared native renderer: settings behavior and layouts cannot diverge between the two hosts. */
internal class SettingsSurface(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val write: (Map<String, Any>) -> Boolean,
    private val close: () -> Unit,
    private val standalone: Boolean,
    private val tools: () -> Unit,
    private val factory: ((Context) -> CompoundButton)? = null,
    private val statusNote: () -> String = { "" }
) {
    var page = SettingsCatalog.Page.HOME
        private set
    private var searching = false
    private var query = ""
    private var searchRefresh: (() -> Unit)? = null
    private var theme = UiTheme(activity, prefs)
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var header: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val rows = linkedMapOf<String, View>()
    private val offsets = mutableMapOf<SettingsCatalog.Page, Int>()
    private val history = activity.getSharedPreferences("ui_search_history", Context.MODE_PRIVATE)
    private val favorites = activity.getSharedPreferences("ui_favorites", Context.MODE_PRIVATE)
    private val pinned = linkedSetOf("system_media_playback").apply {
        if (favorites.contains("keys")) {
            clear()
            addAll(favorites.getStringSet("keys", emptySet()).orEmpty())
        }
    }
    private fun enabled(key: String) = prefs.getBoolean(key, SettingsCatalog.definition(key).defaultEnabled)
    private fun int(key: String, fallback: Int = 0) = prefs.getInt(key, fallback)
    private fun visible(item: SettingsSchema.Item) = item.parents.all(::enabled)
    private fun set(values: Map<String, Any>, rebuild: Boolean = true): Boolean {
        if (!write(values)) {
            Toast.makeText(activity, "保存或同步失败，原设置保留", Toast.LENGTH_LONG).show()
            if (rebuild) refresh()
            return false
        }
        if (rebuild) refresh()
        return true
    }
    init { render() }
    fun refresh() {
        val pos = if (::scroll.isInitialized) scroll.scrollY else 0
        if (searching) searchRefresh?.invoke() ?: renderSearch(query)
        else { render(); scroll.post { scroll.scrollTo(0, pos) } }
    }
    fun open(target: SettingsCatalog.Page, key: String? = null) {
        if (::scroll.isInitialized && !searching) offsets[page] = scroll.scrollY
        searching = false; page = target
        render()
        scroll.post {
            val row = key?.let(rows::get)
            if (row != null) {
                val rect = android.graphics.Rect()
                row.getDrawingRect(rect)
                content.offsetDescendantRectToMyCoords(row, rect)
                scroll.smoothScrollTo(0, (rect.top - theme.dp(12)).coerceAtLeast(0))
                row.background = theme.clickBackground(theme.accentSoft)
                row.postDelayed({ row.background = theme.clickBackground(theme.surface) }, 1700L)
            } else scroll.scrollTo(0, offsets[target] ?: 0)
        }
    }
    fun back(): Boolean {
        if (searching) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            searching = false; render(); return true
        }
        if (page != SettingsCatalog.Page.HOME) { open(SettingsCatalog.Page.HOME); return true }
        return false
    }
    private fun render() {
        searchRefresh = null
        theme = UiTheme(activity, prefs)
        view.removeAllViews(); view.setBackgroundColor(theme.page)
        rows.clear()
        header = theme.row().apply {
            setPadding(theme.dp(4), 0, theme.dp(4), 0); minimumHeight = theme.dp(56)
            if (page != SettingsCatalog.Page.HOME)
                addView(theme.action("‹") { back() }.apply { contentDescription = "返回" })
            addView(theme.label(if (page == SettingsCatalog.Page.HOME) "Xhongguo" else page.title, 22, theme.text, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(theme.action("搜索") { showSearch() })
            if (!standalone) addView(theme.action("关闭", close))
        }
        view.addView(header, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = theme.column().apply { setPadding(theme.dp(12), theme.dp(4), theme.dp(12), theme.dp(20)) }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (page == SettingsCatalog.Page.HOME) home() else category()
    }
    private fun title(parent: LinearLayout, value: String) {
        parent.addView(theme.label(value, 13, theme.accent, true).apply {
            setPadding(theme.dp(10), theme.dp(14), 0, theme.dp(6))
        })
    }
    private fun group(parent: LinearLayout): LinearLayout = theme.column().apply {
        background = theme.shape(theme.surface, 18); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun separator(parent: LinearLayout) {
        if (parent.childCount == 0) return
        parent.addView(View(activity).apply { setBackgroundColor(theme.muted) },
            LinearLayout.LayoutParams(-1, theme.dp(1)).apply { marginStart = theme.dp(14); marginEnd = theme.dp(14) })
    }
    private fun entry(parent: LinearLayout, name: String, desc: String, value: String = "",
                      action: () -> Unit): LinearLayout {
        separator(parent)
        val row = theme.row().apply {
            minimumHeight = theme.dp(if (theme.compact) 52 else 64)
            setPadding(theme.dp(14), theme.dp(8), theme.dp(10), theme.dp(8))
            background = theme.clickBackground(); isFocusable = true
        }
        val text = theme.column()
        text.addView(theme.label(name, 16, theme.text, false))
        if (desc.isNotBlank()) text.addView(theme.label(desc, 12, theme.secondary))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) row.addView(theme.label(value, 13, theme.secondary).apply {
            setPadding(theme.dp(8), 0, theme.dp(2), 0)
        })
        row.setOnClickListener { action() }
        parent.addView(row)
        return row
    }
    private fun home() {
        title(content, "常用功能")
        val common = group(content)
        SettingsSchema.items.filter { it.key == "master_on" || it.key in pinned }.filter(::visible)
            .distinctBy { it.key }.forEach { addItem(common, it, false) }
        entry(common, "管理常用功能", "选择固定在首页的功能", "›") { chooseFavorites() }
        val note = statusNote()
        if (note.isNotBlank()) content.addView(theme.label(note, 12, theme.secondary).apply {
            setPadding(theme.dp(10), theme.dp(8), theme.dp(10), 0)
        })
        title(content, "功能分类")
        val categories = group(content)
        for (category in SettingsCatalog.categories.filter { it != SettingsCatalog.Page.TOOLS }) {
            val row = entry(categories, category.title, category.summary, "›") { open(category) }
            val icon = UiIcon(activity, when (category) {
                SettingsCatalog.Page.BACKGROUND -> "layers"
                SettingsCatalog.Page.AUDIO -> "audio"
                SettingsCatalog.Page.PLAYER -> "play"
                SettingsCatalog.Page.DISPLAY -> "display"
                SettingsCatalog.Page.CONTENT -> "grid"
                else -> "tune"
            }, theme.accent)
            row.addView(icon, 0, LinearLayout.LayoutParams(theme.dp(36), theme.dp(36)).apply { marginEnd = theme.dp(8) })
        }
        if (!standalone) {
            title(content, "工具")
            entry(group(content), "模块工具与运行状态", "打开 Xhongguo 独立 App", "›", tools)
        } else {
            title(content, "更多设置")
            entry(group(content), "下载设置", "下载数量及缓存限制", "›") { open(SettingsCatalog.Page.TOOLS) }
        }
    }
    private fun category() {
        val list = SettingsSchema.items.filter { it.page == page && visible(it) }
        for ((name, items) in list.groupBy { it.group }) {
            title(content, name)
            val g = group(content)
            items.forEach { item ->
                if (item.kind == SettingsSchema.Kind.MODE) {
                    rows[item.key] = g
                    for ((index, title) in listOf("关闭", "持续播放（无小窗）", "小窗播放").withIndex()) {
                        val desc = listOf("使用红果原生行为", "切换应用内页面时保留声音", "切换页面时显示视频小窗")[index]
                        val row = entry(g, title, desc) {}
                        val radio = RadioButton(theme.dialogContext()).apply {
                            isChecked = mode() == index
                            buttonTintList = ColorStateList.valueOf(theme.accent)
                            contentDescription = title
                            minimumHeight = theme.dp(48)
                            isClickable = false; isFocusable = false
                        }
                        row.addView(radio)
                        row.setOnClickListener { set(mapOf("in_app_continuous_playback" to (index == 1),
                            "in_app_mini_player" to (index == 2))) }
                    }
                } else addItem(g, item)
            }
        }
        title(content, "管理")
        entry(group(content), "重置本分类", "仅恢复此分类的默认值", "›") {
            confirmReset(SettingsSchema.items.filter { it.page == page }, "重置本分类")
        }
    }
    fun confirmReset(items: List<SettingsSchema.Item> = SettingsSchema.items, heading: String = "恢复默认设置") {
        AlertDialog.Builder(theme.dialogContext()).setTitle(heading)
            .setMessage("将恢复所选设置的默认值，不清除账号、收藏或视频缓存。")
            .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                set(items.flatMap { SettingsSchema.defaults(it).entries }.associate { it.key to it.value })
            }.show()
    }
    private fun chooseFavorites() {
        val options = SettingsSchema.items.filter { it.kind == SettingsSchema.Kind.SWITCH && it.key != "master_on" }
        val draft = options.map { it.key in pinned }.toBooleanArray()
        AlertDialog.Builder(theme.dialogContext()).setTitle("常用功能")
            .setMultiChoiceItems(options.map { it.title }.toTypedArray(), draft) { _, i, checked -> draft[i] = checked }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                pinned.clear(); pinned.addAll(options.filterIndexed { i, _ -> draft[i] }.map { it.key })
                favorites.edit().putStringSet("keys", pinned.toSet()).apply(); refresh()
            }.show()
    }
    private fun addItem(parent: LinearLayout, item: SettingsSchema.Item, remember: Boolean = true,
                        locate: (() -> Unit)? = null) {
        val desc = listOf(item.summary, item.note).filter { it.isNotBlank() }.joinToString("\n")
        if (item.kind == SettingsSchema.Kind.SWITCH) {
            val row = entry(parent, item.title, desc) {}
            val sw = factory?.invoke(activity) ?: Switch(activity).apply {
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.onAccent, theme.secondary))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.muted))
                setShowText(false)
            }
            sw.isChecked = enabled(item.key)
            sw.contentDescription = item.title
            sw.minimumHeight = theme.dp(48)
            row.addView(sw, LinearLayout.LayoutParams(-2, -2))
            sw.setOnCheckedChangeListener { _, checked ->
                if (searching) recordQuery(query)
                set(mapOf(item.key to checked))
            }
            row.setOnClickListener { sw.isChecked = !sw.isChecked }
            if (locate != null) row.addView(theme.action("›", locate).apply { contentDescription = "定位到设置" })
            if (remember) rows[item.key] = row
            return
        }
        val value = when (item.kind) {
            SettingsSchema.Kind.MODE -> arrayOf("关闭", "持续播放", "小窗播放")[mode()]
            SettingsSchema.Kind.SPEED -> "${prefs.getFloat(item.key, 1f)}×"
            SettingsSchema.Kind.DELAY -> "${ModuleSettingsStore.externalResumeDelay(prefs) / 1000.0} 秒"
            SettingsSchema.Kind.THEME -> arrayOf("跟随系统", "浅色", "深色", "纯黑")[int(item.key).coerceIn(0,3)]
            SettingsSchema.Kind.DENSITY -> arrayOf("标准", "紧凑")[int(item.key).coerceIn(0,1)]
            SettingsSchema.Kind.COLOR -> arrayOf("品牌红", "系统取色")[int(item.key).coerceIn(0,1)]
            else -> "设置"
        }
        val row = entry(parent, item.title, desc, "$value ›") { edit(item) }
        if (remember) rows[item.key] = row
    }
    private fun mode() = if (enabled("in_app_continuous_playback")) 1 else if (enabled("in_app_mini_player")) 2 else 0
    private fun choices(title: String, labels: Array<String>, checked: Int, apply: (Int) -> Unit) {
        AlertDialog.Builder(theme.dialogContext()).setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, i -> dialog.dismiss(); apply(i) }
            .setNegativeButton("取消", null).show()
    }
    private fun edit(item: SettingsSchema.Item) {
        when (item.kind) {
            SettingsSchema.Kind.MODE -> choices(item.title, arrayOf("关闭", "持续播放（无小窗）", "小窗播放"), mode()) { i ->
                set(mapOf("in_app_continuous_playback" to (i == 1), "in_app_mini_player" to (i == 2)))
            }
            SettingsSchema.Kind.SPEED -> {
                val options = SettingsSchema.speedOptions
                val selected = options.indices.minByOrNull { kotlin.math.abs(options[it] - prefs.getFloat(item.key, 1f)) } ?: 1
                choices(item.title, options.map { "${it}×" }.toTypedArray(), selected) { i -> set(mapOf(item.key to options[i])) }
            }
            SettingsSchema.Kind.DELAY -> {
                val options = ModuleSettingsStore.EXTERNAL_RESUME_DELAYS
                val selected = options.indices.minByOrNull { kotlin.math.abs(options[it] - ModuleSettingsStore.externalResumeDelay(prefs)) } ?: 0
                choices(item.title, options.map { "${it / 1000} 秒" }.toTypedArray(), selected) { i -> set(mapOf(item.key to options[i])) }
            }
            SettingsSchema.Kind.THEME -> choices(item.title, arrayOf("跟随系统", "浅色", "深色", "纯黑"), int(item.key)) { set(mapOf(item.key to it)) }
            SettingsSchema.Kind.DENSITY -> choices(item.title, arrayOf("标准", "紧凑"), int(item.key)) { set(mapOf(item.key to it)) }
            SettingsSchema.Kind.COLOR -> choices(item.title, arrayOf("品牌红", "系统取色"), int(item.key)) { set(mapOf(item.key to it)) }
            SettingsSchema.Kind.LIMITS -> editLimits()
            else -> Unit
        }
    }
    private fun editLimits() {
        val box = theme.column().apply { setPadding(theme.dp(20), 0, theme.dp(20), 0) }
        val fields = listOf("单日最大下载集数", "单日最大下载剧数", "总缓存剧数上限").mapIndexed { i, name ->
            box.addView(theme.label(name))
            EditText(theme.dialogContext()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
                setText(int(SettingsSchema.limitKeys[i], 99999).toString()); box.addView(this)
            }
        }
        val dialog = AlertDialog.Builder(theme.dialogContext()).setTitle("下载限制数值")
            .setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = fields.map { it.text.toString().toIntOrNull() }
            if (values.any { it == null || it !in 1..999_999_999 }) {
                fields.forEachIndexed { i, field -> if (values[i] == null || values[i] !in 1..999_999_999) field.error = "请输入有效整数" }
            } else if (set(SettingsSchema.limitKeys.mapIndexed { i, key -> key to values[i]!! }.toMap())) {
                dialog.dismiss()
                Toast.makeText(activity, "请重新启动红果使下载进程读取新设置", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun recordQuery(q: String) {
        if (q.isBlank()) return
        history.edit().putString("recent", (listOf(q) + history.getString("recent", "").orEmpty().split("\n"))
            .filter { it.isNotBlank() }.distinct().take(8).joinToString("\n")).apply()
    }
    fun showSearch() { searching = true; query = ""; renderSearch("") }
    private fun renderSearch(value: String) {
        theme = UiTheme(activity, prefs); view.removeAllViews(); view.setBackgroundColor(theme.page); rows.clear()
        val bar = theme.row()
        bar.addView(theme.action("‹") { back() }.apply { contentDescription = "返回" })
        val input = EditText(theme.dialogContext()).apply {
            hint = "搜索设置"; setSingleLine(true); textSize = 16f; setText(value); setSelection(text.length)
            setTextColor(theme.text); setHintTextColor(theme.secondary)
        }
        bar.addView(input, LinearLayout.LayoutParams(0, theme.dp(54), 1f))
        bar.addView(theme.action("清除") { input.setText("") })
        view.addView(bar)
        scroll = ScrollView(activity)
        content = theme.column().apply { setPadding(theme.dp(12), 0, theme.dp(12), theme.dp(16)) }
        scroll.addView(content); view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun results(q: String) {
            query = q; content.removeAllViews()
            if (q.isBlank()) {
                val recent = history.getString("recent", "").orEmpty().split("\n").filter { it.isNotBlank() }
                if (recent.isNotEmpty()) {
                    title(content, "最近搜索")
                    val recentGroup = group(content)
                    recent.forEach { s -> entry(recentGroup, s, "", "›") { input.setText(s); input.setSelection(s.length) } }
                }
            }
            val items = SettingsSchema.search(q)
            title(content, if (items.isEmpty()) "没有匹配的设置" else "搜索结果 · ${items.size}")
            val g = group(content)
            items.forEach { item ->
                val go = {
                    recordQuery(q)
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(input.windowToken, 0)
                    open(item.page, if (visible(item)) item.key else item.parents.firstOrNull { !enabled(it) })
                }
                if (item.kind == SettingsSchema.Kind.SWITCH && visible(item)) {
                    addItem(g, item.copy(summary = "${item.page.title} · ${item.summary}"), false, go)
                } else entry(g, item.title, "${item.page.title} · ${item.summary}" +
                    if (visible(item)) "" else " · 需先启用上级设置", "›", go)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { results(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchRefresh = { results(input.text.toString()) }
        results(value)
    }
}
