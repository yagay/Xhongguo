package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
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
    private var lastTreeScanAt = 0L
    private var lastSuccessSignature = ""
    private val identityAgreement = VideoIdentityAgreement()
    // Scanning the whole View tree every metadata tick causes UI jank.
    // A weak pager reference is valid only while that Activity still shows it.
    private var cachedActivity = WeakReference<Activity>(null)
    private var cachedPager = WeakReference<View>(null)

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
        // Semantic native package rather than version-specific obfuscated
        // class. The returned model is still validated below.
        return adapter?.javaClass?.name
            ?.startsWith("com.dragon.read.component.shortvideo.") == true
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

    private fun modelFromVisibleHolder(pager: View, pos: Int): Any? {
        // ViewPager2's selected holder is stronger evidence than a stale
        // adapter.getCurrentData() at the boundary between two videos.
        val recycler = (pager as? ViewGroup)?.getChildAt(0) ?: return null
        val holder = call(recycler, "findViewHolderForAdapterPosition", pos)
            ?: return null
        val itemView = try {
            holder.javaClass.getField("itemView").get(holder) as? View
        } catch (_: Throwable) { null }
        if (itemView?.isShown != true) return null
        val models = ArrayList<Any>(2)
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (field.type.name != MODEL) continue
                try {
                    field.isAccessible = true
                    field.get(holder)?.let(models::add)
                } catch (_: Throwable) {}
            }
            type = type.superclass
        }
        return models.singleOrNull()?.takeIf { vid(it) != null }
    }

    private fun inspectPager(pager: View, screenW: Int, screenH: Int): Candidate? {
        if (!pager.isShown || pager.width <= 0 || pager.height <= 0 ||
            pager.width < screenW * 0.55f ||
            pager.height < screenH * 0.38f) return null
        val adapter = call(pager, "getAdapter") ?: return null
        if (!nativeAdapter(adapter)) return null
        // Avoid showing the previous page while ViewPager2 is settling.
        val scrollState = (call(pager, "getScrollState") as? Number)?.toInt()
        if (scrollState != null && scrollState != 0) return null
        val pos = (call(pager, "getCurrentItem") as? Number)?.toInt()
            ?: return null
        val active = data(call(adapter, "getCurrentData"))
        val indexed = indexedModel(adapter, pos)
        val visibleHolder = if (indexed == null)
            modelFromVisibleHolder(pager, pos) else null
        val byIndex = indexed ?: visibleHolder
        val activeId = vid(active)
        val indexedId = vid(byIndex)
        // On a swipe the adapter and pager can briefly disagree: defer
        // publishing rather than show the previous drama's poster.
        if (activeId != null && indexedId != null &&
            activeId != indexedId) return null
        val model = byIndex ?: active ?: return null
        if (vid(model) == null) return null
        val rect = Rect()
        if (!pager.getGlobalVisibleRect(rect)) return null
        val area = rect.width().toLong() * rect.height()
        if (area < screenW.toLong() * screenH / 5L) return null
        return Candidate(model, pos, area,
            activeId != null && indexedId != null,
            when {
                indexed != null -> "pager-position"
                visibleHolder != null -> "selected-holder"
                else -> "current-data"
            })
    }

    fun read(activity: Activity?, engine: Any?): FullSeriesMediaInfo.Info {
        if (activity?.javaClass?.name != PAGE ||
            Looper.myLooper() != Looper.getMainLooper())
            return FullSeriesMediaInfo.Info()
        val root = try { activity.window?.decorView }
            catch (_: Throwable) { null } ?: return FullSeriesMediaInfo.Info()
        val screen = activity.resources.displayMetrics
        val candidates = ArrayList<Candidate>(3)
        val cached = if (cachedActivity.get() === activity)
            cachedPager.get() else null
        val reused = cached?.let {
            inspectPager(it, screen.widthPixels, screen.heightPixels)
        }
        if (reused != null) candidates.add(reused)
        else {
            val scanAt = SystemClock.uptimeMillis()
            if (scanAt - lastTreeScanAt in 0L..280L)
                return FullSeriesMediaInfo.Info()
            lastTreeScanAt = scanAt
            val pending = ArrayDeque<View>()
            pending.add(root)
            var scanned = 0
            while (pending.isNotEmpty() && scanned++ < 4000) {
                val v = pending.removeFirst()
                if (!v.isShown) continue
                if (v.javaClass.name == PAGER) {
                    inspectPager(v, screen.widthPixels, screen.heightPixels)
                        ?.let {
                            candidates.add(it)
                            cachedActivity = WeakReference(activity)
                            cachedPager = WeakReference(v)
                        }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        pending.addLast(v.getChildAt(i))
                    }
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
        // Official host routes can expose a TTVideoEngine ID in a different
        // namespace from SaasVideoData. A mismatch may only fall back after
        // independent selected-page evidence survives multiple probes.
        val now = SystemClock.uptimeMillis()
        val stableKey = if (selected != null && activeVideoId != null)
            System.identityHashCode(activity).toString() + "|" +
                System.identityHashCode(engine).toString() + "|" +
                selected.position + "|" + activeVideoId else null
        val verified = identityAgreement.accept(
            stableKey, engineId, activeVideoId,
            independentlySelected = selected != null &&
                (selected.aligned || selected.method != "current-data"),
            nowMs = now)
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
                " stableFallback=" + (verified && engineId != null &&
                    engineId != activeVideoId) +
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
