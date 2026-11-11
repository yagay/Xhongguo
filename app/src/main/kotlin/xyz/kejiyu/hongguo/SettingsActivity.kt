package xyz.kejiyu.hongguo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Standalone settings UI. Nothing needs to be opened inside the target app. */
class SettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var currentGroup: SettingsCatalog.Group? = null
    private val prefs by lazy { ModuleSettingsStore.prefs(this) }
    private var speedText: TextView? = null
    private var speedSwitch: Switch? = null
    private var limitText: TextView? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun color(id: Int) = getColor(id)
    private fun background(id: Int) = GradientDrawable().apply {
        setColor(color(id))
        cornerRadius = dp(16).toFloat()
    }
    private fun label(value: String, size: Int, colorId: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color(colorId))
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.app_bg)
        window.navigationBarColor = color(R.color.app_bg)
        scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.app_bg))
            isVerticalScrollBarEnabled = false
            isFillViewport = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)
        currentGroup = SettingsCatalog.groups.firstOrNull {
            it.id == savedInstanceState?.getString("settings_group")
        }
        renderSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settings_group", currentGroup?.id)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Android back press")
    override fun onBackPressed() {
        if (currentGroup != null) {
            currentGroup = null
            renderSettings()
        } else super.onBackPressed()
    }

    private fun renderSettings() {
        root.removeAllViews()
        speedText = null
        speedSwitch = null
        limitText = null

        val group = currentGroup
        root.addView(label(if (group == null) "‹ 返回" else "‹ 设置分类",
            14, R.color.accent, true).apply {
            setPadding(0, 0, 0, dp(12))
            setOnClickListener {
                if (currentGroup == null) finish()
                else {
                    currentGroup = null
                    renderSettings()
                }
            }
        })
        root.addView(label(group?.title ?: "Xhongguo 设置",
            24, R.color.text_primary, true))
        root.addView(label(
            group?.detail ?: "常用设置在首页，其余选项按功能分类",
            12, R.color.text_secondary
        ).apply { setPadding(0, dp(4), 0, dp(14)) })

        if (group == null) {
            toggle(SettingsCatalog.master.title,
                SettingsCatalog.master.detail, SettingsCatalog.master.key)
            root.addView(label(
                "设置直接同步到红果；无需离开当前模块页面。",
                12, R.color.text_secondary
            ).apply { setPadding(dp(4), dp(11), dp(4), dp(7)) })
            SettingsCatalog.groups.forEach { category ->
                val container = row()
                val icon = label(category.icon, 23, R.color.accent, true).apply {
                    gravity = Gravity.CENTER
                }
                container.addView(icon, LinearLayout.LayoutParams(
                    dp(38), dp(40)).apply { rightMargin = dp(7) })
                addText(container, category.title, category.detail)
                container.addView(label("›", 24, R.color.text_secondary))
                container.setOnClickListener {
                    currentGroup = category
                    renderSettings()
                }
                append(container)
            }
        } else {
            var subsection = ""
            for (item in group.items) {
                if (item.subsection.isNotBlank() && item.subsection != subsection) {
                    subsection = item.subsection
                    section(subsection)
                }
                when (item.kind) {
                    SettingsCatalog.Kind.SWITCH -> {
                        val sw = toggle(item.title, item.detail, item.key,
                            item.defaultValue)
                        if (item.key == "default_speed") speedSwitch = sw
                    }
                    SettingsCatalog.Kind.SPEED ->
                        speedText = action(item.title, speedDescription()) { chooseSpeed() }
                    SettingsCatalog.Kind.DOWNLOAD_VALUES ->
                        limitText = action(item.title, limitDescription()) { editLimits() }
                    SettingsCatalog.Kind.DIAGNOSTICS ->
                        action(item.title, item.detail) {
                            ModuleDiagnostics.chooseDestination(this)
                        }
                }
            }
            root.addView(label(
                "部分 Hook 更改需强制停止红果并重新打开后生效。",
                11, R.color.text_secondary
            ).apply { setPadding(dp(4), dp(20), dp(4), 0) })
        }
        scroll.scrollTo(0, 0)
    }

    @Deprecated("Android document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (ModuleDiagnostics.onActivityResult(this, requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun section(name: String) {
        root.addView(label(name, 14, R.color.accent, true).apply {
            setPadding(dp(5), dp(22), 0, dp(9))
        })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13), dp(9), dp(13), dp(9))
        minimumHeight = dp(54)
        background = background(R.color.surface)
        isClickable = true
        isFocusable = true
    }

    private fun addText(row: LinearLayout, title: String, sub: String): TextView {
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(label(title, 15, R.color.text_primary, true))
        val summary = label(sub, 12, R.color.text_secondary).apply {
            setPadding(0, dp(4), dp(6), 0)
        }
        stack.addView(summary)
        row.addView(stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return summary
    }

    private fun append(row: View) {
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7) })
    }

    @Suppress("DEPRECATION")
    private fun toggle(name: String, sub: String, key: String, defaultValue: Boolean = false): Switch {
        val row = row()
        addText(row, name, sub)
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                if (!ModuleSettingsStore.save(this@SettingsActivity, key, checked)) {
                    Toast.makeText(this@SettingsActivity, "保存设置失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        append(row)
        return toggle
    }

    private fun action(name: String, sub: String, click: () -> Unit): TextView {
        val row = row()
        val summary = addText(row, name, sub)
        row.addView(label("›", 23, R.color.text_secondary))
        row.setOnClickListener { click() }
        append(row)
        return summary
    }

    private val speeds = floatArrayOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
    private fun speedDescription(): String =
        "当前 " + prefs.getFloat("default_speed_value", 1f).toString() + "×"

    private fun chooseSpeed() {
        val current = prefs.getFloat("default_speed_value", 1f)
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - current) } ?: 1
        val labels = speeds.map { it.toString() + "×" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("默认播放倍速")
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                ModuleSettingsStore.save(this, "default_speed_value", speeds[index])
                ModuleSettingsStore.save(this, "default_speed", true)
                speedSwitch?.isChecked = true
                speedText?.text = speedDescription()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val limitKeys = arrayOf(
        "download_limit_one_day_episode", "download_limit_one_day_series",
        "download_limit_total_series"
    )
    private fun limitDescription(): String =
        "单日集数 " + prefs.getInt(limitKeys[0], 99999) +
        " · 单日剧数 " + prefs.getInt(limitKeys[1], 99999) +
        " · 总缓存 " + prefs.getInt(limitKeys[2], 99999)

    private fun editLimits() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        val fields = arrayOf("单日最大下载集数", "单日最大下载剧数", "总缓存剧数上限")
            .mapIndexed { index, name ->
                form.addView(label(name, 13, R.color.text_secondary).apply {
                    setPadding(0, dp(12), 0, dp(4))
                })
                EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setText(prefs.getInt(limitKeys[index], 99999).toString())
                    form.addView(this)
                }
            }
        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义下载数量限制")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = fields.map { it.text.toString().trim().toIntOrNull() }
            if (values.any { it == null || it !in 1..999_999_999 }) {
                Toast.makeText(this, "请输入 1～999999999 的整数", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            values.forEachIndexed { index, value ->
                ModuleSettingsStore.save(this, limitKeys[index], value!!)
            }
            limitText?.text = limitDescription()
            Toast.makeText(this, "已保存。重新启动红果后下载进程生效", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }
    }
}
