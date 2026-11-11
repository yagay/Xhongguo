package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * Extracts only explicit series/episode labels from the current Hongguo UI.
 * Never guesses a title from random screen text or a cached recommendation.
 */
internal object NativeMediaInfo {
    data class Info(
        val title: String? = null,
        val episode: String? = null,
        val artwork: Bitmap? = null,
    )

    private val titleKeys = listOf(
        "series_title", "series_name", "short_series_name", "short_drama_title",
        "book_name", "book_title", "album_name", "album_title", "video_title",
        "drama_title", "play_title", "shortvideo_title",
    )
    private val titleIdKeys = listOf(
        "series_title", "series_name", "book_name", "book_title",
        "album_title", "drama_title", "video_title", "video_name",
    )
    private val episodeIdKeys = listOf(
        "episode_title", "episode_name", "episode_number", "episode_index",
        "current_episode", "current_ep", "chapter_title",
    )
    private val coverIdKeys = listOf(
        "series_cover", "book_cover", "album_cover", "drama_cover",
        "series_poster", "album_poster",
    )
    private val episodePattern = Regex(
        """(?:^|[\s·•])(?:第\s*[0-9一二三四五六七八九十百千]+\s*集|EP\s*\.?\s*\d+|Episode\s+\d+)(?=$|[\s·•/])""",
        RegexOption.IGNORE_CASE
    )
    private val forbidden = setOf(
        "首页", "剧场", "我的", "商城", "搜索", "设置", "评论",
        "全部", "收藏", "推荐", "分享", "已完结", "继续播放",
        "播放", "加载中", "不感兴趣", "红果短剧", "在线观看"
    )

    private fun clean(value: String?): String? {
        val t = value?.replace('\n', ' ')?.trim()?.replace(Regex("\\s+"), " ")
            ?.take(90) ?: return null
        return t.takeIf { it.isNotEmpty() && !it.contains("http://") &&
            !it.contains("https://") }
    }

    private fun parseTitle(value: String?): String? {
        val t = clean(value) ?: return null
        if (t.length !in 2..50 || t in forbidden || t.startsWith("第") &&
            t.endsWith("集") || t.contains("隐私") || t.contains("用户协议")) return null
        return t
    }

    private fun parseEpisode(value: String?): String? {
        val t = clean(value) ?: return null
        return episodePattern.find(t)?.value?.trim(' ', '·', '•')
    }

    private fun name(view: View): String? = try {
        if (view.id == View.NO_ID) null
        else view.resources.getResourceEntryName(view.id).lowercase()
    } catch (_: Throwable) { null }

    private fun iconBitmap(image: ImageView): Bitmap? {
        return try {
            val drawable = image.drawable as? BitmapDrawable ?: return null
            val source = drawable.bitmap ?: return null
            if (source.width < 72 || source.height < 72) return null
            val max = 320f
            if (source.width <= 320 && source.height <= 320) source
            else {
                val scale = minOf(max / source.width, max / source.height)
                Bitmap.createScaledBitmap(source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true)
            }
        } catch (_: Throwable) { null }
    }

    fun read(activity: Activity?): Info {
        if (activity == null || activity.isFinishing || !activity.javaClass.name
                .startsWith("com.dragon.read.")) return Info()

        var title: String? = null
        var episode: String? = null
        var artwork: Bitmap? = null

        // Intent extras are used only when their key unambiguously identifies
        // the active short-series title.
        for (key in titleKeys) {
            val candidate = try { activity.intent?.getStringExtra(key) }
                catch (_: Throwable) { null }
            title = parseTitle(candidate)
            if (title != null) break
        }
        val decor = try { activity.window?.decorView } catch (_: Throwable) { null }
        if (decor != null) {
            val pending = java.util.ArrayDeque<View>()
            pending.add(decor)
            var scanned = 0
            while (pending.isNotEmpty() && scanned++ < 2200 &&
                (title == null || episode == null || artwork == null)) {
                val view = pending.removeFirst()
                if (!view.isShown) continue
                val id = name(view)
                when (view) {
                    is TextView -> {
                        val text = view.text?.toString()
                        if (title == null && id != null &&
                            titleIdKeys.any { id.contains(it) }) {
                            title = parseTitle(text)
                        }
                        if (episode == null) {
                            val matched = parseEpisode(text)
                            if (matched != null && (id == null ||
                                episodeIdKeys.any { id.contains(it) } ||
                                (text?.trim()?.length ?: 100) < 28)) episode = matched
                        }
                    }
                    is ImageView -> if (artwork == null && id != null &&
                        coverIdKeys.any { id.contains(it) }) {
                        artwork = iconBitmap(view)
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child != null) pending.addLast(child)
                    }
                }
            }
        }
        return Info(title, episode, artwork)
    }
}
