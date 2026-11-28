package com.yagay.xhongguo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Portable, allowlisted preferences only. No account, media URLs or credentials. */
internal object SettingsBackup {
    const val EXPORT = 0x7101
    const val IMPORT = 0x7102
    private const val MAX_BYTES = 65536

    fun choose(activity: Activity, restore: Boolean) {
        val intent = Intent(if (restore) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            if (!restore) putExtra(Intent.EXTRA_TITLE, "Xhongguo-settings.json")
        }
        activity.startActivityForResult(intent, if (restore) IMPORT else EXPORT)
    }

    fun export(activity: Activity, uri: Uri) {
        val prefs = ModuleSettingsStore.prefs(activity)
        val values = JSONObject()
        for ((key, value) in prefs.all) {
            if (key in ModuleSettingsStore.BOOLEANS || key in ModuleSettingsStore.INTS ||
                key in ModuleSettingsStore.FLOATS) values.put(key, value)
        }
        val data = JSONObject().put("schema", 1).put("settings", values).toString(2)
        activity.contentResolver.openOutputStream(uri, "w")?.use {
            it.write(data.toByteArray(Charsets.UTF_8))
        } ?: error("无法打开备份文件")
    }

    fun read(activity: Activity, uri: Uri): Map<String, Any> {
        val data = activity.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_BYTES) { "备份文件过大" }
                out.write(buffer, 0, count)
            }
            out.toString("UTF-8")
        } ?: error("无法打开备份文件")
        val json = JSONObject(data)
        require(json.optInt("schema") == 1) { "不支持的备份版本" }
        val values = json.getJSONObject("settings")
        val result = linkedMapOf<String, Any>()
        val allowed = ModuleSettingsStore.BOOLEANS + ModuleSettingsStore.INTS + ModuleSettingsStore.FLOATS
        for (key in allowed) {
            if (!values.has(key)) continue
            val value = values.get(key)
            when (key) {
                in ModuleSettingsStore.BOOLEANS -> {
                    require(value is Boolean) { "无效开关: $key" }
                    result[key] = value
                }
                in ModuleSettingsStore.INTS -> {
                    require(value is Number) { "无效数值: $key" }
                    val n = value.toInt()
                    require(value.toDouble() == n.toDouble() && ModuleSettingsStore.validInt(key, n)) { "数值超出范围: $key" }
                    result[key] = n
                }
                in ModuleSettingsStore.FLOATS -> {
                    require(value is Number && value.toFloat().isFinite() &&
                        value.toFloat() in 0.75f..3f) { "无效倍速" }
                    result[key] = value.toFloat()
                }
            }
        }
        if (values.optBoolean("in_app_continuous_playback") && values.optBoolean("in_app_mini_player"))
            result["in_app_mini_player"] = false
        return result
    }
    fun diff(activity: Activity, values: Map<String, Any>): List<String> {
        val current = ModuleSettingsStore.prefs(activity).all
        val names = com.yagay.xhongguo.ui.SettingsSchema.items.associate { it.key to it.title }
        val keys = ModuleSettingsStore.BOOLEANS + ModuleSettingsStore.INTS + ModuleSettingsStore.FLOATS
        return keys.filter { current[it] != values[it] }.map {
            "${names[it] ?: it}: ${current[it] ?: "默认"} → ${values[it] ?: "默认"}"
        }
    }
    fun apply(activity: Activity, values: Map<String, Any>) {
        val editor = ModuleSettingsStore.prefs(activity).edit()
        (ModuleSettingsStore.BOOLEANS + ModuleSettingsStore.INTS + ModuleSettingsStore.FLOATS).forEach { editor.remove(it) }
        for ((key, value) in values) when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
        }
        editor.putBoolean("_backup_restored", true)
        check(editor.commit()) { "保存设置失败" }
        ModuleSettingsStore.notifyChanged(activity)
    }
    fun restore(activity: Activity, uri: Uri) = apply(activity, read(activity, uri))
}
