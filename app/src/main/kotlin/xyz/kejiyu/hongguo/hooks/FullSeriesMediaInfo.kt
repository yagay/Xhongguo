package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.SystemClock
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
        val source: String = "none"
    )

    private const val ACTIVITY =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val DATA =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"
    private const val DETAIL =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDetailModel"

    private var lastDiagnosticAt = 0L

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

        val total = listOfNotNull(
            number(field(active, "episodesCount")),
            number(field(detail, "episodeTotalCnt")),
            number(field(detail, "episodeCnt"))
        ).firstOrNull { it in 1..5000L }

        // Detail metadata belongs to this ShortSeriesActivity rather than an
        // unrelated home recommendation; its title can be shown even if the
        // exact episode is not yet available from the active video model.
        val seriesTitle =
            str(field(active, "seriesName")) ?:
            str(field(detail, "episodesTitle")) ?:
            str(field(active, "title")) ?:
            str(field(current.firstOrNull(), "seriesName"))

        // Reading the active SaasVideoData is O(1). Avoid scanning hundreds
        // of playlist items on every ~1.4s media timeline tick.
        val fromActive = episodeFromIndex(active, total)
        val episode = if (fromActive != null) fromActive else {
            val episodeNumber = indexFromList(detail, videoId)
            if (episodeNumber != null) {
                "第" + episodeNumber + "集" +
                    (if (total != null && total >= episodeNumber)
                        " · 全" + total + "集" else "")
            } else null
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastDiagnosticAt > 12000L) {
            lastDiagnosticAt = now
            LogUtil.info("系统媒体: 全集原生元数据解析 presenter=" +
                (presenter != null) + " detail=" + (detail != null) +
                " activeVideoId=" + (videoId != null) +
                " candidateCount=" + current.size +
                " videoMatched=" + (active != null) +
                " title=" + (seriesTitle != null) +
                " episode=" + (episode != null) +
                " total=" + (total != null))
            LogUtil.incr("systemMediaFullSeriesNativeProbe")
        }
        return Info(seriesTitle, episode,
            if (active != null) "saas-current-video" else if (detail != null)
                "saas-series-detail" else "none")
    }
}
