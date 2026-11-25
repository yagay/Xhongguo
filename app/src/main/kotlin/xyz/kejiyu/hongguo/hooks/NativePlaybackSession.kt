package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import xyz.kejiyu.hongguo.LogUtil

/**
 * Read-only authority for the current confirmed TTVideoEngine. A play call
 * is a request, not a playing event. All commits and callbacks run on main.
 * No play, pause, Surface, release or Activity calls originate here.
 */
internal object NativePlaybackSession {
    private const val ENGINE = "com.ss.ttvideoengine.TTVideoEngine"
    private const val MAIN = "com.dragon.read.pages.main.MainFragmentActivity"
    private const val SERIES = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private val main = Handler(Looper.getMainLooper())
    private data class Snapshot(val engine: WeakReference<Any>,
        val owner: WeakReference<Activity>, val videoId: String?, val playing: Boolean,
        val generation: Long)
    @Volatile private var current: Snapshot? = null
    private var serial = 0L
    private var pending = WeakReference<Any>(null)
    private var pendingOwner = WeakReference<Activity>(null)

    fun isEngine(candidate: Any?): Boolean = candidate != null &&
        NativeMetadataAccess.extendsType(candidate.javaClass, ENGINE)
    fun state(candidate: Any?): Int? {
        if (!isEngine(candidate) || NativeMetadataAccess.call(candidate, "isReleased") == true) return null
        return (NativeMetadataAccess.call(candidate, "getPlaybackState") as? Number)?.toInt()
    }
    fun playing(candidate: Any?) = state(candidate) == 1
    fun engine(): Any? = current?.engine?.get()?.takeIf {
        NativeMetadataAccess.call(it, "isReleased") != true
    }
    fun owner(): Activity? = current?.owner?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
    fun currentFor(activity: Activity?): Any? =
        if (activity != null && owner() === activity) engine() else null
    fun generation(): Long = current?.generation ?: -1L
    fun videoId(engine: Any?): String? =
        (NativeMetadataAccess.call(engine, "getVideoID") as? String)
            ?.takeIf { it.isNotBlank() && it != "null" }

    fun boundEngine(candidate: Any?): Any? {
        return NativeBusinessResolver.boundEngine(candidate)
    }
    fun matches(candidate: Any?, engine: Any): Boolean = boundEngine(candidate) === engine

    private fun player(a: Activity?, engine: Any? = null) =
        NativeBusinessResolver.page(a, engine) != NativeBusinessResolver.Page.UNKNOWN

    fun observe(candidate: Any?, action: String, activity: Activity?,
                foreground: () -> Activity?,
                emit: (Any, String, Activity?) -> Unit) {
        if (!isEngine(candidate) || candidate == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val ref = WeakReference(candidate)
            val page = WeakReference(activity)
            main.post { ref.get()?.let { observe(it, action, page.get(), foreground, emit) } }
            return
        }
        if (action == "play" || action == "start") {
            val page = activity?.takeIf { player(it, candidate) }
                ?: owner()?.takeIf { engine() === candidate } ?: return
            val previous = current
            val id = videoId(candidate)
            if (previous?.engine?.get() === candidate && previous.owner.get() === page &&
                previous.playing && previous.videoId == id && playing(candidate)) return
            val ticket = ++serial
            pending = WeakReference(candidate)
            pendingOwner = WeakReference(page)
            val weak = WeakReference(candidate)
            val weakPage = WeakReference(page)
            fun check() {
                if (ticket != serial) return
                val actual = weak.get() ?: return
                val a = weakPage.get() ?: return
                if (!player(a, actual)) return
                val front = foreground()
                if (front !== a && (engine() !== actual || player(front, actual))) return
                if (!playing(actual)) return
                val before = current
                val vid = videoId(actual)
                val same = before?.engine?.get() === actual &&
                    before.owner.get() === a && before.videoId == vid && before.playing
                pending.clear()
                pendingOwner.clear()
                ++serial
                if (same) return
                val epoch = serial
                current = Snapshot(WeakReference(actual), WeakReference(a), vid, true, epoch)
                LogUtil.info("播放会话: confirmed generation=$epoch page=" + a.javaClass.simpleName +
                    " engine=" + System.identityHashCode(actual))
                emit(actual, "play", a)
            }
            check()
            if (ticket == serial) for (delay in longArrayOf(100L, 350L, 900L, 1800L, 3500L)) {
                main.postDelayed({ check() }, delay)
            }
            return
        }
        if (action !in setOf("pause", "stop", "releaseAsync", "release")) return
        if (pending.get() === candidate) {
            ++serial
            pending.clear()
            pendingOwner.clear()
        }
        val active = current ?: return
        if (active.engine.get() !== candidate) return
        if (action.startsWith("release")) current = null
        else current = active.copy(playing = false)
        if (action != "pause" || active.playing)
            emit(candidate, action, active.owner.get())
    }

    fun onDestroyed(activity: Activity?) {
        if (activity == null) return
        if (pendingOwner.get() === activity) {
            ++serial
            pending.clear()
            pendingOwner.clear()
        }
        // A live audio engine may outlive its Activity; do not release it.
        if (current?.owner?.get() === activity)
            LogUtil.info("播放会话: 原页面销毁，等待原生引擎释放")
    }
}
