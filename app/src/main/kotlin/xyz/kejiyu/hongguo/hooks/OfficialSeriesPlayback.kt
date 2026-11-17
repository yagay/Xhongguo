package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import xyz.kejiyu.hongguo.FeatureTrace
import xyz.kejiyu.hongguo.LogUtil

/**
 * Official 7.3.5.32 short-series playback bridge, independent of VideoShop.
 *
 * One native owner, one selection and one source readiness path:
 * x05.w.onCompletion -> adapter.f.U1 -> adapter.a.s4 -> adapter.a.G4
 * -> x05.w.L -> x05.w.onPrepared -> native progress.
 *
 * The host alone selects the next episode. This bridge NEVER writes D/k,
 * replays r4, calls a raw decoder play(), or pretends a play command is
 * proof of audible playback. A verified selected holder may receive ONE
 * original x05.w.resume() with the host's own no-Surface task flag.
 */
internal object OfficialSeriesPlayback {
    private const val SERIES_ACTIVITY =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val SERIES_ADAPTER =
        "com.dragon.read.component.shortvideo.impl.v2.view.adapter.f"
    private const val SERIES_PLAYER = "x05.w"
    private val handler = Handler(Looper.getMainLooper())
    private val timeline = OfficialSeriesTimeline()
    private var adapter = WeakReference<Any>(null)
    private var currentHolder = WeakReference<Any>(null)
    private var currentEngine = WeakReference<Any>(null)
    private var candidateHolder = WeakReference<Any>(null)
    private var candidateAdapter = WeakReference<Any>(null)
    private var lease: NoSurfaceLease? = null
    private var startAttemptGeneration = -1L
    private var generationAtSelection = -1L
    private var ownerActivity = WeakReference<Activity>(null)
    private val visibilityScope = ThreadLocal<Any?>()
    private val inAutoplay = ThreadLocal<Boolean>()
    private val g4DecisionScope = ThreadLocal<Any?>()
    private val g4GateResult = ThreadLocal<Boolean?>()

    private data class NoSurfaceLease(
        val task: Any,
        val flag: Field,
        val original: Boolean,
        val engine: Any
    )

    private fun field(obj: Any?, name: String): Any? {
        var type = obj?.javaClass
        while (type != null && type != Any::class.java) {
            try {
                val f = type.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    private fun method(obj: Any?, name: String, args: Int): Method? {
        var type = obj?.javaClass
        while (type != null && type != Any::class.java) {
            try {
                val found = type.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == args
                }
                if (found != null) {
                    found.isAccessible = true
                    return found
                }
            } catch (_: Throwable) { return null }
            type = type.superclass
        }
        return null
    }

    private fun invoke(obj: Any?, name: String, args: Int = 0): Any? =
        try { if (obj == null) null else method(obj, name, args)?.invoke(obj) }
        catch (_: Throwable) { null }

    private fun engine(holder: Any?): Any? =
        if (holder?.javaClass?.name == SERIES_PLAYER) invoke(holder, "H")
        else null

    private fun position(a: Any?): Int? =
        (field(a, "D") as? Number)?.toInt()

    private fun page(a: Any?): Int? =
        (invoke(field(a, "k"), "getCurrentItem") as? Number)?.toInt()

    private fun loaded(a: Any?): Int =
        (field(a, "h") as? List<*>)?.size ?: 0

    private fun log(stage: String, status: String, detail: String) {
        FeatureTrace.event("playback", "v2_$stage", status, detail.take(280))
        if (status != "observed" || stage in setOf("g4", "selection", "resume", "progress"))
            LogUtil.info("原生播放V2: $stage $status $detail")
    }

    fun snapshot(): OfficialSeriesTimeline.Snapshot = timeline.snapshot()

    fun isSeriesRoute(): Boolean =
        ownerActivity.get()?.javaClass?.name == SERIES_ACTIVITY &&
            SystemMediaBridge.isOfficialSeriesActivityCurrent()

    fun isSeriesEngine(candidate: Any?): Boolean {
        if (candidate == null) return false
        return timeline.isPendingEngine(candidate) ||
            currentEngine.get() === candidate ||
            (isSeriesRoute() && candidate === engine(currentHolder.get()))
    }

    /** During the host's original s4(), and nowhere else, report a
     * page as visible so native EOF selection can proceed in background. */
    fun beginVisibilityScope(a: Any?, target: Int?): Boolean {
        if (a == null || target == null || !SystemMediaBridge.isOfficialSeriesBackground())
            return false
        timeline.rememberAdapter(a)
        val current = position(a) ?: return false
        if (!timeline.maySelectAfterCompletion(a, target, current)) return false
        if (page(a) != current || target >= loaded(a)) return false
        val controller = field(a, "m") ?: return false
        visibilityScope.set(controller)
        log("visibility", "scoped", "target=$target from=$current")
        return true
    }

    fun shouldMaskInvisiblePage(controller: Any?): Boolean =
        controller != null && visibilityScope.get() === controller &&
            SystemMediaBridge.isOfficialSeriesBackground() &&
            timeline.isPendingHandoff()

    fun endVisibilityScope() { visibilityScope.remove() }

    fun onActivityResumed(a: Activity?) {
        if (a?.javaClass?.name == SERIES_ACTIVITY) ownerActivity = WeakReference(a)
        else if (a != null && ownerActivity.get() !== a) {
            releaseLease(null, "route_changed")
            timeline.reset()
            adapter.clear()
            candidateHolder.clear()
            currentHolder.clear()
            currentEngine.clear()
            ownerActivity.clear()
        }
    }

    fun onAdapter(a: Any?) {
        if (a == null || a.javaClass.name != SERIES_ADAPTER) return
        adapter = WeakReference(a)
        timeline.rememberAdapter(a)
        SystemMediaBridge.rememberOfficialSeriesAdapter(a)
    }

    fun onCompletion(holder: Any?, completedEngine: Any?) {
        if (completedEngine == null || holder?.javaClass?.name != SERIES_PLAYER)
            return
        if (currentEngine.get() != null &&
            currentEngine.get() !== completedEngine &&
            timeline.pendingEngine() !== completedEngine) return
        if (currentEngine.get() == null) {
            currentHolder = WeakReference(holder)
            currentEngine = WeakReference(completedEngine)
        }
        releaseLease(null, "previous_episode_complete")
        timeline.onCompletion(completedEngine)
        candidateHolder.clear()
        startAttemptGeneration = -1L
        log("eof", "observed", "generation=" + timeline.snapshot().generation)
    }

    fun onAutoplay(a: Any?, phase: String) {
        onAdapter(a)
        if (phase == "before") inAutoplay.set(true)
        val s = timeline.snapshot()
        log("autoplay", "observed", "phase=$phase,selected=" +
            (position(a) ?: -1) + ",state=" + s.phase)
        if (phase == "after") inAutoplay.remove()
    }

    /**
     * Observe the host's ORIGINAL G4 -> j15.d.i9 call, not by invoking i9
     * an extra time. Policy methods can have side effects; duplicate probes
     * may itself change playback behavior.
     */
    fun onG4(a: Any?, phase: String) {
        onAdapter(a)
        if (phase == "before") {
            g4DecisionScope.set(field(a, "m"))
            g4GateResult.remove()
        }
        val s = timeline.snapshot()
        log("g4", "observed",
            "phase=$phase,hostI9=" +
                (g4GateResult.get()?.toString() ?: "not_observed") +
                ",state=" + s.phase +
                ",background=" + SystemMediaBridge.isOfficialSeriesBackground())
        if (phase == "after") {
            g4DecisionScope.remove()
            g4GateResult.remove()
        }
    }

    fun onNativeAutoplayGate(controller: Any?, result: Any?) {
        if (controller == null || controller !== g4DecisionScope.get()) return
        val blocked = result as? Boolean
        g4GateResult.set(blocked)
        log("i9", "observed", "hostReturn=$blocked")
    }

    fun onSelection(a: Any?, requested: Int?, result: Any?) {
        onAdapter(a)
        if (a == null || requested == null) return
        val accepted = timeline.onSelected(
            a, requested, result == true,
            position(a) ?: -1, page(a) ?: -1)
        log("selection", if (accepted) "accepted" else "rejected",
            "target=$requested,return=$result,D=" + position(a) +
            ",page=" + page(a) + ",background=" +
            SystemMediaBridge.isOfficialSeriesBackground())
        if (!accepted) return
        generationAtSelection = timeline.snapshot().generation
        val holder = candidateHolder.get()
        if (holder != null && candidateAdapter.get() === a)
            bindNextHolder(holder, a)
        scheduleVerifiedNativeStart()
    }

    fun onListener(holder: Any?, listener: Any?, attached: Boolean) {
        if (holder?.javaClass?.name != SERIES_PLAYER ||
            listener?.javaClass?.name != SERIES_ADAPTER) return
        onAdapter(listener)
        if (!attached) {
            log("listener", "observed", "removed,active=" +
                (holder === currentHolder.get()))
            return
        }
        candidateHolder = WeakReference(holder)
        candidateAdapter = WeakReference(listener)
        log("listener", "observed", "registered,hasEngine=" +
            (engine(holder) != null))
        bindNextHolder(holder, listener)
    }

    private fun bindNextHolder(holder: Any, adapter: Any) {
        val boundEngine = engine(holder) ?: return
        if (timeline.onBound(adapter, holder, boundEngine)) {
            log("holder", "bound", "next_engine=true")
            scheduleVerifiedNativeStart()
        } else if (currentEngine.get() == null) {
            currentHolder = WeakReference(holder)
            currentEngine = WeakReference(boundEngine)
        }
    }

    fun onEngineBound(holder: Any?, boundEngine: Any?) {
        if (holder?.javaClass?.name != SERIES_PLAYER ||
            boundEngine == null) return
        val a = candidateAdapter.get()
        if (a != null && candidateHolder.get() === holder) {
            bindNextHolder(holder, a)
        } else if (currentEngine.get() == null) {
            currentHolder = WeakReference(holder)
            currentEngine = WeakReference(boundEngine)
        }
    }

    fun shouldRetainCompletionListener(holder: Any?, listener: Any?): Boolean =
        holder != null && listener != null &&
            holder === currentHolder.get() &&
            listener === adapter.get() &&
            SystemMediaBridge.isOfficialSeriesBackground() &&
            timeline.snapshot().phase == OfficialSeriesTimeline.Phase.COMPLETED

    /** One business resume, posted to run AFTER the original G4/s4 stack.
     * The native task's no-surface flag persists through async VideoModel load.
     * No page movement, repeated retries, synthetic engine play or polling
     * of unrelated preloaded holders. */
    private fun scheduleVerifiedNativeStart() {
        val s = timeline.snapshot()
        if (s.phase != OfficialSeriesTimeline.Phase.BOUND ||
            s.generation == startAttemptGeneration ||
            !SystemMediaBridge.isOfficialSeriesBackground()) return
        startAttemptGeneration = s.generation
        handler.post {
            val current = timeline.snapshot()
            val holder = timeline.pendingHolder()
            val e = timeline.pendingEngine()
            if (current.generation != s.generation ||
                current.phase != OfficialSeriesTimeline.Phase.BOUND ||
                holder == null || e == null ||
                !SystemMediaBridge.isOfficialSeriesBackground()) return@post
            val result = enableHostNoSurface(holder, e)
            if (result != "enabled" && result != "already_enabled") {
                log("resume", "degraded", "reason=$result")
                return@post
            }
            try {
                val resume = method(holder, "resume", 0)
                if (resume == null) {
                    releaseLease(e, "native_resume_missing")
                    log("resume", "degraded", "reason=no_native_resume")
                    return@post
                }
                resume.invoke(holder)
                log("resume", "requested", "mode=official_no_surface,index=" +
                    current.pendingIndex)
            } catch (t: Throwable) {
                releaseLease(e, "resume_exception")
                log("resume", "failed", "type=" + t.javaClass.simpleName)
            }
            // Cleanup only; never a timer-based second play/skip.
            handler.postDelayed({
                if (timeline.isPendingEngine(e) &&
                    timeline.snapshot().generation == s.generation) {
                    releaseLease(e, "source_timeout")
                    log("start", "degraded", "reason=no_verified_progress,index=" +
                        current.pendingIndex + ",phase=" +
                        timeline.snapshot().phase)
                }
            }, 45_000L)
        }
    }

    @Synchronized private fun enableHostNoSurface(holder: Any, e: Any): String {
        if (lease?.engine === e) return "already_enabled"
        releaseLease(null, "new_engine")
        val task = field(holder, "f") ?: return "native_task_missing"
        if (task.javaClass.name != "c15.d" || field(task, "b") !== e)
            return "host_task_engine_mismatch"
        val surface = field(task, "c")
        val available = invoke(surface, "isSurfaceAvailable") as? Boolean
        if (available != false) return "surface_not_confirmed_unavailable"
        val flag = try {
            task.javaClass.getDeclaredField("i").apply {
                isAccessible = true
            }
        } catch (_: Throwable) { return "host_flag_missing" }
        if (flag.type != Boolean::class.javaPrimitiveType)
            return "host_flag_signature_changed"
        return try {
            val previous = flag.getBoolean(task)
            if (!previous) flag.setBoolean(task, true)
            lease = NoSurfaceLease(task, flag, previous, e)
            "enabled"
        } catch (t: Throwable) { "flag_failed:" + t.javaClass.simpleName }
    }

    @Synchronized private fun releaseLease(e: Any?, reason: String) {
        val current = lease ?: return
        if (e != null && current.engine !== e) return
        lease = null
        try {
            current.flag.setBoolean(current.task, current.original)
            log("surface", "restored", "reason=$reason")
        } catch (t: Throwable) {
            log("surface", "degraded", "restore=" + t.javaClass.simpleName)
        }
    }

    fun onEngineCommand(e: Any?, action: String) {
        if (e == null) return
        when (action) {
            "play" -> {
                if (timeline.onPlayCommand(e))
                    log("start", "requested", "phase=STARTING (not yet PLAYING)")
            }
            "pause", "stop", "releaseAsync" -> {
                if (timeline.isPendingEngine(e))
                    log("engine", "observed", "pending_$action")
            }
        }
    }

    /** Observable ONLY when source readiness is for the chosen host holder. */
    fun onReadiness(holder: Any?, stage: String, e: Any?) {
        if (e == null || holder == null || !timeline.isPendingEngine(e) ||
            holder !== timeline.pendingHolder()) return
        val status = invoke(e, "getPlaybackState")
        val model = invoke(e, "getVideoModel")
        log("ready", "observed", "stage=$stage,playbackState=" +
            (status ?: "?") + ",hasModel=" + (model != null))
    }

    fun onError(holder: Any?, e: Any?, message: String) {
        if (e == null || holder !== timeline.pendingHolder()) return
        timeline.onError(e, message)
        releaseLease(e, "native_error")
        log("error", "degraded", message)
    }

    fun awaitingProgress(e: Any?): Boolean =
        timeline.isPendingEngine(e) &&
            timeline.snapshot().phase in setOf(
                OfficialSeriesTimeline.Phase.BOUND,
                OfficialSeriesTimeline.Phase.STARTING)

    fun needsProgressPolling(): Boolean = timeline.snapshot().phase in setOf(
        OfficialSeriesTimeline.Phase.BOUND,
        OfficialSeriesTimeline.Phase.STARTING)

    fun onProgress(e: Any?, pos: Long, duration: Long): Boolean {
        if (e == null || !timeline.onProgress(e, pos, duration)) return false
        releaseLease(e, "verified_native_progress")
        currentEngine = WeakReference(e)
        currentHolder = WeakReference(candidateHolder.get())
        log("progress", "confirmed", "position=$pos,duration=$duration,index=" +
            timeline.snapshot().playingIndex)
        SinglePlaybackOwner.onEnginePlayed(e)
        return true
    }

    fun skipNative(delta: Int): String {
        val a = adapter.get() ?: return "adapter_unavailable"
        if (!timeline.canDispatchEpisodeCommand()) return "handoff_in_progress"
        val index = position(a) ?: return "index_unknown"
        if (page(a) != index || index + delta !in 0 until loaded(a))
            return "pager_misaligned_or_boundary"
        val name = if (delta > 0) "g" else "A1"
        val answer = try { method(a, name, 0)?.invoke(a) }
            catch (t: Throwable) { return "exception:" + t.javaClass.simpleName }
        return if (answer != false && position(a) == index + delta &&
            page(a) == index + delta) "selected"
            else "host_did_not_accept"
    }

    fun onExplicitPause() {
        releaseLease(null, "user_pause")
        val e = timeline.pendingEngine()
        if (e != null) timeline.onPause(e, true)
    }

    fun reset() {
        releaseLease(null, "reset")
        timeline.reset()
        candidateHolder.clear()
        candidateAdapter.clear()
        currentEngine.clear()
        currentHolder.clear()
        adapter.clear()
        ownerActivity.clear()
        visibilityScope.remove()
        inAutoplay.remove()
        g4DecisionScope.remove()
        g4GateResult.remove()
    }
}
