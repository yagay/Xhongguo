package com.yagay.xhongguo

import org.junit.Assert.*
import org.junit.Test
import com.yagay.xhongguo.ui.SettingsSchema

class SettingsSchemaTest {
    @Test fun allOriginalVisibleSettingsHaveAUniqueDefinition() {
        val items = SettingsSchema.items
        assertEquals(items.size, items.map { it.key }.distinct().size)
        val defaults = items.flatMap { SettingsSchema.defaults(it).keys }.toSet()
        assertTrue(defaults.containsAll(listOf("master_on", "system_media_playback", "in_app_mini_player",
            "in_app_continuous_playback", "audio_external_pause", "audio_external_auto_resume",
            "audio_external_resume_delay_ms", "default_speed_value", "ui_theme", "ui_density", "ui_color",
            "download_limit_one_day_episode", "download_limit_one_day_series", "download_limit_total_series")))
    }
    @Test fun modeResetIsAtomicAndDoesNotChangeSystemMedia() {
        val item = SettingsSchema.items.single { it.key == "app_playback_mode" }
        assertEquals(mapOf("in_app_continuous_playback" to false, "in_app_mini_player" to false), SettingsSchema.defaults(item))
        assertEquals(SettingsCatalog.Page.BACKGROUND, item.page)
    }
    @Test fun searchUsesMultipleTermsAndAliases() {
        assertTrue(SettingsSchema.search("小窗 保活").any { it.key == "app_playback_mode" })
        assertTrue(SettingsSchema.search("音频 恢复").any { it.key == "audio_external_auto_resume" })
        assertTrue(SettingsSchema.search("不存在的功能123").isEmpty())
    }
}
