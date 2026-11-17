package xyz.kejiyu.hongguo.hooks

import android.app.ActivityManager
import xyz.kejiyu.hongguo.FeatureTrace
import java.lang.ref.WeakReference

/**
 * Source-boundary proof for a future Activity-independent native handoff.
 *
 * Verified in the supplied 7.3.5.32 APK:
 *   adapter.e.U1 -> adapter.e.p5 -> adapter.e.J2
 *   -> ShortSeriesApi.openShortSeriesActivityForResult(ShortSeriesLaunchArgs)
 *
 * A ShortSeriesLaunchArgs may include InsertVideoData.videoData (Saas DTO).
 * The host x05.w.c0(SaasVideoData, boolean) contains the original startPlay
 * and VideoModel loading path. We MUST NOT call that method unless the next
 * source is both authoritative and unrelated to the completed old engine.
 *
 * This first stage is intentionally an identity/data preflight ONLY:
 * it never calls a host player method, changes an Activity or modifies
 * the original completion/navigation path. Full series V3 remains the owner.
 */
internal object NativeHeadlessPreflight {
    private const val FEED = "com.dragon.read.component.shortvideo.impl.v2.view.adapter.e"
    private const val VIDEO = "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"
    private const val ARGS = "com.dragon.read.component.shortvideo.api.model.ShortSeriesLaunchArgs"
    private val feedOwner = ThreadLocal<WeakReference<Any>?>()

    private fun inheritedField(source: Any?, fieldName: String): Any? {
        var cls = source?.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val fld = cls.getDeclaredField(fieldName)
                fld.isAccessible = true
                return fld.get(source)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    private fun callZero(source: Any?, name: String): Any? {
        if (source == null) return null
        return try {
            val method = source.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return null
            method.invoke(source)
        } catch (_: Throwable) { null }
    }

    private fun stringField(source: Any?, name: String): String? =
        (inheritedField(source, name) as? String)?.takeIf { it.isNotBlank() }

    private fun intField(source: Any?, name: String): Int? =
        (inheritedField(source, name) as? Number)?.toInt()

    private fun background(): Boolean = try {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
    } catch (_: Throwable) { false }

    fun beginFeedCompletion(adapter: Any?) {
        if (adapter?.javaClass?.name == FEED)
            feedOwner.set(WeakReference(adapter))
    }

    fun endFeedCompletion() {
        feedOwner.remove()
    }

    /** Observe the prepared official launch args at the *data boundary*.
     *
     * A model for the SAME video is not a next-episode source.
     * A next video from a DIFFERENT series is also not a safe substitution.
     */
    fun observeLaunchArgs(args: Any?) {
        val owner = feedOwner.get()?.get() ?: return
        if (args?.javaClass?.name != ARGS) return

        // The native holder is the authoritative selected item. Never use
        // pager neighbour/preloaded data to infer the playing video.
        val currentHolder = callZero(owner, "M0")
        val currentWrapper = callZero(currentHolder, "getBoundData")
        val current = inheritedField(currentWrapper, "mVideoData")
            ?.takeIf { it.javaClass.name == VIDEO }
        val insertion = inheritedField(args, "insertVideoData")
        val insertionWrapper = inheritedField(insertion, "videoData")
        val candidate = callZero(insertionWrapper, "getVideoData")
            ?.takeIf { it.javaClass.name == VIDEO }

        val currentSeries = stringField(current, "seriesId")
        val targetSeries = stringField(candidate, "seriesId")
        val currentVid = stringField(current, "vid")
        val targetVid = stringField(candidate, "vid")
        val currentIndex = intField(current, "indexInList")
        val nextIndex = intField(candidate, "indexInList")

        val seriesMatch = currentSeries != null &&
            currentSeries == targetSeries
        val differentVideo = currentVid != null && targetVid != null &&
            currentVid != targetVid
        val adjacent = currentIndex != null && nextIndex != null &&
            nextIndex == currentIndex + 1

        // Even a matching preflight is NOT permission to call c0(): the
        // existing J2 cleanup and source lifecycle must first be separated.
        val nextSourceProven = seriesMatch && differentVideo && adjacent
        FeatureTrace.event("headless", "native_source_preflight",
            if (nextSourceProven) "observed" else "degraded",
            "background=" + background() +
                ",owner=true,holder=" + (currentHolder != null) +
                ",currentVideo=" + (current != null) +
                ",insertSource=" + (candidate != null) +
                ",sameSeries=$seriesMatch,differentVideo=$differentVideo" +
                ",nextIndex=$adjacent,verified=$nextSourceProven" +
                ",isAutoEnter=" + (inheritedField(args, "isAutoEnter") == true) +
                ",listenMode=" + (inheritedField(args, "isListenMode") == true) +
                ",shareWhenExit=" +
                (inheritedField(args, "enableSharePlayerWhenExit") == true))
    }
}
