package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import xyz.kejiyu.hongguo.LogUtil

/**
 * Associates the official home feed's currently VISIBLE pager item with
 * SaasVideoData. Hongguo 7.3.5.32 often has an empty TTVideoEngine.getVideoID()
 * on the feed even though the DTO's vid, series ID and poster are populated.
 *
 * Unlike the full-series reader, this binds through the native ViewPager2
 * and its active adapter rather than any obsolete obfuscated holder name.
 * Never substitute "the last observed" recommendation or a preloaded item:
 * a wrong cover is worse than no cover.
 */
internal object HomeFeedMediaInfo {
    private const val PAGE =
        "com.dragon.read.pages.main.MainFragmentActivity"
    private const val PAGER =
        "androidx.viewpager2.widget.ViewPager2"
    private const val ADAPTER_BASE =
        "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"
    private const val MODEL =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"

    private data class Candidate(
        val data: Any,
        val position: Int,
        val area: Long,
        val aligned: Boolean,
        val method: String
    )
    private var lastProbeAt = 0L
    private var lastSuccessSignature = ""

    private fun call(target: Any?, name: String, vararg args: Any?): Any? =
        try {
            target?.javaClass?.methods?.firstOrNull {
                it.name == name && it.parameterCount == args.size &&
                    it.parameterTypes.indices.all { n ->
                        args[n] == null || it.parameterTypes[n].isInstance(args[n]) ||
                            it.parameterTypes[n] == Int::class.javaPrimitiveType &&
                            args[n] is Int
                    }
            }?.invoke(target, *args)
        } catch (_: Throwable) { null }

    private fun data(input: Any?): Any? {
        if (input == null) return null
        if (input.javaClass.name == MODEL) return input
        val wrapper = call(input, "getVideoData")
        return wrapper?.takeIf { it.javaClass.name == MODEL }
    }

    private fun vid(model: Any?): String? = try {
        model?.javaClass?.getDeclaredField("vid")?.apply {
            isAccessible = true
        }?.get(model)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    } catch (_: Throwable) { null }

    private fun nativeAdapter(adapter: Any?): Boolean {
        var cl = adapter?.javaClass
        while (cl != null && cl != Any::class.java) {
            if (cl.name == ADAPTER_BASE) return true
            cl = cl.superclass
        }
        return false
    }

    private fun indexedModel(adapter: Any, position: Int): Any? {
        // The source APK exposes one int -> SaasVideoData model accessor.
        // Find its signature at runtime: its obfuscated name is irrelevant.
        if (position !in 0..5000) return null
        val methods = adapter.javaClass.methods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType.name == MODEL
        }
        if (methods.size != 1) return null
        return try { data(methods.single().invoke(adapter, position)) }
        catch (_: Throwable) { null }
    }

    private fun inspectPager(pager: View, screenW: Int, screenH: Int): Candidate? {
        if (!pager.isShown || pager.width <= 0 || pager.height <= 0 ||
            pager.width < screenW * 0.55f ||
            pager.height < screenH * 0.38f) return null
        val adapter = call(pager, "getAdapter") ?: return null
        if (!nativeAdapter(adapter)) return null
        val pos = (call(pager, "getCurrentItem") as? Number)?.toInt()
            ?: return null
        val active = data(call(adapter, "getCurrentData"))
        val byIndex = indexedModel(adapter, pos)
        val activeId = vid(active)
        val indexedId = vid(byIndex)
        // On a swipe the adapter and pager can briefly disagree: defer
        // publishing rather than show the previous drama's poster.
        if (activeId != null && indexedId != null &&
            activeId != indexedId) return null
        val model = active ?: byIndex ?: return null
        if (vid(model) == null) return null
        val rect = Rect()
        if (!pager.getGlobalVisibleRect(rect)) return null
        val area = rect.width().toLong() * rect.height()
        if (area < screenW.toLong() * screenH / 5L) return null
        return Candidate(model, pos, area,
            activeId != null && indexedId != null,
            if (active != null) "current-data" else "pager-position")
    }

    fun read(activity: Activity?, engine: Any?): FullSeriesMediaInfo.Info {
        if (activity?.javaClass?.name != PAGE ||
            Looper.myLooper() != Looper.getMainLooper())
            return FullSeriesMediaInfo.Info()
        val root = try { activity.window?.decorView }
            catch (_: Throwable) { null } ?: return FullSeriesMediaInfo.Info()
        val screen = activity.resources.displayMetrics
        val pending = ArrayDeque<View>()
        pending.add(root)
        var scanned = 0
        val candidates = ArrayList<Candidate>(3)
        while (pending.isNotEmpty() && scanned++ < 4000) {
            val v = pending.removeFirst()
            if (!v.isShown) continue
            if (v.javaClass.name == PAGER) {
                inspectPager(v, screen.widthPixels, screen.heightPixels)
                    ?.let(candidates::add)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    pending.addLast(v.getChildAt(i))
                }
            }
        }
        val ranked = candidates.sortedWith(
            compareByDescending<Candidate> { it.area }
                .thenByDescending { it.aligned }
        )
        val best = ranked.firstOrNull()
        val next = ranked.getOrNull(1)
        val ambiguous = best != null && next != null &&
            best.area == next.area && vid(best.data) != vid(next.data)
        val selected = if (ambiguous) null else best
        val activeVideoId = vid(selected?.data)
        val engineId = try { call(engine, "getVideoID")?.toString() }
            catch (_: Throwable) { null }
            ?.takeIf { it.isNotBlank() && it != "null" }
        // Engine identity is authoritative when it exists. In the official
        // home feed it may be blank even while video is playing.
        val verified = selected != null &&
            (engineId == null || engineId == activeVideoId)
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeAt > 9000L) {
            lastProbeAt = now
            LogUtil.info("系统媒体: 首页当前播放器模型" +
                " pagers=" + candidates.size +
                " currentItem=" + (selected?.position ?: -1) +
                " source=" + (selected?.method ?: "none") +
                " aligned=" + (selected?.aligned == true) +
                " engineVideoId=" + (engineId != null) +
                " pagerVideoId=" + (activeVideoId != null) +
                " idMatched=" + (engineId != null && engineId == activeVideoId) +
                " ambiguous=$ambiguous verified=$verified")
            LogUtil.incr("systemMediaHomePagerProbe")
        }
        if (!verified) return FullSeriesMediaInfo.Info()
        val info = FullSeriesMediaInfo.infoForVideoData(selected!!.data)
        val signature = activeVideoId + "|" + info.seriesId + "|" +
            (info.title != null) + "|" + (info.artworkUrl != null)
        if (signature != lastSuccessSignature) {
            lastSuccessSignature = signature
            LogUtil.info("系统媒体: 首页已绑定当前原生视频" +
                " title=" + (info.title != null) +
                " officialPoster=" + (info.artworkUrl != null) +
                " seriesId=" + (info.seriesId != null))
            LogUtil.incr("systemMediaHomeModelBound")
        }
        return info
    }
}
