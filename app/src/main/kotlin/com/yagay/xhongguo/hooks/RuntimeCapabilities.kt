package com.yagay.xhongguo.hooks

import com.yagay.xhongguo.LogUtil

/** Exact JVM contracts, checked independently on each installed host version. */
internal object RuntimeCapabilities {
    data class Result(val name: String, val available: Boolean, val evidence: String)

    fun inspect(loader: ClassLoader, packageName: String, version: String?): List<Result> {
        val c = NativeHookContract
        fun type(name: String) = c.load(loader, name)
        fun method(name: String, sig: NativeHookContract.Signature) = c.has(type(name), sig)
        fun m(owner: String, name: String, result: String, vararg args: String) =
            method(owner, c.sig(name, result, *args))
        val engine = type(c.ENGINE)
        val ctx = type(c.CONTEXT)
        val controller = type(c.CONTROLLER)
        val detail = type(c.DETAIL)
        val data = type(c.VIDEO)
        val result = listOf(
            Result("Android framework UI/lifecycle", true, "Activity, View, Window, Resources"),
            Result("Native transport", c.engineTransport.all { c.has(engine, it) },
                "TTVideoEngine play/start/pause/stop/release/releaseAsync ()V"),
            Result("VideoShop playback and background", c.has(ctx,
                c.sig("getVideoContext", c.CONTEXT, "android.content.Context", static = true)) &&
                m(c.CONTEXT, "isPlaying", "boolean") && m(c.CONTEXT, "pause", "void"),
                "VideoContext#getVideoContext/isPlaying/pause"),
            Result("VideoShop play entity", m(c.CONTEXT, "getPlayEntity", c.ENTITY) &&
                m(c.ENTITY, "getVideoId", "java.lang.String"), "VideoContext#getPlayEntity / PlayEntity#getVideoId"),
            Result("VideoShop event controller", c.contextEvents.take(3).all { c.has(ctx, it) },
                "VideoContext verified playback callbacks"),
            Result("Native playback confirmation", c.controllerState.all { c.has(controller, it) },
                "VideoController#onPlaybackStateChanged(TTVideoEngine,int)/onRenderStart(TTVideoEngine)"),
            Result("TTVideoEngine timeline", m(c.ENGINE, "getCurrentPlaybackTime", "int") &&
                m(c.ENGINE, "getDuration", "int"), "TTVideoEngine#getCurrentPlaybackTime/getDuration"),
            Result("TTVideoEngine video identity", m(c.ENGINE, "getVideoID", "java.lang.String") &&
                m(c.ENGINE, "getPlaybackState", "int"), "TTVideoEngine#getVideoID/getPlaybackState"),
            Result("Native playback resolution", m(c.ENGINE, "getVideoModel", c.MODEL) &&
                m(c.ENGINE, "getCurrentResolution", c.RESOLUTION) &&
                method(c.ENGINE, c.sig("findMaxQualityResolution", c.RESOLUTION, c.MODEL,
                    c.RESOLUTION, static = true)) && m(c.ENGINE, "configResolution", "void", c.RESOLUTION),
                "TTVideoEngine verified resolution API"),
            Result("Native speed control", m(c.PARAMS, "setSpeed", c.PARAMS, "float") &&
                m(c.ENGINE, "setPlaybackParams", "void", c.PARAMS), "PlaybackParams#setSpeed / TTVideoEngine#setPlaybackParams"),
            Result("Full-series current episode", type(c.SERIES) != null &&
                m(c.FRAGMENT, "getCurrentPosition", "int") && m(c.FRAGMENT, "getDataList", "java.util.List"),
                "ShortSeriesSingleFragment#getCurrentPosition/getDataList"),
            Result("Official series title/poster", listOf("episodesId", "episodesTitle", "episodesCover").all {
                c.hasField(detail, it, "java.lang.String")
            } && listOf("vid", "seriesName", "cover").all { c.hasField(data, it, "java.lang.String") },
                "SaasVideoDetailModel / SaasVideoData exact field types"),
            Result("Settings screen", listOf("SettingsActivity", "KmpSettingsActivity").any {
                m("com.dragon.read.component.biz.impl.mine.settings.$it", "onCreate", "void", "android.os.Bundle")
            }, "native SettingsActivity/KmpSettingsActivity"),
            Result("Recycler item binding", c.methods(type(c.HOLDER), c.recyclerBind).isNotEmpty(),
                "AbsRecyclerViewHolder verified onBind overloads"),
            Result("Bottom navigation", m(c.BOTTOM, "setBottomTabBackground", "void", "int"),
                "BottomTabFrameLayout#setBottomTabBackground(int)"),
            Result("Download limit config", method("com.dragon.read.base.ssconfig.SsConfigMgr",
                c.sig("getABValueJson", "java.lang.String", "java.lang.String", "java.lang.String", static = true)),
                "SsConfigMgr#getABValueJson exact two-string overload")
        )
        LogUtil.info("通用能力识别: package=$packageName version=" + (version ?: "未知") +
            " verified=" + result.count { it.available } + "/" + result.size +
            " referenceAPK=" + c.APK_VERSION)
        result.forEach { LogUtil.info("通用能力: " + it.name + "=" +
            (if (it.available) "签名匹配（待运行验证）" else "接口未发现") + " | " + it.evidence) }
        return result
    }
}
