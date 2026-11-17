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
 * Rebuilt from the HOST's 7.3.5.32 native call chain, not from V2 patches.
 *
 * x05.w.onCompletion -> adapter.f.U1 -> adapter.a.s4 -> adapter.a.G4
 * -> x05.w.v / x05.w.L -> actual TTVideoEngine progress.
 *
 * The original host decides the episode and owns its playback engine.
 * No new decoder, second ViewPager move, timer-based retry or fake EOF.
 * The single permitted command is one host x05.w.resume() for the exact
 * accepted next holder while the screen has no surface.
 */
internal object NativeSeriesV3 {
    private const val SERIES_ACTIVITY =
        "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"
    private const val SERIES_ADAPTER =
        "com.dragon.read.component.shortvideo.impl.v2.view.adapter.f"
    private const val SERIES_PLAYER = "x05.w"
    private val handler = Handler(Looper.getMainLooper())
    private val state = NativeSeriesV3State()

    private var adapter = WeakReference<Any>(null)
    private var ownerActivity = WeakReference<Activity>(null)
    private var currentHolder = WeakReference<Any>(null)
    private var currentEngine = WeakReference<Any>(null)
    private var bindingEngine = WeakReference<Any>(null)
    private var candidateHolder = WeakReference<Any>(null)
    private var candidateAdapter = WeakReference<Any>(null)
    private var initialBaseline: Long? = null
    private var initialOwner = WeakReference<Any>(null)
    private var resumeAttemptGeneration = -1L

    private data class PageScope(
        val controller: Any,
        val adapter: Any,
        val generation: Long,
        val target: Int,
        val originalBusiness: Int,
        val originalPage: Int,
        val origin: NativeSeriesSelectionRecoveryPolicy.Origin
    )
    private data class SurfaceLease(
        val engine: Any, val task: Any,
        val flag: Field, val original: Boolean
    )
    private data class AcceptedEofGate(
        val adapter: Any, val controller: Any,
        val generation: Long, val index: Int, val at: Long
    )
    private var acceptedEofGate: AcceptedEofGate? = null
    private val scopedPage = ThreadLocal<PageScope?>()

    private val currentG4 = ThreadLocal<Any?>()
    private var lease: SurfaceLease? = null

    private fun inheritedField(obj: Any?, name: String): Any? {
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

    private fun method(obj: Any?, name: String, argc: Int): Method? {
        var type = obj?.javaClass
        while (type != null && type != Any::class.java) {
            try {
                val m = type.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == argc
                }
                if (m != null) {
                    m.isAccessible = true
                    return m
                }
            } catch (_: Throwable) { return null }
            type = type.superclass
        }
        return null
    }

    private fun call(obj: Any?, name: String, argc: Int = 0): Any? =
        try { if (obj == null) null else method(obj, name, argc)?.invoke(obj) }
        catch (_: Throwable) { null }

    private fun engine(holder: Any?): Any? =
        if (holder?.javaClass?.name == SERIES_PLAYER) call(holder, "H")
        else null

    private fun d(a: Any?): Int? = (inheritedField(a, "D") as? Number)?.toInt()
    private fun page(a: Any?): Int? =
        (call(inheritedField(a, "k"), "getCurrentItem") as? Number)?.toInt()
    private fun count(a: Any?): Int =
        (inheritedField(a, "h") as? List<*>)?.size ?: 0

    private fun trace(stage: String, result: String, message: String) {
        FeatureTrace.event("playback", "v3_$stage", result, message.take(300))
        if (stage != "listener" || result != "observed")
            LogUtil.info("原生V3 $stage/$result $message")
    }

    fun snapshot(): NativeSeriesV3State.Snapshot = state.snapshot()

    /** Authoritative episode ordinal only for a genuinely progressing
     * engine. D and pager alone cannot prove that the selected video plays. */
    fun confirmedEpisodeIndex(engine: Any?): Int? {
        if (engine == null || currentEngine.get() !== engine) return null
        val s = state.snapshot()
        if (s.phase == NativeSeriesV3State.Phase.PLAYING)
            return s.playingIndex
        if (s.phase == NativeSeriesV3State.Phase.IDLE &&
            initialOwner.get() === engine) {
            val a = adapter.get() ?: return null
            val index = d(a) ?: return null
            return index.takeIf { page(a) == it }
        }
        return null
    }

    /**
     * Metadata is read from the exact host business selection associated
     * with a progressing engine. The publisher never guesses from a pager,
     * an old Presenter, or a preloaded neighbour.
     */
    fun confirmedSelectedSeriesModel(engine: Any?): Any? {
        val verified = confirmedEpisodeIndex(engine) ?: return null
        val a = adapter.get() ?: return null
        if (d(a) != verified || page(a) != verified) return null
        val selected = call(a, "l0") ?: return null
        if (selected.javaClass.name !=
            "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData")
            return null
        return selected
    }

    fun isSeriesRoute(): Boolean =
        ownerActivity.get()?.javaClass?.name == SERIES_ACTIVITY &&
            SystemMediaBridge.isOfficialSeriesActivityCurrent()

    fun isSeriesEngine(e: Any?): Boolean =
        e != null && (state.isPendingEngine(e) ||
            currentEngine.get() === e || bindingEngine.get() === e)

    fun onActivityResumed(activity: Activity?) {
        if (activity?.javaClass?.name == SERIES_ACTIVITY) {
            ownerActivity = WeakReference(activity)
            return
        }
        if (activity != null && ownerActivity.get() !== activity) reset()
    }

    fun onAdapter(a: Any?) {
        if (a?.javaClass?.name != SERIES_ADAPTER) return
        adapter = WeakReference(a)
        state.rememberAdapter(a)
        SystemMediaBridge.rememberOfficialSeriesAdapter(a)
    }

    fun onCompletion(holder: Any?, finished: Any?) {
        if (holder?.javaClass?.name != SERIES_PLAYER || finished == null) return
        val current = currentEngine.get()
        if (current != null && current !== finished &&
            !state.isPendingEngine(finished)) return
        if (current == null) {
            currentHolder = WeakReference(holder)
            currentEngine = WeakReference(finished)
        }
        releaseLease(null, "completed")
        acceptedEofGate = null
        val g = state.complete(finished)
        candidateHolder.clear()
        resumeAttemptGeneration = -1L
        trace("eof", "observed", "generation=$g,background=" +
            SystemMediaBridge.isOfficialSeriesBackground())
        // Diagnostics only. No speculative s4(), pager movement or play().
        handler.postDelayed({
            if (state.snapshot().generation == g &&
                state.snapshot().phase == NativeSeriesV3State.Phase.COMPLETED)
                trace("selection", "degraded",
                    "host_did_not_accept_next_after_eof")
        }, 4200L)
    }

    fun onAutoplay(a: Any?, phase: String) {
        onAdapter(a)
        // Observe the host's original U1 -> s4 -> G4 handoff. Never
        // manufacture another s4 from a delayed callback: it competes
        // with the native adapter and can leave D one page behind.
        trace("autoplay", "observed",
            "phase=$phase,D=" + (d(a) ?: -1) +
            ",pager=" + (page(a) ?: -1) +
            ",state=" + state.snapshot().phase)
        if (phase == "after" &&
            state.snapshot().phase == NativeSeriesV3State.Phase.COMPLETED) {
            trace("handoff", "pending",
                "native_selection_not_confirmed,D=" + (d(a) ?: -1) +
                ",pager=" + (page(a) ?: -1))
        }
    }

    /** The public j15.d interface cannot be hooked: its i9() is ABSTRACT.
     * Native method hooks must attach to the concrete runtime controller.
     * Called only inside the original adapter.a.G4() stack. */
    fun onG4(a: Any?, phase: String) {
        onAdapter(a)
        if (phase == "before") currentG4.set(inheritedField(a, "m"))
        trace("g4", "observed", "phase=$phase,D=" + (d(a) ?: -1) +
            ",state=" + state.snapshot().phase)
        if (phase == "after") {
            // The grant is single-use: when the original G4 call returns,
            // a later manual/foreground G4 is never affected.
            acceptedEofGate = null
            currentG4.remove()
        }
    }

    fun onNativeAutoplayGate(controller: Any?, original: Any?): Any? {
        val native = original as? Boolean ?: return original
        val grant = acceptedEofGate
        val scope = scopedPage.get()
        val eofScope = scope?.origin ==
            NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF
        val sameOriginalG4 = controller != null &&
            controller === currentG4.get()
        val matchingController = sameOriginalG4 &&
            ((eofScope && controller === scope?.controller) ||
                (grant != null && controller === grant.controller &&
                    adapter.get() === grant.adapter))
        val sameGeneration = if (eofScope)
            state.snapshot().generation == scope?.generation
            else grant != null && state.snapshot().generation == grant.generation
        val accepted = eofScope ||
            (grant != null && state.snapshot().phase in setOf(
                NativeSeriesV3State.Phase.SELECTED,
                NativeSeriesV3State.Phase.BOUND,
                NativeSeriesV3State.Phase.STARTING))
        val age = if (eofScope) 0L else if (grant != null)
            SystemClock.uptimeMillis() - grant.at else Long.MAX_VALUE
        val relax = NativeG4GatePolicy.mayRelax(
            hostBlocked = native,
            background = SystemMediaBridge.isOfficialSeriesBackground(),
            explicitCommand =
                SystemMediaBridge.isExplicitMediaCommandInProgress(),
            controllerMatches = matchingController,
            generationMatches = sameGeneration,
            acceptedEofSelection = accepted,
            elapsedMs = age
        )
        if (sameOriginalG4)
            trace("g4_gate", if (relax) "scoped" else "observed",
                "nativeBlock=$native,override=$relax,age=$age")
        return if (relax) false else original
    }

    /** V2 incorrectly demanded pager == D. The original app often advances
     * ViewPager2 to target BEFORE it calls s4(target). Permit both orders,
     * without moving the pager ourselves. */
    fun beginVisibilityScope(a: Any?, target: Int?): Boolean {
        if (a == null || target == null) return false
        val background = SystemMediaBridge.isOfficialSeriesBackground()
        // A V3 EOF can arrive while the legacy media exit flag is still
        // false; trace *why* the host selection scope is declined.
        val before = state.snapshot()
        if (!background) {
            if (before.phase == NativeSeriesV3State.Phase.COMPLETED)
                trace("scope", "observed",
                    "reason=not_background,target=$target,phase=" + before.phase)
            return false
        }
        onAdapter(a)
        val current = d(a) ?: return false
        val p = page(a) ?: return false
        val g = state.snapshot().generation
        // A visibility exception belongs only to a verified native EOF.
        // Manual media keys never obtain a second route through s4().
        val paused = SystemMediaBridge.isExplicitMediaCommandInProgress()
        val total = count(a)
        val eofAllowed = state.mayScopeNext(a, target, current, p, total,
            background = true, explicitlyPaused = paused)
        if (!eofAllowed) {
            trace("scope", "observed",
                "reason=state_gate,target=$target,D=$current,pager=$p," +
                    "count=$total,paused=$paused,phase=" + state.snapshot().phase)
            return false
        }
        val controller = inheritedField(a, "m") ?: return false
        val origin = NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF
        scopedPage.set(PageScope(controller, a, g, target, current, p,
            origin))
        trace("scope", "enabled", "D=$current,pager=$p,target=$target," +
            "g=$g,origin=$origin")
        return true
    }

    fun shouldMaskInvisiblePage(controller: Any?): Boolean {
        val s = scopedPage.get() ?: return false
        val permitted = controller != null && controller === s.controller &&
            state.snapshot().generation == s.generation &&
            SystemMediaBridge.isOfficialSeriesBackground()
        if (permitted) trace("visibility", "scoped",
            "target=" + s.target + ",beforePager=" + s.originalPage)
        return permitted
    }

    fun endVisibilityScope() { scopedPage.remove() }

    fun onSelection(a: Any?, target: Int?, returned: Any?) {
        onAdapter(a)
        if (a == null || target == null) return
        val accepted = state.select(
            a, target, returned == true, d(a) ?: -1, page(a) ?: -1)
        val scope = scopedPage.get()
        trace("select", if (accepted) "accepted" else "rejected",
            "target=$target,result=$returned,D=" + (d(a) ?: -1) +
            ",pager=" + (page(a) ?: -1) +
            ",eofScope=" + (scope?.target == target))
        if (!accepted) return
        if (scope != null && scope.adapter === a && scope.target == target &&
            scope.origin == NativeSeriesSelectionRecoveryPolicy.Origin.VERIFIED_EOF) {
            acceptedEofGate = AcceptedEofGate(a, scope.controller,
                state.snapshot().generation, target,
                SystemClock.uptimeMillis())
        }
        val holder = candidateHolder.get()
        if (holder != null && candidateAdapter.get() === a)
            bindNext(holder, a)
        scheduleStart()
    }

    fun onListener(holder: Any?, listener: Any?, attached: Boolean) {
        if (holder?.javaClass?.name != SERIES_PLAYER ||
            listener?.javaClass?.name != SERIES_ADAPTER) return
        onAdapter(listener)
        if (!attached) {
            trace("listener", "observed", "removed,current=" +
                (holder === currentHolder.get()))
            return
        }
        candidateHolder = WeakReference(holder)
        candidateAdapter = WeakReference(listener)
        trace("listener", "observed", "attached,engine=" + (engine(holder) != null))
        bindNext(holder, listener)
    }

    private fun bindNext(holder: Any, a: Any) {
        val e = engine(holder) ?: return
        if (state.bind(a, holder, e)) {
            trace("holder", "bound", "generation=" + state.snapshot().generation)
            scheduleStart()
        } else if (currentEngine.get() == null) {
            currentEngine = WeakReference(e)
            currentHolder = WeakReference(holder)
        }
    }

    /** Called BEFORE x05.w.L(engine) to avoid blocking a nested host play. */
    fun onEngineBindingStart(holder: Any?, e: Any?) {
        if (holder?.javaClass?.name != SERIES_PLAYER || e == null) return
        bindingEngine = WeakReference(e)
        // First-series play() may execute synchronously INSIDE L(engine).
        // Register this original host holder before the nested engine call
        // so the MediaSession does not announce success prematurely.
        if (currentEngine.get() == null && isSeriesRoute()) {
            currentEngine = WeakReference(e)
            currentHolder = WeakReference(holder)
        }
    }

    fun onEngineBound(holder: Any?, e: Any?) {
        if (holder?.javaClass?.name != SERIES_PLAYER || e == null) return
        if (bindingEngine.get() === e) bindingEngine.clear()
        val a = candidateAdapter.get()
        if (a != null && candidateHolder.get() === holder) bindNext(holder, a)
        if (currentEngine.get() == null) {
            currentEngine = WeakReference(e)
            currentHolder = WeakReference(holder)
        }
        SystemMediaBridge.rememberOfficialSeriesHolder(holder, e)
    }

    fun shouldRetainCompletionListener(holder: Any?, listener: Any?): Boolean {
        if (holder == null || listener == null ||
            holder !== currentHolder.get() || listener !== adapter.get())
            return false
        return (SystemMediaBridge.isOfficialSeriesBackground() ||
            SystemMediaBridge.isOfficialSeriesPrePauseHandoff(engine(holder))) &&
            state.snapshot().phase !in setOf(
                NativeSeriesV3State.Phase.FAILED,
                NativeSeriesV3State.Phase.SELECTED,
                NativeSeriesV3State.Phase.BOUND,
                NativeSeriesV3State.Phase.STARTING)
    }

    /** If the host's selected next holder has no Surface, set its OWN
     * original c15.d.i playWithoutSurface flag before exactly one resume().
     * It must survive async VideoModel source loading, then be restored. */
    @Synchronized private fun enableNoSurface(holder: Any, e: Any): String {
        if (lease?.engine === e) return "already"
        releaseLease(null, "new_engine")
        val task = inheritedField(holder, "f") ?: return "task_missing"
        if (task.javaClass.name != "c15.d" || inheritedField(task, "b") !== e)
            return "task_engine_mismatch"
        val surface = inheritedField(task, "c")
        val available = call(surface, "isSurfaceAvailable") as? Boolean
        if (available != false) return "no_missing_surface_evidence"
        val flag = try {
            task.javaClass.getDeclaredField("i").apply { isAccessible = true }
        } catch (_: Throwable) { return "task_flag_missing" }
        if (flag.type != Boolean::class.javaPrimitiveType)
            return "task_flag_type_changed"
        return try {
            val original = flag.getBoolean(task)
            if (!original) flag.setBoolean(task, true)
            lease = SurfaceLease(e, task, flag, original)
            "enabled"
        } catch (t: Throwable) { "flag_error_" + t.javaClass.simpleName }
    }

    @Synchronized private fun releaseLease(e: Any?, reason: String) {
        val l = lease ?: return
        if (e != null && l.engine !== e) return
        lease = null
        try {
            l.flag.setBoolean(l.task, l.original)
            trace("surface", "restored", "reason=$reason")
        } catch (t: Throwable) {
            trace("surface", "degraded", "restore=" + t.javaClass.simpleName)
        }
    }

    private fun scheduleStart() {
        val s = state.snapshot()
        if (s.phase != NativeSeriesV3State.Phase.BOUND ||
            s.generation == resumeAttemptGeneration ||
            !SystemMediaBridge.isOfficialSeriesBackground()) return
        resumeAttemptGeneration = s.generation
        handler.post {
            val current = state.snapshot()
            val holder = state.pendingHolder()
            val e = state.pendingEngine()
            if (current.generation != s.generation ||
                current.phase != NativeSeriesV3State.Phase.BOUND ||
                holder == null || e == null ||
                !SystemMediaBridge.isOfficialSeriesBackground()) return@post
            val mode = enableNoSurface(holder, e)
            if (mode !in setOf("enabled", "already")) {
                trace("resume", "degraded", "reason=$mode")
                return@post
            }
            try {
                val original = method(holder, "resume", 0)
                if (original == null) {
                    releaseLease(e, "resume_missing")
                    trace("resume", "failed", "host_resume_missing")
                    return@post
                }
                original.invoke(holder)
                trace("resume", "requested", "one_host_resume,target=" +
                    current.pendingIndex)
            } catch (t: Throwable) {
                releaseLease(e, "resume_exception")
                trace("resume", "failed", t.javaClass.simpleName)
            }
            // Check whether the host ever supplies a VideoModel or
            // prepared engine. These probes never issue another play/skip.
            inspectPendingSource(e, s.generation, 3500L)
            inspectPendingSource(e, s.generation, 12000L)
            // Cleanup only — NEVER retry play/skip on a timer.
            handler.postDelayed({
                if (state.isPendingEngine(e) &&
                    state.snapshot().generation == s.generation) {
                    releaseLease(e, "source_timeout")
                    trace("startup", "degraded", "no_progress,target=" +
                        current.pendingIndex + ",phase=" + state.snapshot().phase)
                }
            }, 45_000L)
        }
    }

    /**
     * Observe actual host source assignment to the next accepted engine.
     * No VideoModel IDs, URLs or proprietary payloads are recorded.
     * This does not start playback or touch source selection.
     */
    fun onSourceModelAssigned(engine: Any?, suppliedModel: Any?) {
        if (engine == null || !state.isPendingEngine(engine)) return
        val snapshot = state.snapshot()
        trace("source", "observed",
            "event=setVideoModel,target=" + (snapshot.pendingIndex ?: -1) +
                ",phase=" + snapshot.phase +
                ",supplied=" + (suppliedModel != null) +
                ",engineModel=" + (call(engine, "getVideoModel") != null))
    }

    /** A single snapshot of a pending native engine (diagnostic only). */
    private fun inspectPendingSource(engine: Any, generation: Long, delayMs: Long) {
        handler.postDelayed({
            val snap = state.snapshot()
            if (snap.generation != generation ||
                !state.isPendingEngine(engine)) return@postDelayed
            val source = call(engine, "getVideoModel") != null
            val videoId = (call(engine, "getVideoID") as? String)?.isNotBlank() == true
            val engineState = call(engine, "getPlaybackState") ?: "unknown"
            trace("source", if (source) "observed" else "degraded",
                "event=pending_audit,afterMs=$delayMs,phase=" + snap.phase +
                    ",model=$source,videoId=$videoId,engineState=$engineState")
        }, delayMs)
    }

    fun onEngineCommand(e: Any?, command: String) {
        if (e == null) return
        when (command) {
            "play" -> if (state.playCommand(e))
                trace("startup", "requested", "play_command_not_playback")
            "pause", "stop", "releaseAsync" -> if (state.isPendingEngine(e))
                trace("engine", "observed", "pending_$command")
        }
    }

    fun onReadiness(holder: Any?, stage: String, e: Any?) {
        if (holder == null || e == null || holder !== state.pendingHolder() ||
            !state.isPendingEngine(e)) return
        trace("ready", "observed", "stage=$stage,state=" +
            (call(e, "getPlaybackState") ?: "?") +
            ",hasModel=" + (call(e, "getVideoModel") != null))
    }

    fun onError(holder: Any?, e: Any?, reason: String) {
        if (e == null || holder !== state.pendingHolder()) return
        state.onError(e, reason)
        releaseLease(e, "native_error")
        trace("error", "degraded", reason)
    }

    fun awaitingProgress(e: Any?): Boolean =
        e != null && ((state.isPendingEngine(e) &&
            state.snapshot().phase in setOf(
                NativeSeriesV3State.Phase.BOUND,
                NativeSeriesV3State.Phase.STARTING)) ||
            (currentEngine.get() === e &&
                state.snapshot().phase == NativeSeriesV3State.Phase.IDLE &&
                initialOwner.get() !== e))

    fun needsProgressPolling(): Boolean =
        state.snapshot().phase in setOf(
            NativeSeriesV3State.Phase.BOUND, NativeSeriesV3State.Phase.STARTING) ||
            (isSeriesRoute() && currentEngine.get() != null &&
                state.snapshot().phase == NativeSeriesV3State.Phase.IDLE &&
                initialOwner.get() !== currentEngine.get())

    fun onProgress(e: Any?, pos: Long, duration: Long): Boolean {
        if (e == null) return false
        // Retain the exact holder selected by the reducer, not the latest
        // listener from some unrelated preloaded item.
        val selectedHolder = state.pendingHolder()
        if (state.confirmProgress(e, pos, duration)) {
            releaseLease(e, "progress")
            currentEngine = WeakReference(e)
            currentHolder = WeakReference(selectedHolder)
            SystemMediaBridge.rememberOfficialSeriesHolder(currentHolder.get(), e)
            trace("progress", "confirmed", "index=" +
                state.snapshot().playingIndex + ",pos=$pos,dur=$duration")
            // No premature play-owner handoff; the host's engine is real.
            SinglePlaybackOwner.onEnginePlayed(e)
            return true
        }
        if (currentEngine.get() !== e ||
            state.snapshot().phase != NativeSeriesV3State.Phase.IDLE ||
            initialOwner.get() === e || duration !in 1000L..86_400_000L ||
            pos < 0L || pos > duration + 1000L) return false
        val old = initialBaseline
        if (old == null || pos < old) {
            initialBaseline = pos
            return false
        }
        if (pos - old < 350L) return false
        initialOwner = WeakReference(e)
        initialBaseline = null
        SinglePlaybackOwner.onEnginePlayed(e)
        trace("progress", "confirmed", "first_series_engine=true,pos=$pos")
        return true
    }

    fun skipNative(delta: Int): String {
        if (delta != 1 && delta != -1) return "invalid_delta"
        if (!state.canManualSkip()) return "handoff_pending"
        val a = adapter.get() ?: return "adapter_missing"
        val current = d(a) ?: return "current_index_missing"
        if (page(a) != current || current + delta !in 0 until count(a))
            return "page_misaligned_or_boundary"
        // One user request -> one original host business command. Never
        // follow a rejected command with a second synthetic s4(), because
        // doing so races the host's own asynchronous r4/G4 callbacks.
        val entry = if (delta > 0) "g" else "A1"
        val result = try {
            method(a, entry, 0)?.invoke(a)
        } catch (t: Throwable) {
            trace("manual_skip", "failed", t.javaClass.simpleName)
            return "host_exception_" + t.javaClass.simpleName
        }
        val target = current + delta
        val selected = d(a) == target && page(a) == target
        trace("manual_skip",
            if (selected) "selected" else "pending",
            "command=$entry,result=$result,target=$target,D=" +
                (d(a) ?: -1) + ",pager=" + (page(a) ?: -1))
        return if (selected) "selected" else "native_selection_pending"
    }

    fun onExplicitPause() {
        acceptedEofGate = null
        releaseLease(null, "user_pause")
        state.onPause(state.pendingEngine(), true)
    }

    fun reset() {
        acceptedEofGate = null
        releaseLease(null, "reset")
        state.reset()
        adapter.clear()
        ownerActivity.clear()
        candidateAdapter.clear()
        candidateHolder.clear()
        bindingEngine.clear()
        currentEngine.clear()
        currentHolder.clear()
        initialBaseline = null
        initialOwner.clear()
        resumeAttemptGeneration = -1L
        scopedPage.remove()
        currentG4.remove()
    }
}
