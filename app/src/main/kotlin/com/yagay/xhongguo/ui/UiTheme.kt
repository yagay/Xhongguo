package com.yagay.xhongguo.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView

/** No app resource IDs here: also safe to construct with the target Activity. */
internal class UiTheme(val context: Context, prefs: SharedPreferences?) {
    val mode = prefs?.getInt("ui_theme", 0) ?: 0
    val compact = prefs?.getInt("ui_density", 0) == 1
    val dark = mode == 2 || mode == 3 || mode == 0 &&
        (context.resources.configuration.uiMode and 0x30) == 0x20
    val page = if (mode == 3) Color.BLACK else if (dark) 0xff141416.toInt() else 0xfffaf8f7.toInt()
    val surface = if (mode == 3) 0xff111113.toInt() else if (dark) 0xff202024.toInt() else Color.WHITE
    val muted = if (dark) 0xff2c2b30.toInt() else 0xfff0eeec.toInt()
    val text = if (dark) 0xfff3eff0.toInt() else 0xff201b1a.toInt()
    val secondary = if (dark) 0xffc1b8b7.toInt() else 0xff625b5a.toInt()
    val accent = if (prefs?.getInt("ui_color", 0) == 1 && Build.VERSION.SDK_INT >= 31)
        runCatching {
            context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_700)
        }.getOrDefault(brand()) else brand()
    val onAccent = if (dark) Color.BLACK else Color.WHITE
    val accentSoft = if (dark) 0xff382c2c.toInt() else 0xfff8e5df.toInt()
    private fun brand() = if (dark) 0xffffb4a0.toInt() else 0xffaa3926.toInt()
    fun dp(value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()
    fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun clickBackground(color: Int = surface, radius: Int = 12) =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x24ffffff else 0x16000000),
            shape(color, radius), shape(Color.WHITE, radius))
    fun label(value: String, size: Int = 14, tint: Int = text, bold: Boolean = false) =
        TextView(context).apply {
            this.text = value; textSize = size.toFloat(); setTextColor(tint)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun action(value: String, onClick: () -> Unit): TextView = label(value, 14, accent, true).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(48); minimumWidth = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = clickBackground(page); isFocusable = true; setOnClickListener { onClick() }
    }
    fun window(window: Window) {
        window.statusBarColor = page; window.navigationBarColor = page
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    fun dialogContext(): Context = android.view.ContextThemeWrapper(context,
        if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
}
