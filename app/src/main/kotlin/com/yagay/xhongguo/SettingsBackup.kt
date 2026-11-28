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

    fun restore(activity: Activity, uri: Uri) {
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
        val prefs = ModuleSettingsStore.prefs(activity)
        val editor = prefs.edit()
        val allowed = ModuleSettingsStore.BOOLEANS + ModuleSettingsStore.INTS + ModuleSettingsStore.FLOATS
        allowed.forEach { editor.remove(it) }
        for (key in allowed) {
            if (!values.has(key)) continue
            val value = values.get(key)
            when (key) {
                in ModuleSettingsStore.BOOLEANS -> {
                    require(value is Boolean) { "无效开关: $key" }
                    editor.putBoolean(key, value)
                }
                in ModuleSettingsStore.INTS -> {
                    require(value is Number) { "无效数值: $key" }
                    val n = value.toInt()
                    require(value.toDouble() == n.toDouble() && ModuleSettingsStore.validInt(key, n)) { "数值超出范围: $key" }
                    editor.putInt(key, n)
                }
                in ModuleSettingsStore.FLOATS -> {
                    require(value is Number && value.toFloat().isFinite() &&
                        value.toFloat() in 0.75f..3f) { "无效倍速" }
                    editor.putFloat(key, value.toFloat())
                }
            }
        }
        if (values.optBoolean("in_app_continuous_playback") && values.optBoolean("in_app_mini_player"))
            editor.putBoolean("in_app_mini_player", false)
        // Keep the configuration authoritative even if a backup has no overrides.
        editor.putBoolean("_backup_restored", true)
        check(editor.commit()) { "保存设置失败" }
        ModuleSettingsStore.notifyChanged(activity)
    }
}
