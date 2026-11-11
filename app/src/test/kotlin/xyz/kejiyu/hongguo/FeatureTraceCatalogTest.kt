package xyz.kejiyu.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureTraceCatalogTest {
    @Test fun everyImportantSettingMapsToAFeature() {
        assertEquals("media", FeatureTraceCatalog.forSetting("system_media_playback"))
        assertEquals("mini", FeatureTraceCatalog.forSetting("in_app_mini_player"))
        assertEquals("mine", FeatureTraceCatalog.forSetting("mine_hide_wallet"))
        assertEquals("quality", FeatureTraceCatalog.forSetting("max_quality"))
        assertEquals("downloads", FeatureTraceCatalog.forSetting("download_limit_unlimit"))
    }

    @Test fun hotPathCountersAreIgnoredButFeatureCountersAreTraced() {
        assertNull(FeatureTraceCatalog.forCounter("inflate"))
        assertNull(FeatureTraceCatalog.forCounter("scanTree"))
        assertEquals("artwork", FeatureTraceCatalog.forCounter("systemMediaArtworkApplied"))
        assertEquals("background", FeatureTraceCatalog.forCounter("systemMediaAppSwitchRecovery"))
        assertEquals("mini", FeatureTraceCatalog.forCounter("inAppMiniDock"))
        assertEquals("fail", FeatureTraceCatalog.outcomeForCounter("systemMediaNotificationFailed"))
    }

    @Test fun vipAdAndDownloadHooksAreClassified() {
        assertEquals("ads", FeatureTraceCatalog.forHookId("vipIsVip_0"))
        assertEquals("ads", FeatureTraceCatalog.forHookId("veAd_0"))
        assertEquals("downloads", FeatureTraceCatalog.forHookId("downloadLimitAB_0"))
        assertNull(FeatureTraceCatalog.forHookId("inf_17"))
    }
}
