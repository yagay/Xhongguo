package xyz.kejiyu.hongguo.hooks

import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import xyz.kejiyu.hongguo.LogUtil

/** Match the original VideoShop PlayEntity to the CURRENT TTVideoEngine. */
internal object NativePlayEntityInfo {
    data class Info(val title: String? = null,
        val episode: String? = null, val source: String = "none",
        val artworkUrl: String? = null)

    private val tracked = LinkedHashMap<String, WeakReference<Any>>()
    private val lock = Any()
    private var observed = 0
    private var matched = 0
    private var lastDebugAt = 0L

    private fun invoke(obj: Any, name: String): Any? = try {
        obj.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        }?.invoke(obj)
    } catch (_: Throwable) { null }

    private fun id(obj: Any, key: String): String? =
        invoke(obj, key)?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it != "null" && it != "0"
        }

    private fun safeName(raw: Any?): String? {
        val str = (raw as? CharSequence)?.toString()?.trim()
            ?.replace(Regex("\\s+"), " ")?.take(90) ?: return null
        if (str.length !in 3..75 || str in setOf(
                "红果短剧", "短剧", "首页", "正在播放的视频"
            ) || str.matches(Regex("[0-9a-fA-F]{16,}")) ||
            str.startsWith("http") || str.contains("://")) return null
        return str
    }

    private fun firstName(obj: Any?, methods: List<String>): String? {
        if (obj == null) return null
        for (name in methods) {
            safeName(invoke(obj, name))?.let { return it }
        }
        return null
    }

    private fun episodeText(obj: Any?): String? {
        if (obj == null) return null
        for (method in listOf("getChapterName", "getEpisodeName",
                "getEpisodeTitle", "getCurrentEpisodeTitle", "getChapterTitle")) {
            val value = safeName(invoke(obj, method)) ?: continue
            val explicit = Regex("第\\s*\\d+\\s*集").find(value)?.value
            if (explicit != null) return explicit
            if (value.length < 35) return value
        }
        for (method in listOf("getEpisodeNum", "getEpisodeNumber",
                "getChapterNumber", "getChapterNum", "getCurrentEpisode")) {
            val n = (invoke(obj, method) as? Number)?.toInt() ?: continue
            if (n in 1..5000) return "第" + n + "集"
        }
        return null
    }

    private fun fromBundle(bundle: Bundle): Info {
        var title: String? = null
        var episode: String? = null
        for (key in listOf("book_name", "bookName", "series_name",
                "seriesName", "drama_name", "dramaName")) {
            title = safeName(bundle.getString(key))
            if (title != null) break
        }
        for (key in listOf("chapter_name", "episode_name",
                "chapterName", "episodeName")) {
            episode = safeName(bundle.getString(key))
            if (episode != null) break
        }
        if (episode == null) {
            for (key in listOf("chapter_num", "episode_number", "episodeNum")) {
                val n = try { bundle.getInt(key, 0) } catch (_: Throwable) { 0 }
                if (n in 1..5000) {
                    episode = "第" + n + "集"
                    break
                }
            }
        }
        // Only explicit series-cover keys. "cover"/"episodeCover" may
        // refer to a frame of the current video, NOT its official poster.
        val artUrl = listOf("episodes_cover", "episodesCover",
            "smallEpisodesCover", "small_episodes_cover")
            .firstNotNullOfOrNull { key ->
                bundle.getString(key)?.takeIf { it.isNotBlank() }
            }
        return Info(title, episode, "play-entity-bundle", artUrl)
    }

    private fun metadata(entity: Any): Info {
        val business = invoke(entity, "getBusinessModel")
        val title = firstName(business, listOf(
            "getBookName", "getSeriesName", "getShortSeriesName",
            "getDramaName", "getBookTitle", "getSeriesTitle", "getTitle"
        )) ?: firstName(entity, listOf("getTitle"))
        val embeddedEpisode = title?.let {
            Regex("第\\s*[0-9一二三四五六七八九十百千]+\\s*集").find(it)?.value
        }
        val cleanTitle = if (embeddedEpisode != null && title != null) {
            safeName(title.substringBefore(embeddedEpisode).trim(' ', '·', '-', '|', '：'))
        } else title
        val episode = episodeText(business) ?: embeddedEpisode
        val extras = (invoke(entity, "getBundle") as? Bundle)?.let {
            fromBundle(it)
        } ?: Info()
        val artUrl = listOf("getEpisodesCover", "getSmallEpisodesCover")
            .firstNotNullOfOrNull { method ->
                (invoke(business ?: entity, method) as? String)
                    ?.takeIf { it.isNotBlank() }
            } ?: extras.artworkUrl
        return Info(cleanTitle ?: extras.title, episode ?: extras.episode,
            "play-entity", artUrl)
    }

    fun remember(entity: Any?) {
        if (entity?.javaClass?.name != "com.ss.android.videoshop.entity.PlayEntity")
            return
        val videoId = id(entity, "getVideoId") ?: return
        synchronized(lock) {
            tracked[videoId] = WeakReference(entity)
            observed++
            while (tracked.size > 80) {
                val iterator = tracked.keys.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                } else break
            }
        }
    }

    fun readForEngine(engine: Any?, videoContext: Any?,
        controller: Any?, layer: Any?): Info {
        if (engine == null) return Info()
        val engineId = id(engine, "getVideoID")
        val sources = listOf(
            videoContext to "getPlayEntity",
            controller to "getPlayEntity",
            layer to "getBindPlayEntity"
        )
        var found: Any? = null
        for ((owner, method) in sources) {
            if (owner == null) continue
            val entity = invoke(owner, method) ?: continue
            remember(entity)
            val entityId = id(entity, "getVideoId")
            val boundEngine = invoke(owner, "getVideoEngine")
            if (entityId != null && engineId != null && entityId == engineId ||
                boundEngine === engine &&
                    entity.javaClass.name == "com.ss.android.videoshop.entity.PlayEntity") {
                found = entity
                break
            }
        }
        if (found == null && engineId != null) synchronized(lock) {
            found = tracked[engineId]?.get()
        }
        val info = found?.let { metadata(it) } ?: Info()
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDebugAt > 15000) {
            lastDebugAt = now
            if (found != null) matched++
            LogUtil.info("系统媒体: 原生 PlayEntity 匹配=" + (found != null) +
                " engineVideoId=" + (engineId != null) +
                " title=" + (info.title != null) +
                " episode=" + (info.episode != null) +
                " observed=" + observed + " matched=" + matched)
            LogUtil.incr("systemMediaPlayEntityProbe")
        }
        return info
    }
}
