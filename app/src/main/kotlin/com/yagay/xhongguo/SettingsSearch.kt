package com.yagay.xhongguo

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

internal object SettingsSearch {
    data class Item(val key: String, val title: String, val description: String,
        val page: SettingsCatalog.Page)
    fun show(context: Context, items: List<Item>, selected: (Item) -> Unit) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(context).apply { hint = "输入功能名称或关键词"; setSingleLine(true) }
        box.addView(input)
        val list = ListView(context)
        box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val dialog = AlertDialog.Builder(context).setTitle("搜索设置")
            .setView(box).setNegativeButton("取消", null).create()
        var matched = items
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter
        fun filter(query: String) {
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            matched = items.filter { item ->
                val haystack = "${item.title} ${item.description} ${item.page.title} ${item.key}"
                terms.all { haystack.contains(it, ignoreCase = true) }
            }
            adapter.clear()
            adapter.addAll(matched.map { "${it.title} · ${it.page.title}" })
            adapter.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            matched.getOrNull(position)?.let { dialog.dismiss(); selected(it) }
        }
        filter("")
        dialog.show()
        dialog.window?.setLayout(-1, (context.resources.displayMetrics.heightPixels * 0.70f).toInt())
    }
}
