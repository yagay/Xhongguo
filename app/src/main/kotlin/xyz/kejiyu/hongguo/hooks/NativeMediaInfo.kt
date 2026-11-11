package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.ArrayDeque

/**
 * Only read labels actually displayed by the CURRENT Hongguo player. The
 * 7.3.5.32 home feed uses mostly ID-less TextViews; requiring a named
 * "series_title" id returned null for every tested video.
 *
 * The home fallback is deliberately constrained to the lower-left VIDEO
 * overlay, above the full-series action and bottom tabs. Do not scan hidden
 * recommendations or other pages for arbitrary titles.
 */
internal object NativeMediaInfo {
    data class Info(
        val title: String? = null,
        val episode: String? = null,
        val artwork: Bitmap? = null,
        val source: String = "none"
    )
    private data class Candidate(
        val text: String, val x: Float, val y: Float,
        val width: Float, val font: Float, val id: String?
    )
    private val titleKeys = listOf(
        "series_title", "series_name", "short_series_name", "short_drama_title",
        "book_name", "book_title", "album_name", "album_title", "video_title",
        "drama_title", "play_title", "shortvideo_title", "bookName",
        "bookTitle", "seriesName", "seriesTitle", "albumName", "title"
    )
    private val titleIdKeys = listOf(
        "series_title", "series_name", "book_name", "book_title",
        "album_title", "drama_title", "video_title", "video_name",
        "seriesname", "bookname", "video_title_name", "short_video_title"
    )
    private val episodeIdKeys = listOf(
        "episode_title", "episode_name", "episode_number", "episode_index",
        "current_episode", "current_ep", "chapter_title", "chapter_number"
    )
    private val coverIdKeys = listOf(
        "series_cover", "book_cover", "album_cover", "drama_cover",
        "series_poster", "album_poster", "cover_image", "cover_view"
    )
    private val episodePattern = Regex(
        """(?:^|[\s·•|：:/])((?:第\s*[0-9一二三四五六七八九十百千]+\s*集)|(?:EP\s*\.?\s*\d+)|(?:Episode\s+\d+))(?=$|[\s·•|：:/—\-])""",
        RegexOption.IGNORE_CASE
    )
    private val totalPattern = Regex("""(?:全|共)\s*\d+\s*集""")
    private val forbidden = listOf(
        "首页", "剧场", "我的", "商城", "搜索", "设置", "评论",
        "全部", "收藏", "推荐", "分享", "已完结", "继续播放",
        "播放", "加载中", "不感兴趣", "红果短剧", "在线观看",
        "观看完整", "追剧", "红果首发", "演员", "点赞",
        "会员", "同款", "开启弹幕", "查看评论"
    )

    private fun clean(value: String?): String? {
        val t = value?.replace('\n', ' ')?.trim()?.replace(Regex("\\s+"), " ")
            ?.take(110) ?: return null
        return t.takeIf {
            it.isNotEmpty() && !it.contains("http://") && !it.contains("https://")
        }
    }
    private fun parseTitle(value: String?): String? {
        val t = clean(value) ?: return null
        if (t.length !in 4..74 || forbidden.any {
                t == it || t.startsWith(it) || (it == "观看完整" && t.contains(it))
            } || t.contains("用户协议") || t.contains("隐私政策")
            || t.matches(Regex("""[0-9.\s万+]+"""))
            || episodePattern.containsMatchIn(t)
            || t.contains("全集") || t.contains("第") && t.contains("集")
            || t.contains("·") && t.length < 24
            || t.contains("都市") && t.length < 15) return null
        return t
    }
    private fun parseEpisode(value: String?): String? {
        val t = clean(value) ?: return null
        return episodePattern.find(t)?.groups?.get(1)?.value?.replace(" ", "")
    }

    private fun id(view: View): String? = try {
        if (view.id == View.NO_ID) null
        else view.resources.getResourceEntryName(view.id).lowercase()
    } catch (_: Throwable) { null }

    private fun cover(image: ImageView): Bitmap? {
        return try {
            val src = (image.drawable as? BitmapDrawable)?.bitmap ?: return null
            if (src.width < 72 || src.height < 72 || src.isRecycled) return null
            if (src.width <= 320 && src.height <= 320) src
            else {
                val ratio = minOf(320f / src.width, 320f / src.height)
                Bitmap.createScaledBitmap(
                    src, (src.width * ratio).toInt().coerceAtLeast(1),
                    (src.height * ratio).toInt().coerceAtLeast(1), true
                )
            }
        } catch (_: Throwable) { null }
    }

    private fun homeScore(c: Candidate): Int {
        val text = parseTitle(c.text) ?: return -100
        if (c.x > .66f || c.y !in 0.58f..0.85f) return -100
        if (text.contains("广告") || text.contains("福利") ||
            text.contains("短剧推荐") || text.contains("热门") ||
            text.contains("播放量")) return -100
        var score = 0
        if (c.y in 0.67f..0.80f) score += 6
        else if (c.y in 0.80f..0.84f) score += 1
        if (c.x < .28f) score += 3
        if (c.font >= 17f) score += 6
        else if (c.font >= 14f) score += 3
        if (text.length in 7..55) score += 4
        if (c.width > .25f) score += 2
        if (c.id != null && titleIdKeys.any { c.id.contains(it) }) score += 12
        if (text.contains("·") || text.contains(" ")) score -= 4
        if (text.contains("都市") || text.contains("现代") ||
            text.contains("玄幻") || text.contains("言情")) score -= 9
        return score
    }

    private fun seriesScore(c: Candidate): Int {
        val text = parseTitle(c.text) ?: return -100
        if (c.x > .76f || c.y > .85f) return -100
        var score = 0
        if (c.id != null && titleIdKeys.any { c.id.contains(it) }) score += 14
        if (c.font >= 18f) score += 6
        else if (c.font >= 14f) score += 3
        if (c.y < .25f || c.y in 0.63f..0.80f) score += 4
        if (text.length in 7..55) score += 4
        if (text.contains("·")) score -= 5
        return score
    }

    fun read(activity: Activity?): Info {
        if (activity == null || activity.isFinishing ||
            activity.packageName !in setOf("com.phoenix.read", "com.phoenix.read.oversea.gp"))
            return Info()
        val className = activity.javaClass.name
        val isHome = className.contains("MainFragmentActivity")
        val isSeries = className.contains("ShortSeries") ||
            className.contains("VideoAlbum")
        if (!isHome && !isSeries) return Info()

        var title: String? = null
        var episode: String? = null
        var total: String? = null
        var artwork: Bitmap? = null
        var source = "none"
        // The home feed updates in-place as the user swipes. Its Activity
        // launch Intent is NOT a trustworthy identity for the current video.
        if (isSeries) {
            for (key in titleKeys) {
                val raw = try { activity.intent?.getStringExtra(key) }
                    catch (_: Throwable) { null }
                title = parseTitle(raw)
                if (title != null) { source = "intent"; break }
            }
        }

        val root = try { activity.window?.decorView } catch (_: Throwable) { null }
            ?: return Info(title, episode, artwork, source)
        val bounds = Rect()
        val rootBounds = Rect()
        if (!root.getGlobalVisibleRect(rootBounds) ||
            rootBounds.width() <= 0 || rootBounds.height() <= 0)
            return Info(title, episode, artwork, source)
        val candidates = ArrayList<Candidate>()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var examined = 0
        while (queue.isNotEmpty() && examined++ < 2200) {
            val view = queue.removeFirst()
            if (!view.isShown || !view.getGlobalVisibleRect(bounds)) continue
            if (view is TextView) {
                val v = clean(view.text?.toString())
                if (!v.isNullOrBlank()) {
                    val name = id(view)
                    val c = Candidate(v,
                        (bounds.left - rootBounds.left).toFloat() / rootBounds.width(),
                        (bounds.centerY() - rootBounds.top).toFloat() / rootBounds.height(),
                        bounds.width().toFloat() / rootBounds.width(),
                        view.textSize / view.resources.displayMetrics.scaledDensity,
                        name
                    )
                    candidates.add(c)
                    if (title == null && name != null &&
                        titleIdKeys.any { name.contains(it) }) {
                        val validated = parseTitle(v)
                        if (validated != null) { title = validated; source = "named-view" }
                    }
                    if (episode == null && (name == null ||
                        episodeIdKeys.any { name.contains(it) })) {
                        episode = parseEpisode(v)
                    }
                    if (total == null) total = totalPattern.find(v)?.value
                }
            } else if (view is ImageView && artwork == null) {
                val name = id(view)
                if (name != null && coverIdKeys.any { name.contains(it) })
                    artwork = cover(view)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child != null) queue.addLast(child)
                }
            }
        }
        if (title == null) {
            val best = candidates.maxByOrNull {
                if (isHome) homeScore(it) else seriesScore(it)
            }
            val bestScore = best?.let {
                if (isHome) homeScore(it) else seriesScore(it)
            } ?: -100
            if (best != null && bestScore >= (if (isHome) 14 else 17)) {
                title = parseTitle(best.text)
                source = if (isHome) "home-video-overlay" else "series-video-overlay"
            }
        }
        if (episode != null && total != null) episode += " · " + total
        return Info(title, episode, artwork, source)
    }
}
