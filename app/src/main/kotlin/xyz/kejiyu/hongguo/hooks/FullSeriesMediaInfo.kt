package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.SystemClock
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * 7.3.5.32 full-series playback keeps its current SaasVideoData inside:
 * ShortSeriesActivity.a -> uo4.y0.{I,z} (SaasVideoData).
 * The same presenter holds its loaded SaasVideoDetailModel at x.b.
 *
 * Reading this already-loaded data is more reliable than the fullscreen
 * overlay: fullscreen controls can be invisible while a video is playing.
 * Nothing here changes playback, creates network requests, or affects feed.
 */
internal object FullSeriesMediaInfo {
    data class Info(
        val title: String? = null,
        val episode: String? = null,
        val source: String = "none",
        val artworkUrl: String? = null
    )

    private const val ACTIVITY =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val DATA =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"
    private const val DETAIL =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel"

    private var lastDiagnosticAt = 0L
    // uo4.y0.x is only a short-lived Runnable holding SaasVideoDetailModel.
    // Cache only non-sensitive metadata for the same ShortSeriesActivity, not
    // the transient Runnable or the whole model (avoids retaining its video list).
    private var cachedActivity = WeakReference<Activity>(null)
    private var cachedSeriesKey: String? = null
    private var cachedSeriesTitle: String? = null
    private var cachedTotal: Long? = null
    private var cachedCoverUrl: String? = null

    private data class PagerState(
        val currentItem: Int? = null,
        val adapterCount: Int? = null,
        val listCount: Int? = null,
        val currentPageModel: Any? = null,
        val pageAligned: Boolean = false
    )

    private fun pagerState(presenter: Any?, detail: Any?, total: Long?): PagerState {
        // uo4.y0.h is j25.d, which extends j25.e -> AndroidX ViewPager.
        // getCurrentItem() is an actual selected page, unlike the cached
        // uo4.y0.z model that is only set during presenter initialization.
        val pager = field(presenter, "h") ?: return PagerState()
        if (!pager.javaClass.name.contains("ViewPager") &&
            !pager.javaClass.name.startsWith("j25.")) return PagerState()
        val item = number(call(pager, "getCurrentItem"))?.toInt()
            ?.takeIf { it in 0..5000 }
        val adapter = call(pager, "getAdapter")
        val count = number(call(adapter, "getCount"))?.toInt()
            ?.takeIf { it in 1..5000 }

        val list = listOf("episodesList", "playerSerialVideoList",
            "episodesListWithTrail", "episodesListWithTrailAfterCut")
            .mapNotNull { field(detail, it) as? List<*> }
            .firstOrNull { it.isNotEmpty() && it.size <= 5000 &&
                (it.size == count || total != null && it.size.toLong() == total) }

        val listSize = list?.size
        val aligned = item != null && count != null && item < count &&
            (total != null && count.toLong() == total ||
             listSize != null && count == listSize && count >= 2)
        val model = if (aligned && list != null && item < list.size)
            list[item] else null
        return PagerState(item, count, listSize, model, aligned)
    }

    private fun episodeFromPager(pager: PagerState, total: Long?): String? {
        val item = pager.currentItem ?: return null
        if (!pager.pageAligned) return null

        // The real ViewPager adapter must represent the same complete
        // episodes list or an exact copy of the series detail's list. A
        // three-page recycler with 80 episodes is NOT an episode counter.
        val selected = pager.currentPageModel
        val indexInList = number(field(selected, "indexInList"))
        val verified = if (indexInList != null &&
            indexInList in 0..4999L &&
            indexInList < (total ?: 5000L)) indexInList + 1
            else item.toLong() + 1L
        if (verified <= 0 || verified > 5000 ||
            total != null && verified > total) return null
        return "第" + verified + "集" +
            (if (total != null) " · 全" + total + "集" else "")
    }

    // The outer uo4.y0.h pager has only two navigation containers (count=2).
    // The real currently-playing episode is selected in the INNER
    // ShortSeriesSingleFragment's list. The original 7.3.5.32 APK exposes
    // getCurrentPosition() (backed by field U), getDataList() and
    // VideoData.getVideoData() -> SaasVideoData on that fragment.
    private const val EPISODE_FRAGMENT =
        "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"

    private data class ActiveFragmentEntry(
        val fragment: Any,
        val score: Int,
        val itemPosition: Int,
        val listCount: Int,
        val video: Any,
        val modelVidIndex: Long?,
        val modelListIndex: Long?,
        val seriesVerified: Boolean
    )

    private var lastFragmentDiagnosticAt = 0L

    private fun currentFragmentEntries(
        activity: Activity,
        seriesKey: String?,
        total: Long?
    ): List<ActiveFragmentEntry> {
        val manager = call(activity, "getSupportFragmentManager") ?: return emptyList()
        val entries = ArrayList<ActiveFragmentEntry>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>())
        val tasks = java.util.ArrayDeque<Pair<Any, Int>>()
        tasks.addLast(manager to 0)
        var visitedManagers = 0
        while (tasks.isNotEmpty() && visitedManagers++ < 20) {
            val (fm, depth) = tasks.removeFirst()
            val fragments = call(fm, "getFragments") as? List<*> ?: continue
            for (fragment in fragments) {
                if (fragment == null || !seen.add(fragment)) continue
                if (fragment.javaClass.name == EPISODE_FRAGMENT) {
                    val visible = call(fragment, "isVisible") as? Boolean ?: false
                    val resumed = call(fragment, "isResumed") as? Boolean ?: false
                    val hidden = call(fragment, "isHidden") as? Boolean ?: false
                    val view = call(fragment, "getView") as? android.view.View
                    val shown = view?.isShown == true
                    if (!hidden && (resumed || visible || shown)) {
                        val position = number(call(fragment,
                            "getCurrentPosition"))?.toInt()
                        val list = call(fragment, "getDataList") as? List<*>
                        if (position != null && list != null &&
                            position >= 0 && position < list.size &&
                            list.size in 1..5000) {
                            val wrapper = list[position]
                            val model = call(wrapper, "getVideoData")
                            if (model?.javaClass?.name == DATA) {
                                val modelId = str(field(model, "episodesId"))
                                val modelTotal = number(field(model,
                                    "episodesCount"))
                                val sameSeries = seriesKey == null ||
                                    modelId == null || seriesKey == modelId
                                val sameTotal = total == null ||
                                    modelTotal == null || modelTotal <= 0L ||
                                    modelTotal == total
                                if (sameSeries && sameTotal) {
                                    val bounds = android.graphics.Rect()
                                    val area = if (shown &&
                                        view?.getGlobalVisibleRect(bounds) == true)
                                        bounds.width().toLong() *
                                            bounds.height() else 0L
                                    val score = (if (resumed) 8 else 0) +
                                        (if (visible) 4 else 0) +
                                        (if (shown) 2 else 0) +
                                        (if (area > 50000) 1 else 0)
                                    entries.add(ActiveFragmentEntry(fragment,
                                        score, position, list.size, model,
                                        number(field(model, "vidIndex")),
                                        number(field(model, "indexInList")),
                                        (seriesKey != null && modelId == seriesKey) ||
                                            (total != null && modelTotal == total)))
                                }
                            }
                        }
                    }
                }
                if (depth < 3 && call(fragment, "isAdded") == true) {
                    val child = call(fragment, "getChildFragmentManager")
                    if (child != null) tasks.addLast(child to (depth + 1))
                }
            }
        }
        return entries
    }

    private fun episodeFromActiveFragment(
        activity: Activity,
        seriesKey: String?,
        total: Long?
    ): String? {
        val entries = currentFragmentEntries(activity, seriesKey, total)
        val sorted = entries.sortedWith(compareByDescending<ActiveFragmentEntry> {
            it.score
        }.thenByDescending { it.seriesVerified })
        val best = sorted.firstOrNull()
        val next = sorted.getOrNull(1)
        // Multiple equally visible active players can be cached by the app.
        // Refuse to pick a preloaded neighbour without extra evidence.
        val ambiguous = best != null && next != null &&
            best.score == next.score &&
            best.seriesVerified == next.seriesVerified &&
            (best.video !== next.video)

        val listIndex = best?.modelListIndex
        val vidIndex = best?.modelVidIndex
        val indexFromList = listIndex?.takeIf {
            it >= 0L && it < (total ?: 5000L)
        }?.plus(1)
        val indexFromVid = vidIndex?.takeIf {
            it in 1..(total ?: 5000L)
        }
        // vidIndex is an episode-facing numeric ID in this SaaS model;
        // indexInList is the local list offset. Prefer agreement, and do not
        // invent "first episode" when a local recycled list starts at zero.
        val current = when {
            ambiguous || best == null || best.score < 4 -> null
            indexFromVid != null && indexFromList != null &&
                indexFromVid == indexFromList -> indexFromVid
            indexFromVid != null && listIndex == vidIndex ->
                indexFromList
            indexFromVid != null && listIndex == 0L &&
                indexFromVid > 1L -> indexFromVid
            indexFromVid != null && indexFromList == null -> indexFromVid
            indexFromList != null && vidIndex == null -> indexFromList
            indexFromList != null && vidIndex == 0L -> indexFromList
            else -> null
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastFragmentDiagnosticAt > 12000L) {
            lastFragmentDiagnosticAt = now
            LogUtil.info("系统媒体: 全集内层视频索引 fragmentCandidates=" +
                entries.size + " visibleScore=" +
                (best?.score?.toString() ?: "?") +
                " localPosition=" + (best?.itemPosition?.toString() ?: "?") +
                " innerListCount=" + (best?.listCount?.toString() ?: "?") +
                " vidIndex=" + (vidIndex?.toString() ?: "?") +
                " indexInList=" + (listIndex?.toString() ?: "?") +
                " verifiedSeries=" + (best?.seriesVerified == true) +
                " ambiguous=$ambiguous" +
                " resolvedEpisode=" + (current?.toString() ?: "?"))
            LogUtil.incr("systemMediaInnerEpisodeProbe")
        }
        if (current == null || current < 1 ||
            total != null && current > total) return null
        return "第" + current + "集" +
            (if (total != null) " · 全" + total + "集" else "")
    }

    private fun preserveCurrentSeries(
        activity: Activity, seriesKey: String?, newTitle: String?,
        newTotal: Long?, newCover: String?
    ): Pair<String?, Long?> {
        if (cachedActivity.get() !== activity ||
            seriesKey != null && cachedSeriesKey != null &&
                cachedSeriesKey != seriesKey) {
            cachedActivity = WeakReference(activity)
            cachedSeriesTitle = null
            cachedTotal = null
            cachedCoverUrl = null
            cachedSeriesKey = seriesKey
        }
        if (seriesKey != null) cachedSeriesKey = seriesKey
        if (newTitle != null) cachedSeriesTitle = newTitle
        if (newTotal != null && newTotal in 1..5000L) cachedTotal = newTotal
        if (!newCover.isNullOrBlank()) cachedCoverUrl = newCover
        return cachedSeriesTitle to cachedTotal
    }

    private fun field(receiver: Any?, name: String): Any? {
        if (receiver == null) return null
        var cls: Class<*>? = receiver.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val declared = cls.getDeclaredField(name)
                declared.isAccessible = true
                return declared.get(receiver)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun call(receiver: Any?, methodName: String): Any? = try {
        receiver?.javaClass?.methods?.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        }?.invoke(receiver)
    } catch (_: Throwable) { null }

    private fun str(value: Any?): String? {
        val text = (value as? CharSequence)?.toString()?.trim()
            ?.replace(Regex("\\s+"), " ") ?: return null
        if (text.length !in 2..80 || text.startsWith("http") ||
            text.matches(Regex("[0-9]+")) || text.contains("://") ||
            text in setOf("红果短剧", "全集", "正在播放的视频")) return null
        return text
    }

    private fun number(value: Any?): Long? = (value as? Number)?.toLong()

    private fun vid(entity: Any?): String? {
        if (entity == null) return null
        val direct = field(entity, "vid") ?: field(entity, "videoId") ?:
            call(entity, "getVideoId")
        return direct?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun episodeFromIndex(model: Any?, total: Long?): String? {
        if (model == null || model.javaClass.name != DATA) return null
        val index = number(field(model, "indexInList"))
        val vidIndex = number(field(model, "vidIndex"))

        // indexInList is the playlist's zero-based index. vidIndex is
        // independently supplied by the server and not necessarily 0-based.
        // Prefer explicit playlist position when plausible.
        val current: Long? = when {
            index != null && index >= 0 &&
                index < 5000 && (total == null || index < total) -> index + 1
            vidIndex == 0L -> 1L
            vidIndex != null && vidIndex in 1L..5000L &&
                total != null && vidIndex <= total -> vidIndex
            else -> null
        }
        if (current == null) return null
        val result = "第" + current + "集"
        return if (total != null && total in current..5000L) {
            result + " · 全" + total + "集"
        } else result
    }

    private fun indexFromList(detail: Any?, engineId: String?): Long? {
        if (detail == null || engineId == null) return null
        for (key in listOf("episodesList", "playerSerialVideoList",
            "episodesListWithTrail", "episodesListWithTrailAfterCut")) {
            val list = field(detail, key) as? List<*> ?: continue
            if (list.isEmpty() || list.size > 500) continue
            for ((index, item) in list.withIndex()) {
                if (vid(item) == engineId) return index.toLong() + 1
            }
        }
        return null
    }

    fun read(activity: Activity?, engine: Any?): Info {
        if (activity?.javaClass?.name != ACTIVITY) return Info()
        val presenter = field(activity, "a")
        val detailHolder = field(presenter, "x")
        val detail = field(detailHolder, "b")?.takeIf { it.javaClass.name == DETAIL }
        val videoId = call(engine, "getVideoID")?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }

        val current = listOf("I", "z")
            .mapNotNull { key -> field(presenter, key) }
            .filter { it.javaClass.name == DATA }

        // A prepared next episode can coexist with the active video. Never
        // use its index or episode title without an exact ID match.
        val active = if (videoId != null) current.firstOrNull {
            vid(it) == videoId
        } else null

        val freshTotal = listOfNotNull(
            number(field(active, "episodesCount")),
            number(field(detail, "episodeTotalCnt")),
            number(field(detail, "episodeCnt")),
            // When the engine's videoID is an internal player identifier,
            // data.vid won't match; series-level episodesCount is still
            // reliable if it belongs to this ShortSeriesActivity.
            number(field(current.firstOrNull(), "episodesCount"))
        ).firstOrNull { it in 1..5000L }

        // Detail metadata belongs to this ShortSeriesActivity rather than an
        // unrelated home recommendation; its title can be shown even if the
        // exact episode is not yet available from the active video model.
        val latestTitle =
            str(field(active, "seriesName")) ?:
            str(field(detail, "episodesTitle")) ?:
            str(field(active, "title")) ?:
            str(field(current.firstOrNull(), "seriesName"))
        val seriesKey = str(field(detail, "episodesId")) ?:
            str(field(current.firstOrNull(), "episodesId"))
        // 7.3.5.32's official Saas model has URL strings for both full-sized
        // and compact series posters. The detail is a transient Runnable
        // payload, so preserve its URL for the currently displayed series.
        val freshCoverUrl = listOfNotNull(
            field(detail, "episodesCover") as? String,
            field(detail, "smallEpisodesCover") as? String,
            field(active, "cover") as? String,
            field(active, "episodeCover") as? String,
            field(current.firstOrNull(), "cover") as? String,
            field(current.firstOrNull(), "episodeCover") as? String
        ).firstOrNull { it.isNotBlank() }
        val retained = preserveCurrentSeries(activity, seriesKey,
            latestTitle, freshTotal, freshCoverUrl)
        val seriesTitle = retained.first
        val total = retained.second
        val pager = pagerState(presenter, detail, total)

        // Reading the active SaasVideoData is O(1). Avoid scanning hundreds
        // of playlist items on every ~1.4s media timeline tick.
        val fromActive = episodeFromIndex(active, total)
        val episode = fromActive
            ?: indexFromList(detail, videoId)?.let { episodeNumber ->
                "第" + episodeNumber + "集" +
                    (if (total != null && total >= episodeNumber)
                        " · 全" + total + "集" else "")
            }
            ?: episodeFromActiveFragment(activity, cachedSeriesKey, total)
            ?: episodeFromPager(pager, total)
            // The total episode count is independently verified by the
            // series data model. Show it even if the active index is unknown.
            ?: total?.let { "全" + it + "集" }

        val now = SystemClock.uptimeMillis()
        if (now - lastDiagnosticAt > 12000L) {
            lastDiagnosticAt = now
            LogUtil.info("系统媒体: 全集原生元数据解析 presenter=" +
                (presenter != null) + " detail=" + (detail != null) +
                " activeVideoId=" + (videoId != null) +
                " candidateCount=" + current.size +
                " videoMatched=" + (active != null) +
                " title=" + (seriesTitle != null) +
                " coverUrl=" + (cachedCoverUrl != null) +
                " episode=" + (episode != null) +
                " hasCurrentEpisode=" + (episode?.startsWith("第") == true) +
                " total=" + (total != null) +
                " pagerIndex=" + (pager.currentItem?.toString() ?: "?") +
                " pagerCount=" + (pager.adapterCount?.toString() ?: "?") +
                " detailListCount=" + (pager.listCount?.toString() ?: "?") +
                " pagerAligned=" + pager.pageAligned +
                " innerEpisodeProbe=" + (episode?.startsWith("第") == true))
            LogUtil.incr("systemMediaFullSeriesNativeProbe")
        }
        return Info(seriesTitle, episode,
            if (active != null) "saas-current-video"
            else if (pager.pageAligned) "saas-fullseries-pager"
            else if (seriesTitle != null || total != null) "saas-series-detail"
            else "none",
            cachedCoverUrl)
    }
}
