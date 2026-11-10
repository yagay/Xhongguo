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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.app_bg))
            isVerticalScrollBarEnabled = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("‹  返回", 15, R.color.accent, true).apply {
            setPadding(0, 0, 0, dp(18))
            setOnClickListener { finish() }
        })
        root.addView(label("Xhongguo 设置", 26, R.color.text_primary, true))
        root.addView(label("直接在模块 App 中管理红果的 LSPosed 功能", 13, R.color.text_secondary).apply {
            setPadding(0, dp(5), 0, dp(15))
        })
        root.addView(label(
            "保存后会同步到红果。部分启动时安装的 Hook 需要完全关闭红果并重新打开才会生效。",
            12, R.color.text_secondary
        ).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = background(R.color.accent_soft)
        })

        section("核心")
        toggle("模块总开关", "统一启用或停用模块功能", "master_on")

        section("界面精简")
        toggle("隐藏状态栏", "视频页面沉浸显示", "status_bar")
        toggle("隐藏控件", "三键导航下保留首页底部栏和全集入口", "control_hide")
        toggle("隐藏选集相关控件", "适用于联播页面", "player_bar")
        toggle("隐藏视频进度条", "隐藏已适配的视频进度条", "progress_off")
        toggle("隐藏系统小白条", "隐藏手势导航提示条", "nav_bar_off")
        toggle("暂停后恢复控件", "暂停视频时临时恢复控件", "restore_controls_pause")

        section("播放与手势")
        toggle("自动最高画质", "使用当前视频支持的最高画质", "max_quality")
        speedSwitch = toggle("默认播放倍速", "可在下一项选择具体倍速", "default_speed")
        speedText = action("选择倍速", speedDescription()) { chooseSpeed() }
        toggle("双击打开评论", "替换默认的双击点赞", "double_tap_comment")
        toggle("禁用下拉刷新", "禁用刷新手势与提示区域", "pull_refresh")
        toggle("拦截顶部下滑", "拦截顶部区域的下滑手势", "top_zone")

        section("内容与账号")
        toggle("拦截广告与挂件", "使用现有的广告层与挂件 Hook", "ad_block")
        toggle("VIP 状态 Hook", "启用现有的 VIP 适配逻辑", "vip_unlock")
        toggle("VIP 图标", "控制 VIP 图标与挂件", "vip_icon")

        section("工具及实验功能")
        toggle("OLED 亮度保护", "实验性亮度拦截", "oled_protect")
        toggle("通知栏快捷菜单", "红果需要授予通知权限", "notification_menu")
        toggle("自定义下载数量限制", "覆盖默认的下载数量限制", "download_limit_unlimit")
        limitText = action("下载限制数值", limitDescription()) { editLimits() }

        root.addView(label(
            "原红果内设置首次启动红果后会尝试迁移。请在 LSPosed 中启用 Xhongguo 并选择相应的红果作用域。",
            12, R.color.text_secondary
        ).apply { setPadding(dp(4), dp(22), dp(4), 0) })
    }

    private fun section(name: String) {
        root.addView(label(name, 14, R.color.accent, true).apply {
            setPadding(dp(5), dp(22), 0, dp(9))
        })
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minimumHeight = dp(64)
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
    private fun toggle(name: String, sub: String, key: String): Switch {
        val row = row()
        addText(row, name, sub)
        val toggle = Switch(this).apply {
            isChecked = prefs.getBoolean(key, false)
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
