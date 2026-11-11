package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.SystemClock
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.FeatureTrace

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
        val artworkUrl: String? = null,
        val seriesId: String? = null
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
    // Playback on the official series page uses a VideoShop video ID which
    // is not always equal to SaasVideoData.vid (confirmed by 21:18 ZIP).
    // Track the actual presenter model across engine handoffs. A recycled
    // model from the old drama is not valid evidence for a NEW engine.
    private var boundPresenterActivity = WeakReference<Activity>(null)
    private var boundPresenterEngine = WeakReference<Any>(null)
    private var boundPresenterVideoId: String? = null
    private var lastMatchedActivity = WeakReference<Activity>(null)
    private var lastMatchedVideoId: String? = null
    private var transitionActivity = WeakReference<Activity>(null)
    private var transitionEngine = WeakReference<Any>(null)
    private var transitionAt = 0L
    private var transitionPreviousVideo: String? = null
    private var transitionPreviousSeries: String? = null

    /** Called before the host's new native engine starts: capture the prior
     * verified identity without importing the old Activity's stale labels. */
    fun beginEngineTransition(activity: Activity?, engine: Any?, previousSeries: String?) {
        if (activity?.javaClass?.name != ACTIVITY || engine == null) return
        transitionActivity = WeakReference(activity)
        transitionEngine = WeakReference(engine)
        transitionAt = SystemClock.uptimeMillis()
        transitionPreviousVideo = when {
            lastMatchedActivity.get() === activity -> lastMatchedVideoId
            boundPresenterActivity.get() === activity -> boundPresenterVideoId
            else -> null
        }
        transitionPreviousSeries = previousSeries
    }

    /**
     * A host DTO consumed AFTER the new engine started may provide its
     * official series identity despite incompatible engine video-ID formats.
     * Accept it only with matching official detail and one unique series.
     * Never use this for the home feed or unrelated Activity instances.
     */
    private fun recentNativeHandoff(activity: Activity, engine: Any?): Info? {
        if (transitionActivity.get() !== activity ||
            transitionEngine.get() !== engine) return null
        val now = SystemClock.uptimeMillis()
        var recentNativeCount = 0
        var withOfficialDetail = 0
        val selected: Pair<NativeEvidencePolicy.Candidate, VideoSnapshot>? =
            synchronized(detailLock) {
                val all = observedVideos.mapNotNull { (videoId, v) ->
                    val series = v.seriesId ?: return@mapNotNull null
                    val detail = observedDetails[series]
                    val candidate = NativeEvidencePolicy.Candidate(
                        videoId, series, v.nativeConsumedAt,
                        detail != null, detail?.observedAt ?: 0L)
                    candidate to v
                }
                recentNativeCount = all.count {
                    it.first.nativeObservedAtMs >= transitionAt &&
                        it.first.videoId != transitionPreviousVideo
                }
                withOfficialDetail = all.count {
                    it.first.nativeObservedAtMs >= transitionAt &&
                        it.first.videoId != transitionPreviousVideo &&
                        it.first.detailAvailable
                }
                val choice = NativeEvidencePolicy.select(
                    all.map { it.first }, transitionAt, now,
                    transitionPreviousVideo, transitionPreviousSeries)
                    ?: return@synchronized null
                all.firstOrNull { it.first == choice }
            }
        if (selected == null) {
            FeatureTrace.event("media", "native_handoff_probe", "degraded",
                "recentNative=" + recentNativeCount +
                    ",matchingDetail=" + withOfficialDetail +
                    ",pendingMs=" + (now - transitionAt).coerceAtLeast(0L),
                minIntervalMs = 3500L)
            return null
        }
        val (candidate, video) = selected
        val detail = detailForSeries(candidate.seriesId) ?: return null
        val title = detail.title ?: video.title
        val poster = detail.artworkUrl ?: video.poster
        if (title == null && poster == null) return null
        lastMatchedActivity = WeakReference(activity)
        lastMatchedVideoId = candidate.videoId
        FeatureTrace.event("media", "native_handoff_match", "matched",
            "officialDetail=true,series=true,title=" + (title != null) +
                ",poster=" + (poster != null) +
                ",differentSeries=" + (candidate.seriesId != transitionPreviousSeries),
            minIntervalMs = 1500L)
        // A fresh DTO can belong to a preloaded neighbouring episode.
        // The official series total is safe, but a guessed current episode
        // is not. Publish "全N集" until an exact active-index signal arrives.
        val episodeSummary = detail.total?.takeIf { it in 1..5000L }
            ?.let { "全" + it + "集" }
        return Info(title, episodeSummary, "series-native-handoff-evidence",
            poster, candidate.seriesId)
    }

    // The 7.3.5.32 detail is often held only by a short-lived uo4.y0.x
    // Runnable. Retain safe metadata keyed by the actual series ID, never
    // the Runnable, Activity, video list or a stale episode screenshot.
    private data class DetailSnapshot(
        val title: String?,
        val artworkUrl: String?,
        val total: Long?,
        val observedAt: Long
    )
    private val detailLock = Any()
    private val observedDetails = LinkedHashMap<String, DetailSnapshot>()
    private const val DETAIL_TTL_MS = 30L * 60L * 1000L

    // The native feed and full-series screens share these immutable, bounded
    // snapshots. Key by the ACTUAL video ID from TTVideoEngine, never by UI
    // position, autoplay neighbours or a recycled Activity.
    private data class VideoSnapshot(
        val seriesId: String?,
        val title: String?,
        val poster: String?,
        val observedAt: Long,
        val nativeConsumedAt: Long
    )
    private val observedVideos = LinkedHashMap<String, VideoSnapshot>()
    private const val VIDEO_TTL_MS = 15L * 60L * 1000L
    private var lastObservedVideoDiagnosticAt = 0L
    private var lastDetailTypeDiagnosticAt = 0L

    /** Observe a completed native data-model *at its consumption boundary*.
     * The empty default constructor is not useful with RPC/Gson adapters. */
    fun observeVideoData(model: Any?, nativeConsumer: Boolean = true) {
        if (model?.javaClass?.name != DATA) return
        val videoId = seriesId(field(model, "vid")) ?: return
        val id = seriesId(field(model, "episodesId")) ?:
            seriesId(field(model, "seriesId"))
        val title = str(field(model, "seriesName"))
        val posterValue = field(model, "cover") as? String
        val episodeCover = field(model, "episodeCover") as? String
        // A still/thumbnail is not the official series poster.
        val poster = posterValue?.takeIf {
            it.isNotBlank() && it != episodeCover
        }?.let { MediaArtworkLoader.normalize(it) }
        if (title == null && poster == null) return
        val now = SystemClock.uptimeMillis()
        var newMetadata = false
        synchronized(detailLock) {
            val previous = observedVideos.remove(videoId)
            val sameSeries = previous != null &&
                (id == null || previous.seriesId == null ||
                    previous.seriesId == id)
            val updated = VideoSnapshot(
                id ?: previous?.seriesId,
                title ?: if (sameSeries) previous?.title else null,
                poster ?: if (sameSeries) previous?.poster else null,
                now, if (nativeConsumer) now else previous?.nativeConsumedAt ?: 0L)
            observedVideos[videoId] = updated
            newMetadata = previous == null ||
                previous.seriesId != updated.seriesId ||
                previous.title != updated.title ||
                previous.poster != updated.poster
            while (observedVideos.size > 250) {
                val it = observedVideos.keys.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
            if (now - lastObservedVideoDiagnosticAt > 12000L) {
                lastObservedVideoDiagnosticAt = now
                LogUtil.info("系统媒体: 原生视频数据已观察" +
                    " videoId=true seriesId=" + (id != null) +
                    " title=" + (title != null) +
                    " poster=" + (poster != null) +
                    " cacheSize=" + observedVideos.size)
                LogUtil.incr("systemMediaVideoDataObserved")
            }
        }
        if (newMetadata) SystemMediaBridge.onNativeSeriesMetadataAvailable()
    }

    /**
     * The feed's native ViewPager2 can identify the *visible* data model
     * even when TTVideoEngine.getVideoID() is blank. A series detail
     * snapshot may then supply the official title and full-size poster,
     * but ONLY if its real series ID matches this current model.
     */
    fun infoForVideoData(model: Any?): Info {
        if (model?.javaClass?.name != DATA) return Info()
        val videoId = seriesId(field(model, "vid")) ?: return Info()
        observeVideoData(model, nativeConsumer = false)
        val cached = synchronized(detailLock) {
            observedVideos[videoId]?.takeIf {
                SystemClock.uptimeMillis() - it.observedAt in 0L..VIDEO_TTL_MS
            }
        }
        val modelSeriesId = seriesId(field(model, "episodesId")) ?:
            seriesId(field(model, "seriesId")) ?: cached?.seriesId
        val detail = detailForSeries(modelSeriesId)
        val modelCover = (field(model, "cover") as? String)
            ?.takeIf { it.isNotBlank() &&
                it != (field(model, "episodeCover") as? String) }
            ?.let(MediaArtworkLoader::normalize)
        return Info(
            title = detail?.title ?: str(field(model, "seriesName")) ?:
                cached?.title,
            source = "home-native-current-item",
            artworkUrl = detail?.artworkUrl ?: modelCover ?: cached?.poster,
            seriesId = modelSeriesId
        )
    }

    fun observedForEngine(engine: Any?): Info {
        val videoId = seriesId(call(engine, "getVideoID")) ?: return Info()
        val now = SystemClock.uptimeMillis()
        val video = synchronized(detailLock) {
            observedVideos[videoId]?.takeIf {
                now - it.observedAt in 0L..VIDEO_TTL_MS
            }
        } ?: return Info()
        val detail = detailForSeries(video.seriesId)
        return Info(detail?.title ?: video.title, null,
            "native-model-video-id",
            detail?.artworkUrl ?: video.poster, video.seriesId)
    }


    private fun seriesId(value: Any?): String? =
        value?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it != "null" && it != "0" && it.length <= 128
        }

    /** Version-independent bridge: observe the actual DTO, not an
     * obfuscated Runnable class which changes with each host APK. */
    fun observeDetailModel(model: Any?) {
        rememberDetail(model)
    }

    private fun rememberDetail(model: Any?) {
        if (!isDetailModel(model)) return
        val id = seriesId(detailField(model, "episodesId")) ?:
            seriesId(detailField(model, "seriesId"))
        val nowForEvidence = SystemClock.uptimeMillis()
        if (nowForEvidence - lastDetailTypeDiagnosticAt > 12000L) {
            lastDetailTypeDiagnosticAt = nowForEvidence
            LogUtil.info("系统媒体: 原生详情对象实际到达" +
                " wrapper=" + (model?.javaClass?.name != DETAIL) +
                " id=" + (id != null) +
                " hasTitle=" + (str(detailField(model, "episodesTitle")) != null) +
                " hasCover=" +
                (MediaArtworkLoader.normalize(detailField(model, "episodesCover") as? String)
                    != null))
            LogUtil.incr("systemMediaNativeDetailObjectSeen")
        }
        if (id == null) return
        val title = str(detailField(model, "episodesTitle")) ?:
            str(detailField(model, "seriesName"))
        val cover = listOf("episodesCover", "smallEpisodesCover")
            .firstNotNullOfOrNull { key ->
                (detailField(model, key) as? String)?.let {
                    MediaArtworkLoader.normalize(it)
                }
            }
        val total = listOf("episodeTotalCnt", "episodeCnt")
            .firstNotNullOfOrNull { key ->
                number(detailField(model, key))?.takeIf { it in 1..5000L }
            }
        if (title == null && cover == null && total == null) return
        val now = SystemClock.uptimeMillis()
        var newMetadata = false
        synchronized(detailLock) {
            val prev = observedDetails[id]
            val updated = DetailSnapshot(title ?: prev?.title,
                cover ?: prev?.artworkUrl, total ?: prev?.total, now)
            observedDetails.remove(id)
            observedDetails[id] = updated
            val iterator = observedDetails.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.observedAt > DETAIL_TTL_MS ||
                    observedDetails.size > 24) iterator.remove()
            }
            newMetadata = prev == null || prev.title != updated.title ||
                prev.artworkUrl != updated.artworkUrl ||
                prev.total != updated.total
            if (newMetadata) {
                LogUtil.info("系统媒体: 已捕获官方全集详情" +
                    " title=" + (updated.title != null) +
                    " poster=" + (updated.artworkUrl != null) +
                    " total=" + (updated.total != null))
                LogUtil.incr("systemMediaSeriesDetailCaptured")
            }
        }
        if (newMetadata) SystemMediaBridge.onNativeSeriesMetadataAvailable()
    }

    private fun detailForSeries(id: String?): DetailSnapshot? {
        if (id == null) return null
        val now = SystemClock.uptimeMillis()
        return synchronized(detailLock) {
            observedDetails[id]?.takeIf {
                now - it.observedAt in 0L..DETAIL_TTL_MS
            }
        }
    }

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

    private data class FragmentMedia(
        val title: String? = null,
        val episode: String? = null,
        val artworkUrl: String? = null,
        val total: Long? = null,
        val seriesId: String? = null,
        val videoId: String? = null
    )

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
                                val modelId = seriesId(field(model, "episodesId"))
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

    private fun mediaFromActiveFragment(
        activity: Activity,
        seriesKey: String?,
        total: Long?,
        engineVideoId: String? = null
    ): FragmentMedia {
        val entries = currentFragmentEntries(activity, seriesKey, total)
            .filter { engineVideoId == null || vid(it.video) == engineVideoId }
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
        if (best == null || ambiguous || best.score < 4) return FragmentMedia()
        val model = best.video
        val fragmentTotal = number(field(model, "episodesCount"))
            ?.takeIf { it in 1..5000L }
        observeVideoData(model, nativeConsumer = false)
        val modelTitle = str(field(model, "seriesName"))
        // The Saas model separates the drama's 'cover' from the individual
        // episode's 'episodeCover' (often a frame still). Accept 'cover'
        // only when distinct from 'episodeCover', never the frame still.
        val poster = field(model, "cover") as? String
        val frameStill = field(model, "episodeCover") as? String
        val fragmentCover: String? = poster?.takeIf {
            it.isNotBlank() && it != frameStill
        }
        val matchedEpisode = if (current != null && current >= 1 &&
            (total == null || current <= total)) {
            "第" + current + "集" +
                (if (total != null) " · 全" + total + "集" else "")
        } else null
        return FragmentMedia(
            title = modelTitle,
            episode = matchedEpisode,
            artworkUrl = fragmentCover,
            total = fragmentTotal,
            seriesId = seriesId(field(model, "episodesId")) ?:
                seriesId(field(model, "seriesId")),
            videoId = vid(model)
        )
    }

    private fun preserveCurrentSeries(
        activity: Activity, seriesKey: String?, newTitle: String?,
        newTotal: Long?, newCover: String?, verifiedPoster: Boolean
    ): Pair<String?, Long?> {
        if (cachedActivity.get() !== activity ||
            seriesKey != null && cachedSeriesKey != null &&
                cachedSeriesKey != seriesKey ||
            // If IDs are known, they alone identify the series. A
            // late title correction must never erase its cached poster.
            (seriesKey == null && newTitle != null &&
                cachedSeriesTitle != null &&
                newTitle != cachedSeriesTitle)) {
            cachedActivity = WeakReference(activity)
            cachedSeriesTitle = null
            cachedTotal = null
            cachedCoverUrl = null
            cachedSeriesKey = seriesKey
        }
        if (seriesKey != null) cachedSeriesKey = seriesKey
        if (newTitle != null) cachedSeriesTitle = newTitle
        if (newTotal != null && newTotal in 1..5000L) cachedTotal = newTotal
        // The official detail poster must be allowed to REPLACE an early
        // low-confidence SaasVideoData.cover. Never let a later episode frame
        // replace a verified detail poster.
        if (!newCover.isNullOrBlank() &&
            (cachedCoverUrl.isNullOrBlank() || verifiedPoster &&
                cachedCoverUrl != newCover)) {
            cachedCoverUrl = newCover
            LogUtil.info("系统媒体: 已确定当前短剧官方封面" +
                " verified=$verifiedPoster")
        }
        return cachedSeriesTitle to cachedTotal
    }

    private fun isDetailModel(model: Any?): Boolean {
        var type: Class<*>? = model?.javaClass
        while (type != null && type != Any::class.java) {
            if (type.name == DETAIL) return true
            type = type.superclass
        }
        return false
    }

    /** Read SaasVideoDetailModelWrapper's delegated properties, not the
     * empty inherited fields of its base SaasVideoDetailModel. */
    private fun detailField(model: Any?, name: String): Any? {
        val direct = field(model, name)
        if (model == null || !isDetailModel(model)) return direct
        // Wrapper's semantic delegated value takes priority over the inherited
        // base field, which may be unset or initialized to a placeholder.
        val delegate = field(model, "_" + name + '$' + "delegate")
            ?: return direct
        val props = (field(model, "" + '$' + '$' + "delegatedProperties")
            as? Array<*>) ?: return direct
        val prop = props.firstOrNull { p ->
            try {
                val n = p?.javaClass?.methods?.firstOrNull {
                    it.name == "getName" && it.parameterCount == 0
                }?.invoke(p)?.toString()
                n == "_" + name || n == name
            } catch (_: Throwable) { false }
        } ?: return direct
        return try {
            // Kotlin delegate getter is identified by its semantic
            // KProperty argument/return shape, never by obfuscated name.
            val getter = delegate.javaClass.declaredMethods.firstOrNull {
                it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "kotlin.reflect.KProperty" &&
                    it.returnType != Void.TYPE
            } ?: return direct
            getter.isAccessible = true
            getter.invoke(delegate, prop) ?: direct
        } catch (_: Throwable) { direct }
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

    /**
     * In the final-episode autoplay case the SAME ShortSeriesActivity can
     * contain two generations of DTOs: its old SaasVideoDetailModel and
     * the new video's TTVideoEngine/SaasVideoData. The playing video's
     * exact ID wins. Never use the old Activity detail to choose/filter the
     * active fragment, or to repopulate its old title and cover.
     */
    fun read(activity: Activity?, engine: Any?): Info {
        if (activity?.javaClass?.name != ACTIVITY) return Info()
        val presenter = field(activity, "a")
        val detailHolder = field(presenter, "x")
        val detail = field(detailHolder, "b")?.takeIf(::isDetailModel)
        rememberDetail(detail)
        val videoId = seriesId(call(engine, "getVideoID"))

        val current = listOf("I", "z")
            .mapNotNull { key -> field(presenter, key) }
            .filter { it.javaClass.name == DATA }
        current.forEach { observeVideoData(it, nativeConsumer = false) }

        // A presenter can still hold the LAST drama's current/next model.
        // Verify exact video identity before using either one.
        val active = if (videoId != null) current.firstOrNull {
            vid(it) == videoId
        } else null
        val observed = observedForEngine(engine)
        val fragment = mediaFromActiveFragment(activity,
            seriesKey = null, total = null, engineVideoId = videoId)

        // The engine's getVideoID is NOT directly comparable to the
        // official presenter SaasVideoData.vid on all video routes.
        // Accept a unique presenter model only if it has an independently
        // captured detail for the same series. Across a different native
        // engine in the SAME Activity, its model video ID must also advance.
        // That extra condition prevents the final-episode autoplay from
        // recycling the PREVIOUS drama's title/poster.
        val distinctCandidates = current.mapNotNull(::vid).distinct()
        val presenterModel = current.firstOrNull()?.takeIf {
            distinctCandidates.size == 1
        }
        val presenterVideoId = vid(presenterModel)
        val presenterSeriesId =
            seriesId(field(presenterModel, "episodesId")) ?:
            seriesId(field(presenterModel, "seriesId"))
        val lastInSameActivity = boundPresenterActivity.get() === activity
        val newEngineSameActivity = lastInSameActivity &&
            boundPresenterEngine.get() != null &&
            boundPresenterEngine.get() !== engine
        val modelAdvanced = !newEngineSameActivity ||
            boundPresenterVideoId == null ||
            presenterVideoId != boundPresenterVideoId
        val presenterDetail = detailForSeries(presenterSeriesId)
        val safePresenter = if (active == null &&
            presenterVideoId != null && presenterSeriesId != null &&
            presenterDetail != null && modelAdvanced) presenterModel else null
        val playingModel = active ?: safePresenter
        if (safePresenter != null) {
            LogUtil.info("系统媒体: 全集使用当前 Presenter 及匹配详情" +
                " engineIdComparable=false" +
                " modelAdvanced=$modelAdvanced" +
                " seriesId=true")
            LogUtil.incr("systemMediaPresenterDetailFallback")
        }
        val matchedSeriesKey =
            seriesId(field(playingModel, "episodesId")) ?:
            seriesId(field(playingModel, "seriesId")) ?:
            fragment.seriesId ?: observed.seriesId
        val detailKey = seriesId(detailField(detail, "episodesId")) ?:
            seriesId(detailField(detail, "seriesId"))

        // An engine that identifies its current video must NOT fall back
        // to stale presenter.x.b, intent extras or old cached title.
        // With no trustworthy matching DTO, temporarily publish blanks.
        if (videoId != null && matchedSeriesKey == null) {
            val independent = recentNativeHandoff(activity, engine)
            if (independent != null) return independent
            // A SaasVideoData matched by exact video ID can still carry a
            // correct title/poster even if the backend omitted episodesId.
            // Use those fields directly; NEVER substitute old Activity
            // detail or a prior series cache.
            val titleByVideoId = str(field(active, "seriesName")) ?:
                fragment.title ?: observed.title
            val sourceCover = field(active, "cover") as? String
            val frameCover = field(active, "episodeCover") as? String
            val artworkByVideoId = listOfNotNull(
                sourceCover?.takeIf {
                    it.isNotBlank() && it != frameCover
                }, fragment.artworkUrl, observed.artworkUrl
            ).firstNotNullOfOrNull(MediaArtworkLoader::normalize)
            val episodeByVideoId =
                episodeFromIndex(active, null) ?: fragment.episode
            val matchedByVideoId = titleByVideoId != null ||
                artworkByVideoId != null || episodeByVideoId != null
            val now = SystemClock.uptimeMillis()
            if (now - lastDiagnosticAt > 4000L) {
                lastDiagnosticAt = now
                LogUtil.info("系统媒体: 全集新视频剧集 ID 未就绪" +
                    " engineVideoId=true candidateCount=" + current.size +
                    " detailPresent=" + (detailKey != null) +
                    " videoMatched=" + (active != null ||
                        fragment.videoId == videoId ||
                        observed.title != null || observed.artworkUrl != null) +
                    " safeMetadata=$matchedByVideoId")
                LogUtil.incr("systemMediaSeriesUnverifiedTransition")
                FeatureTrace.event("media", "series_candidate", "degraded",
                    "presenterCount=" + current.size +
                        ",detailPresent=" + (detailKey != null) +
                        ",videoMatched=" + (active != null) +
                        ",safeMetadata=" + matchedByVideoId)
            }
            return Info(titleByVideoId, episodeByVideoId,
                if (matchedByVideoId) "series-video-id-only"
                else "series-video-unverified",
                artworkByVideoId)
        }

        // With a blank engine ID, a single truly visible active fragment
        // is better evidence than a recycled detail. Otherwise the detail
        // remains an acceptable fallback for the initial series.
        val seriesKey = matchedSeriesKey ?: detailKey
        val trustedDetail = detail?.takeIf {
            detailKey != null && detailKey == seriesKey
        }
        val staleDetail = detailKey != null && seriesKey != null &&
            detailKey != seriesKey
        val captured = detailForSeries(seriesKey)

        val total = listOfNotNull(
            number(field(playingModel, "episodesCount")),
            fragment.total,
            number(detailField(trustedDetail, "episodeTotalCnt")),
            number(detailField(trustedDetail, "episodeCnt")),
            captured?.total,
            if (videoId == null)
                number(field(current.firstOrNull(), "episodesCount"))
            else null
        ).firstOrNull { it in 1..5000L }

        val intentTitle = if (videoId == null && !staleDetail) {
            listOf("episodesTitle", "seriesName", "series_name",
                "bookName", "book_name", "shortSeriesName", "dramaName")
                .firstNotNullOfOrNull { key ->
                    try { str(activity.intent?.getStringExtra(key)) }
                    catch (_: Throwable) { null }
                }
        } else null
        val freshTitle =
            str(field(playingModel, "seriesName")) ?:
            fragment.title ?:
            captured?.title ?:
            str(detailField(trustedDetail, "episodesTitle")) ?:
            intentTitle ?:
            str(field(playingModel, "title")) ?:
            (if (videoId == null)
                str(field(current.firstOrNull(), "seriesName"))
            else null) ?: observed.title

        val model = playingModel ?: if (videoId == null)
            current.firstOrNull() else null
        val cover = field(model, "cover") as? String
        val episodeCover = field(model, "episodeCover") as? String
        val nativeSeriesCover = cover?.takeIf {
            it.isNotBlank() && it != episodeCover
        }
        val verifiedPoster = listOfNotNull(
            detailField(trustedDetail, "episodesCover") as? String,
            detailField(trustedDetail, "smallEpisodesCover") as? String,
            captured?.artworkUrl
        ).firstNotNullOfOrNull { MediaArtworkLoader.normalize(it) }
        val freshCover = verifiedPoster ?: listOfNotNull(
            nativeSeriesCover, fragment.artworkUrl, observed.artworkUrl
        ).firstNotNullOfOrNull { MediaArtworkLoader.normalize(it) }
        val retained = preserveCurrentSeries(activity, seriesKey,
            freshTitle, total, freshCover, verifiedPoster != null)
        val seriesTitle = retained.first
        val stableTotal = retained.second

        // The outer pager is only episode-index evidence when it is
        // aligned with a detail model for the SAME actual playing series.
        val pager = if (trustedDetail != null)
            pagerState(presenter, trustedDetail, stableTotal)
            else PagerState()
        val episode = episodeFromIndex(playingModel, stableTotal)
            ?: indexFromList(trustedDetail, videoId)?.let { index ->
                "第" + index + "集" +
                    (if (stableTotal != null && stableTotal >= index)
                        " · 全" + stableTotal + "集" else "")
            }
            ?: fragment.episode
            ?: episodeFromPager(pager, stableTotal)
            ?: stableTotal?.let { "全" + it + "集" }

        if (playingModel != null && presenterVideoId != null && engine != null) {
            boundPresenterActivity = WeakReference(activity)
            boundPresenterEngine = WeakReference(engine)
            boundPresenterVideoId = vid(playingModel)
            lastMatchedActivity = WeakReference(activity)
            lastMatchedVideoId = boundPresenterVideoId
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastDiagnosticAt > 9000L) {
            lastDiagnosticAt = now
            LogUtil.info("系统媒体: 全集播放身份校验" +
                " videoId=" + (videoId != null) +
                " presenterMatched=" + (active != null) +
                " presenterFallback=" + (safePresenter != null) +
                " modelAdvanced=$modelAdvanced" +
                " fragmentMatched=" + (fragment.videoId != null &&
                    (videoId == null || fragment.videoId == videoId)) +
                " observedMatched=" + (observed.seriesId != null) +
                " seriesId=" + (seriesKey != null) +
                " staleDetail=$staleDetail" +
                " cacheCover=" + (cachedCoverUrl != null) +
                " title=" + (seriesTitle != null) +
                " episode=" + (episode != null))
            LogUtil.incr("systemMediaSeriesPlaybackIdentity")
            FeatureTrace.event("media", "series_candidate",
                if (seriesTitle != null) "bound" else "degraded",
                "active=" + (active != null) +
                    ",fallback=" + (safePresenter != null) +
                    ",staleDetail=" + staleDetail +
                    ",episode=" + (episode != null) +
                    ",poster=" + (cachedCoverUrl != null))
        }
        return Info(
            title = seriesTitle,
            episode = episode,
            source = when {
                active != null -> "saas-current-video-verified"
                safePresenter != null -> "saas-presenter-matched-detail"
                fragment.videoId != null -> "saas-visible-fragment-verified"
                observed.seriesId != null -> "saas-observed-video-id"
                trustedDetail != null -> "saas-current-detail"
                else -> "none"
            },
            artworkUrl = cachedCoverUrl,
            seriesId = seriesKey
        )
    }

}
