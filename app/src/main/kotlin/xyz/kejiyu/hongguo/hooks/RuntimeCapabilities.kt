package xyz.kejiyu.hongguo.hooks

import xyz.kejiyu.hongguo.LogUtil

/**
 * Runtime binding contract for changing Hongguo APKs.
 *
 * Only stable, semantic framework/SDK class names live here. Do NOT copy
 * obfuscated holder names or resource integers from a previous release.
 * Presence is evidence that a hook can be attempted, not proof that the
 * feature works. Each hook still installs and reports independently.
 */
internal object RuntimeCapabilities {
    data class Result(val name: String, val available: Boolean, val evidence: String)

    private fun load(loader: ClassLoader, name: String): Class<*>? =
        try { Class.forName(name, false, loader) } catch (_: Throwable) { null }

    private fun hasMethod(type: Class<*>?, name: String, count: Int? = null): Boolean {
        if (type == null) return false
        return try {
            (type.methods.asSequence() + type.declaredMethods.asSequence())
                .any { it.name == name && (count == null || it.parameterCount == count) }
        } catch (_: Throwable) { false }
    }

    private fun hasField(type: Class<*>?, name: String): Boolean =
        try { type?.getDeclaredField(name) != null }
        catch (_: Throwable) { false }

    /** Called once on host process attach. Never scans every class/dex at runtime. */
    fun inspect(loader: ClassLoader, packageName: String, version: String?): List<Result> {
        val videoContext = load(loader, "com.ss.android.videoshop.context.VideoContext")
        val videoLayer = load(loader, "com.ss.android.videoshop.mediaview.LayerHostMediaLayout")
        val entity = load(loader, "com.ss.android.videoshop.entity.PlayEntity")
        val engine = load(loader, "com.ss.ttvideoengine.TTVideoEngine")
        val resolution = load(loader, "com.ss.ttvideoengine.Resolution")
        val seriesActivity = load(loader,
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")
        val seriesFragment = load(loader,
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment")
        val data = load(loader,
            "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData")
        val detail = load(loader,
            "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel")
        val settings = load(loader,
            "com.dragon.read.component.biz.impl.mine.settings.SettingsActivity")
        val kmpSettings = load(loader,
            "com.dragon.read.component.biz.impl.mine.settings.KmpSettingsActivity")
        val download = load(loader, "com.dragon.read.base.ssconfig.SsConfigMgr")
        val a = listOf(
            Result("Android framework UI/lifecycle", true,
                "Activity, View, Window, Resources"),
            Result("VideoShop playback and background",
                hasMethod(videoContext, "getVideoContext") &&
                    hasMethod(videoContext, "isPlaying", 0) &&
                    hasMethod(videoContext, "pause", 0),
                "VideoContext#getVideoContext/isPlaying/pause"),
            Result("VideoShop play entity",
                hasMethod(videoContext, "getPlayEntity", 0) &&
                    hasMethod(entity, "getVideoId", 0),
                "VideoContext#getPlayEntity / PlayEntity#getVideoId"),
            Result("VideoShop event controller",
                hasMethod(videoLayer, "onVideoPlay") &&
                    hasMethod(videoLayer, "onVideoPause"),
                "LayerHostMediaLayout#onVideoPlay/onVideoPause"),
            Result("TTVideoEngine timeline",
                hasMethod(engine, "getCurrentPlaybackTime", 0) ||
                    hasMethod(engine, "getCurrentPlaybackTime"),
                "TTVideoEngine current playback time"),
            Result("TTVideoEngine video identity",
                hasMethod(engine, "getVideoID", 0), "TTVideoEngine#getVideoID"),
            Result("Native playback resolution",
                resolution != null && (hasMethod(videoContext, "setResolution") ||
                    hasMethod(engine, "setResolution")),
                "VideoShop/TTVideoEngine#setResolution"),
            Result("Native speed control",
                hasMethod(engine, "setPlaybackParams") ||
                    hasMethod(engine, "setSpeed") ||
                    hasMethod(videoContext, "setPlayBackParams"),
                "engine/context playback parameters"),
            Result("Full-series current episode",
                seriesActivity != null && hasMethod(seriesFragment, "getCurrentPosition", 0) &&
                    hasMethod(seriesFragment, "getDataList", 0),
                "ShortSeriesSingleFragment#getCurrentPosition/getDataList"),
            Result("Official series title/poster",
                hasField(detail, "episodesId") &&
                    hasField(detail, "episodesTitle") &&
                    hasField(detail, "episodesCover") &&
                    hasField(data, "seriesName"),
                "SaasVideoDetailModel / SaasVideoData semantic fields"),
            Result("Settings screen",
                settings != null || kmpSettings != null,
                "native SettingsActivity/KmpSettingsActivity (injection separately verified)"),
            Result("Download limit config",
                hasMethod(download, "getABValueJson"),
                "SsConfigMgr#getABValueJson"),
        )
        LogUtil.info("通用能力识别: package=$packageName version=" +
            (version ?: "未知") + " verified=" + a.count { it.available } + "/" + a.size)
        for (c in a) {
            LogUtil.info("通用能力: " + c.name + "=" +
                (if (c.available) "可尝试" else "接口未发现") + " | " + c.evidence)
        }
        return a
    }
}
