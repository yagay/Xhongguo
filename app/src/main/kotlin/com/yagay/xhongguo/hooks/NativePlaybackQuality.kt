package com.yagay.xhongguo.hooks

import java.lang.ref.WeakReference
import com.yagay.xhongguo.LogUtil

/** Only the confirmed current video, using actual supported resolutions. */
internal object NativePlaybackQuality {
    private var appliedEngine = WeakReference<Any>(null)
    private var appliedGeneration = -1L
    private var appliedVideoId: String? = null
    fun reset() {
        appliedEngine.clear()
        appliedGeneration = -1L
        appliedVideoId = null
    }
    fun apply(): Boolean {
        val engine = NativePlaybackSession.engine() ?: return false
        if (!NativePlaybackSession.playing(engine)) return false
        val generation = NativePlaybackSession.generation()
        val videoId = NativePlaybackSession.videoId(engine)
        if (appliedEngine.get() === engine && appliedGeneration == generation &&
            appliedVideoId == videoId) return true
        return try {
            val loader = engine.javaClass.classLoader ?: return false
            val modelType = Class.forName("com.ss.ttvideoengine.model.VideoModel", false, loader)
            val resolution = Class.forName("com.ss.ttvideoengine.Resolution", false, loader)
            val engineType = Class.forName("com.ss.ttvideoengine.TTVideoEngine", false, loader)
            val model = engineType.getMethod("getVideoModel").invoke(engine) ?: return false
            if (!modelType.isInstance(model)) return false
            val current = engineType.getMethod("getCurrentResolution").invoke(engine) ?: return false
            val supported = modelType.getMethod("getSupportResolutions").invoke(model) as? Array<*>
                ?: return false
            if (supported.isEmpty()) return false
            val highest = engineType.getMethod("findMaxQualityResolution", modelType, resolution)
                .invoke(null, model, current) ?: return false
            if (!resolution.isInstance(highest) || supported.none { it == highest }) return false
            if (NativePlaybackSession.engine() !== engine ||
                NativePlaybackSession.generation() != generation) return false
            if (highest != current)
                engineType.getMethod("configResolution", resolution).invoke(engine, highest)
            appliedEngine = WeakReference(engine)
            appliedGeneration = generation
            appliedVideoId = videoId
            LogUtil.info("最高画质: 原生支持集合已验证 current=$current selected=$highest")
            LogUtil.incr("maxQualityUniversalApply")
            true
        } catch (ex: Throwable) {
            LogUtil.debug("最高画质: 当前原生接口未就绪 " + ex.javaClass.simpleName)
            false
        }
    }
}
