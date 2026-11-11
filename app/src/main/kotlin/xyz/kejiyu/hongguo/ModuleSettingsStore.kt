package xyz.kejiyu.hongguo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** One module-owned settings file, shared with the hooked target via an allowlisted provider. */
object ModuleSettingsStore {
    const val PREF_FILE = "lspilot_kejiyu"
    val URI: Uri = Uri.parse("content://xyz.kejiyu.hongguo.settings/config")
    val BOOLEANS = setOf(
        "master_on", "status_bar", "control_hide", "player_bar", "ad_block",
        "pull_refresh", "top_zone", "nav_bar_off", "progress_off",
        "restore_controls_pause", "vip_unlock", "vip_icon", "max_quality",
        "default_speed", "double_tap_comment", "oled_protect",
        "notification_menu", "download_limit_unlimit", "video_longpress_settings",
        "disable_shopping_mall", "in_app_mini_player", "system_media_playback",
        "diagnostic_verbose",
        // All nine My-page entries are hidden by default. Each can be
        // restored independently in either module settings surface.
        "mine_hide_welfare", "mine_hide_orders", "mine_hide_messages",
        "mine_hide_games", "mine_hide_following",
        "mine_hide_wallet", "mine_hide_membership", "mine_hide_game_center",
        "mine_hide_creator_center"
    )
    val FLOATS = setOf("default_speed_value")
    val INTS = setOf(
        "download_limit_one_day_episode", "download_limit_one_day_series",
        "download_limit_total_series"
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun notifyChanged(ctx: Context) { ctx.contentResolver.notifyChange(URI, null) }

    fun save(ctx: Context, key: String, value: Any): Boolean {
        val editor = prefs(ctx).edit()
        when {
            key in BOOLEANS && value is Boolean -> editor.putBoolean(key, value)
            key in FLOATS && value is Float && value.isFinite() -> editor.putFloat(key, value)
            key in INTS && value is Int && value in 1..999_999_999 -> editor.putInt(key, value)
            else -> return false
        }
        val ok = editor.commit()
        if (ok) {
            if (value is Boolean) FeatureTrace.setting(key, value, "module")
            else FeatureTrace.event(
                FeatureTraceCatalog.forSetting(key),
                "setting", "changed", "key=" + key + ",kind=number")
            notifyChanged(ctx)
        } else {
            FeatureTrace.event("settings", "save", "fail", "key=" + key)
        }
        return ok
    }

    fun pushFromTarget(ctx: Context, key: String, value: Any): Boolean {
        val data = Bundle().apply {
            putString("key", key)
            when (value) {
                is Boolean -> putBoolean("b", value)
                is Float -> putFloat("f", value)
                is Int -> putInt("i", value)
                else -> return false
            }
        }
        return try {
            ctx.contentResolver.call(URI, "set", null, data)?.getBoolean("ok") == true
        } catch (_: Throwable) { false }
    }

    fun importIfEmpty(ctx: Context, old: Map<String, *>): Boolean {
        val data = Bundle()
        BOOLEANS.forEach { (old[it] as? Boolean)?.let { v -> data.putBoolean(it, v) } }
        FLOATS.forEach { (old[it] as? Float)?.let { v -> data.putFloat(it, v) } }
        INTS.forEach { (old[it] as? Int)?.let { v -> data.putInt(it, v) } }
        if (data.isEmpty) return false
        return try {
            ctx.contentResolver.call(URI, "import", null, data)?.getBoolean("ok") == true
        } catch (_: Throwable) { false }
    }

    /** Mirror to target-local prefs so legacy XML readers observe the same settings. */
    fun pullIntoTarget(ctx: Context, local: SharedPreferences) {
        val data = try {
            ctx.contentResolver.call(URI, "snapshot", null, null)
        } catch (_: Throwable) { null } ?: return
        if (!data.getBoolean("_configured", false)) return
        val values = local.all
        val edit = local.edit()
        var dirty = false
        BOOLEANS.forEach { key ->
            if (data.containsKey(key)) {
                val v = data.getBoolean(key)
                if (values[key] != v) { edit.putBoolean(key, v); dirty = true }
            } else if (values.containsKey(key)) { edit.remove(key); dirty = true }
        }
        FLOATS.forEach { key ->
            if (data.containsKey(key)) {
                val v = data.getFloat(key)
                if (values[key] != v) { edit.putFloat(key, v); dirty = true }
            } else if (values.containsKey(key)) { edit.remove(key); dirty = true }
        }
        INTS.forEach { key ->
            if (data.containsKey(key)) {
                val v = data.getInt(key)
                if (values[key] != v) { edit.putInt(key, v); dirty = true }
            } else if (values.containsKey(key)) { edit.remove(key); dirty = true }
        }
        if (dirty) edit.commit()
    }
}

class ModuleSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun enforceTrustedCaller(ctx: Context) {
        if (Binder.getCallingUid() == ctx.applicationInfo.uid) return
        val pkgs = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (pkgs.none { it == "com.phoenix.read" || it == "com.phoenix.read.oversea.gp" }) {
            throw SecurityException("Xhongguo settings provider: caller not allowed")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return Bundle()
        enforceTrustedCaller(ctx)
        val prefs = ModuleSettingsStore.prefs(ctx)
        return when (method) {
            "snapshot" -> Bundle().apply {
                putBoolean("_configured", prefs.all.isNotEmpty())
                ModuleSettingsStore.BOOLEANS.forEach {
                    if (prefs.contains(it)) putBoolean(it, prefs.getBoolean(it, false))
                }
                ModuleSettingsStore.FLOATS.forEach {
                    if (prefs.contains(it)) putFloat(it, prefs.getFloat(it, 1f))
                }
                ModuleSettingsStore.INTS.forEach {
                    if (prefs.contains(it)) putInt(it, prefs.getInt(it, 99999))
                }
            }
            "set" -> {
                val key = extras?.getString("key").orEmpty()
                val editor = prefs.edit()
                val valid = when {
                    key in ModuleSettingsStore.BOOLEANS && extras?.containsKey("b") == true -> {
                        editor.putBoolean(key, extras.getBoolean("b")); true
                    }
                    key in ModuleSettingsStore.FLOATS && extras?.containsKey("f") == true -> {
                        val v = extras.getFloat("f")
                        if (v.isFinite()) { editor.putFloat(key, v); true } else false
                    }
                    key in ModuleSettingsStore.INTS && extras?.containsKey("i") == true -> {
                        val v = extras.getInt("i")
                        if (v in 1..999_999_999) { editor.putInt(key, v); true } else false
                    }
                    else -> false
                }
                val ok = valid && editor.commit()
                if (ok) ModuleSettingsStore.notifyChanged(ctx)
                Bundle().apply { putBoolean("ok", ok) }
            }
            "import" -> {
                val editor = prefs.edit()
                var count = 0
                if (extras != null) {
                    ModuleSettingsStore.BOOLEANS.forEach { key ->
                        if (extras.containsKey(key) && !prefs.contains(key)) { editor.putBoolean(key, extras.getBoolean(key)); count++ }
                    }
                    ModuleSettingsStore.FLOATS.forEach { key ->
                        if (extras.containsKey(key) && !prefs.contains(key) && extras.getFloat(key).isFinite()) {
                            editor.putFloat(key, extras.getFloat(key)); count++
                        }
                    }
                    ModuleSettingsStore.INTS.forEach { key ->
                        val v = extras.getInt(key, -1)
                        if (extras.containsKey(key) && !prefs.contains(key) && v in 1..999_999_999) {
                            editor.putInt(key, v); count++
                        }
                    }
                }
                val ok = count > 0 && editor.commit()
                if (ok) ModuleSettingsStore.notifyChanged(ctx)
                Bundle().apply { putBoolean("ok", ok) }
            }
            else -> super.call(method, arg, extras)
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
