package xyz.kejiyu.hongguo.hooks

import android.graphics.Bitmap
import android.media.MediaMetadata

/**
 * One immutable presentation snapshot for MediaSession and the notification.
 * Its title/subtitle already passed through MediaPresentationState; callers
 * cannot accidentally mix a new title with an old transition fallback.
 */
internal data class MediaCardSnapshot(
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val artwork: Bitmap?
) {
    val signature: String get() =
        title.length.toString() + ":" + title +
        "|" + subtitle.length + ":" + subtitle + "|" + durationMs

    fun toPlatformMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "红果短剧")
        if (durationMs > 0L) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        }
        val safeArtwork = artwork?.takeUnless { it.isRecycled }
        if (safeArtwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, safeArtwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, safeArtwork)
        }
        return builder.build()
    }
}
