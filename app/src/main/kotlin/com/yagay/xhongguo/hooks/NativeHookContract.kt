package com.yagay.xhongguo.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Semantic signatures extracted from Hongguo CN 7.4.2.32, 25 DEX files.
 * These are a contract, not a version/obfuscation lookup table.
 * Every later APK is independently checked by reflection before a hook binds.
 */
internal object NativeHookContract {
    const val APK_VERSION = "7.4.2.32"
    const val APK_SHA256 = "340cff9073bf7bd6530bc79f0c9ba00ba89dd3e8482c0f0bcc27cfa71b891b84"
    const val ENGINE = "com.ss.ttvideoengine.TTVideoEngine"
    const val CONTEXT = "com.ss.android.videoshop.context.VideoContext"
    const val CONTROLLER = "com.ss.android.videoshop.controller.VideoController"
    const val ENTITY = "com.ss.android.videoshop.entity.PlayEntity"
    const val INQUIRER = "com.ss.android.videoshop.api.VideoStateInquirer"
    const val LAYER = "com.ss.android.videoshop.mediaview.LayerHostMediaLayout"
    const val FRAGMENT = "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
    const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    const val VIDEO = "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"
    const val DETAIL = "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel"
    const val PARAMS = "com.ss.ttm.player.PlaybackParams"
    const val RESOLUTION = "com.ss.ttvideoengine.Resolution"
    const val MODEL = "com.ss.ttvideoengine.model.VideoModel"
    const val HOLDER = "com.dragon.read.recyler.AbsRecyclerViewHolder"
    const val BOTTOM = "com.dragon.read.widget.BottomTabFrameLayout"

    data class Signature(val name: String, val result: String, val params: List<String>,
        val static: Boolean = false) {
        fun matches(method: Method): Boolean = method.name == name &&
            method.returnType.name == result && method.parameterTypes.map { it.name } == params &&
            Modifier.isStatic(method.modifiers) == static && !Modifier.isAbstract(method.modifiers) &&
            !method.isBridge && !method.isSynthetic
    }
    fun sig(name: String, result: String, vararg params: String, static: Boolean = false) =
        Signature(name, result, params.toList(), static)
    fun load(loader: ClassLoader, name: String): Class<*>? =
        runCatching { Class.forName(name, false, loader) }.getOrNull()
    fun methods(type: Class<*>?, signatures: Collection<Signature>): List<Method> =
        if (type == null) emptyList() else runCatching {
            type.declaredMethods.filter { method -> signatures.any { it.matches(method) } }
                .sortedBy { it.toGenericString() }
        }.getOrDefault(emptyList())
    fun has(type: Class<*>?, signature: Signature): Boolean =
        type != null && runCatching {
            (type.methods.asSequence() + type.declaredMethods.asSequence()).any(signature::matches)
        }.getOrDefault(false)
    fun hasField(type: Class<*>?, name: String, fieldType: String): Boolean =
        type != null && runCatching {
            val field = type.getDeclaredField(name)
            field.type.name == fieldType && !Modifier.isStatic(field.modifiers)
        }.getOrDefault(false)

    val engineTransport = listOf("play", "start", "pause", "stop", "release", "releaseAsync")
        .map { sig(it, "void") }
    val controllerState = listOf(sig("onPlaybackStateChanged", "void", ENGINE, "int"),
        sig("onRenderStart", "void", ENGINE))
    val contextEvents = listOf("onVideoPlay", "onVideoPause", "onVideoReleased", "onFirstPlayStart")
        .map { sig(it, "void", INQUIRER, ENTITY) } +
        sig("onEnginePlayStart", "void", INQUIRER, ENTITY, "int")
    val contextTransport = listOf(sig("play", "void"), sig("pause", "void"))
    val entitySetters = listOf(sig("setTitle", ENTITY, "java.lang.String"),
        sig("setVideoId", ENTITY, "java.lang.String"),
        sig("setBusinessModel", ENTITY, "java.lang.Object"))
    val recyclerBind = listOf(sig("onBind", "void", "java.lang.Object", "int"),
        sig("onBind", "void", "java.lang.Object", "int", "java.util.List"),
        sig("onBind", "void", "java.lang.Object", "int", "androidx.recyclerview.widget.RecyclerView\$Adapter"))
}
