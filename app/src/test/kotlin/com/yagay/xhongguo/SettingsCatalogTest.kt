package com.yagay.xhongguo

import org.junit.Assert.*
import org.junit.Test

class SettingsCatalogTest {
    @Test fun audioDefaultsAndDependencies() {
        val resume = SettingsCatalog.definition("audio_external_auto_resume")
        assertTrue(resume.defaultEnabled)
        assertEquals(SettingsCatalog.Page.AUDIO, resume.page)
        assertEquals(listOf("audio_external_pause"), resume.parentKeys)
        assertEquals(listOf("audio_external_pause", "audio_external_auto_resume"),
            SettingsCatalog.definition("audio_external_resume_delay_ms").parentKeys)
    }

    @Test fun backgroundModesAreExclusive() {
        assertEquals("in_app_mini_player", SettingsCatalog.otherMode("in_app_continuous_playback"))
        assertEquals("in_app_continuous_playback", SettingsCatalog.otherMode("in_app_mini_player"))
        assertNull(SettingsCatalog.otherMode("system_media_playback"))
    }
}
