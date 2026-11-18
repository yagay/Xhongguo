package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
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
 * Host-first strategy: no new decoder, extra ViewPager move, synthetic
 * EOF, timer retry, module-originated resume or Surface-flag mutation.
 * Observe original VideoModel assignment and real progress before updating
 * the shared MediaSession. Only explicit user media skips may request one
 * official adapter.g()/A1() business selection.
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
    private var observedNativeStart: Pair<Long, Int?>? = null

    private data class PageScope(
        val controller: Any,
        val adapter: Any,
        val generation: Long,
        val target: Int,
        val originalBusiness: Int,
        val originalPage: Int,
        val origin: NativeSeriesSelectionRecoveryPolicy.Origin
    )
    private data class AcceptedManualGate(
        val adapter: Any, val controller: Any,
        val generation: Long, val index: Int, val at: Long,
        val origin: NativeSeriesSelectionRecoveryPolicy.Origin
    )
    private var acceptedManualGate: AcceptedManualGate? = null

    /**
     * MediaSession manual next/previous requests may complete through the
     * host's asynchronous ViewPager2 callback. Bind a short-lived grant to
     * the exact adapter, original index, target and EOF generation. Never
     * issue a second pager move or synthesize a selection.
     */
    private data class ManualSkipRequest(
        val adapter: Any, val fromIndex: Int, val targetIndex: Int,
        val generation: Long, val startedMs: Long
    )
    @Volatile private var manualSkipRequest: ManualSkipRequest? = null
    // Only inside the single synchronous original adapter.g()/A1() call.
    // The host must be allowed to pause its OLD engine when changing video.
    private val originalSkipEngine = ThreadLocal<Any?>()
    private var manualSelectedGeneration = -1L
    private var manualSelectedIndex: Int? = null
    private val scopedPage = ThreadLocal<PageScope?>()

    private val currentG4 = ThreadLocal<Any?>()

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
        // Feed and full-series share x05.w, but only a real Series Activity
        // may advance this V3 state machine. Previously a FEED EOF could
        // mark COMPLETED and block manual media controls as handoff_pending.
        if (!SystemMediaBridge.isOfficialSeriesActivityCurrent()) return
        val current = currentEngine.get()
        if (current != null && current !== finished &&
            !state.isPendingEngine(finished)) return
        if (current == null) {
            currentHolder = WeakReference(holder)
            currentEngine = WeakReference(finished)
        }
        acceptedManualGate = null
        manualSkipRequest = null
        manualSelectedIndex = null
        manualSelectedGeneration = -1L
        val g = state.complete(finished)
        candidateHolder.clear()
        observedNativeStart = null
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
            acceptedManualGate = null
            currentG4.remove()
        }
    }

    fun onNativeAutoplayGate(controller: Any?, original: Any?): Any? {
        val native = original as? Boolean ?: return original
        val grant = acceptedManualGate
        val scope = scopedPage.get()
        // Early main left automatic EOF's host policy untouched.
        // Only an explicit, verified media user skip may rewrite i9().
        val manualRequest = scope?.origin ==
            NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP ||
            grant?.origin == NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP
        if (!manualRequest) return original
        val selectionScope = scope?.origin ==
            NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP
        val sameOriginalG4 = controller != null &&
            controller === currentG4.get()
        val matchingController = sameOriginalG4 &&
            ((selectionScope && controller === scope?.controller) ||
                (grant != null && controller === grant.controller &&
                    adapter.get() === grant.adapter))
        val sameGeneration = if (selectionScope)
            state.snapshot().generation == scope?.generation
            else grant != null && state.snapshot().generation == grant.generation
        val accepted = selectionScope ||
            (grant != null && state.snapshot().phase in setOf(
                NativeSeriesV3State.Phase.SELECTED,
                NativeSeriesV3State.Phase.BOUND,
                NativeSeriesV3State.Phase.STARTING))
        val age = if (selectionScope) 0L else if (grant != null)
            SystemClock.uptimeMillis() - grant.at else Long.MAX_VALUE
        val manualSelection =
            scope?.origin == NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP ||
                (scope == null &&
                    grant?.origin == NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP)
        val relax = NativeG4GatePolicy.mayRelax(
            hostBlocked = native,
            background = if (manualSelection)
                SystemMediaBridge.isOfficialSeriesManualSkipBackground()
                else SystemMediaBridge.isOfficialSeriesBackground(),
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
        return if (NativeTransportPolicy.allowScopedPageOverride(
                manualUserSkip = manualRequest,
                matchingOriginalSelection = relax))
            false else original
    }

    /**
     * The host owns the full automatic EOF route, as in early main.
     * An explicit MediaSession next/previous command gets only a single,
     * identity- and generation-bound offscreen selection exception.
     */
    fun beginVisibilityScope(a: Any?, target: Int?): Boolean {
        if (a == null || target == null) return false
        val manual = manualSkipRequest ?: return false
        if (!SystemMediaBridge.isOfficialSeriesManualSkipBackground())
            return false
        onAdapter(a)
        val current = d(a) ?: return false
        val pager = page(a) ?: return false
        val generation = state.snapshot().generation
        val paused = SystemMediaBridge.isExplicitMediaCommandInProgress()
        val allowed = NativeManualEpisodeSkipPolicy.mayScope(
            sameAdapter = manual.adapter === a && adapter.get() === a,
            background = true,
            nativeSelectionIdle = state.canManualSkip(),
            explicitTransportPause = paused,
            originalIndex = current,
            pagerIndex = pager,
            requestedIndex = target,
            mediaTargetIndex = manual.targetIndex,
            loadedCount = count(a),
            sameGeneration = manual.generation == generation &&
                manual.fromIndex == current,
            ageMs = SystemClock.uptimeMillis() - manual.startedMs)
        if (!allowed) {
            if (manual.adapter === a)
                trace("manual_scope", "rejected",
                    "D=$current,pager=$pager,target=$target,expected=" +
                        manual.targetIndex + ",phase=" + state.snapshot().phase)
            return false
        }
        val controller = inheritedField(a, "m") ?: return false
        manualSkipRequest = null // one target, one use, no future EOF grant
        scopedPage.set(PageScope(controller, a, generation, target, current,
            pager, NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP))
        trace("scope", "enabled",
            "D=$current,pager=$pager,target=$target,g=$generation," +
                "origin=USER_SKIP")
        return true
    }

    fun shouldMaskInvisiblePage(controller: Any?): Boolean {
        val s = scopedPage.get() ?: return false
        val permitted = controller != null && controller === s.controller &&
            state.snapshot().generation == s.generation &&
            s.origin == NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP &&
            SystemMediaBridge.isOfficialSeriesManualSkipBackground()
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
            ",manualScope=" + (scope?.origin ==
                NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP))
        if (!accepted) return
        if (scope != null && scope.adapter === a && scope.target == target &&
            scope.origin == NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP) {
            acceptedManualGate = AcceptedManualGate(a, scope.controller,
                state.snapshot().generation, target,
                SystemClock.uptimeMillis(), scope.origin)
            if (scope.origin ==
                NativeSeriesSelectionRecoveryPolicy.Origin.USER_SKIP) {
                // A user skip isn't an EOF, so its generation is unchanged.
                // Reset the source observation for this selected target only.
                observedNativeStart = null
                manualSelectedGeneration = state.snapshot().generation
                manualSelectedIndex = target
            }
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
        if (currentEngine.get() == null && isSeriesRoute()) {
            // A feed x05.w.L() is not the authoritative Series engine.
            currentEngine = WeakReference(e)
            currentHolder = WeakReference(holder)
        }
        SystemMediaBridge.rememberOfficialSeriesHolder(holder, e)
    }

    /**
     * Host-first playback, ported from early main. An accepted native s4()
     * is ONLY a business selection; x05.w + TTVideoEngine own VideoModel
     * loading, native play(), readiness and Surface lifecycles.
     *
     * The previous module-level no-surface flag, eager holder.resume()
     * and 45s lease could race the actual source loader. Never start,
     * seek, pause, replace or force-resume the native engine here.
     * One observation per (EOF generation, selected index).
     */
    private fun scheduleStart() {
        val selected = state.snapshot()
        val key = selected.generation to selected.pendingIndex
        if (selected.phase != NativeSeriesV3State.Phase.BOUND ||
            observedNativeStart == key) return
        observedNativeStart = key
        val engineRef = WeakReference(state.pendingEngine())
        trace("startup", "observed",
            "owner=original_host,target=" + (selected.pendingIndex ?: -1) +
                ",background=" + SystemMediaBridge.isOfficialSeriesBackground())
        // Diagnostics ONLY: do not retry play on a timer. The host must
        // bind a source and reach real progress before we publish success.
        handler.postDelayed({
            val latest = state.snapshot()
            val engine = engineRef.get() ?: return@postDelayed
            if (latest.generation != selected.generation ||
                latest.pendingIndex != selected.pendingIndex ||
                !state.isPendingEngine(engine)) return@postDelayed
            val hasSource = call(engine, "getVideoModel") != null
            val playbackState = call(engine, "getPlaybackState")
            trace("startup", "degraded",
                "original_host_no_progress,target=" + latest.pendingIndex +
                    ",model=$hasSource,state=" + (playbackState ?: "unknown") +
                    ",phase=" + latest.phase)
        }, 8000L)
    }

    /** Observe a real SDK VideoModel assignment to the pending engine.
     * NEVER trigger resume from this callback; playback remains host-owned. */
    fun onHostVideoModel(engine: Any?, model: Any?) {
        if (engine == null || !state.isPendingEngine(engine)) return
        trace("source", "observed",
            "setVideoModel,pendingIndex=" + state.snapshot().pendingIndex +
                ",supplied=" + (model != null) +
                ",engineHasModel=" + (call(engine, "getVideoModel") != null))
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
            manualSelectedGeneration = -1L
            manualSelectedIndex = null
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

    /** Permit the official old-engine pause only while executing the
     * actual user-requested host skip. Does not affect background lifecycle
     * pauses or any other engine/adapter/thread. */
    fun isOriginalManualSkipPause(engine: Any?): Boolean =
        engine != null && originalSkipEngine.get() === engine &&
            SystemMediaBridge.isOfficialSeriesManualSkipBackground()

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
        val hostCommand = method(a, entry, 0) ?: return "host_command_missing_$entry"
        val target = current + delta
        val scoped = SystemMediaBridge.isOfficialSeriesManualSkipBackground()
        // Arm before calling the real adapter: its pager callbacks may run
        // synchronously or on the next UI event. An invalid/stale request
        // cannot bypass the native visibility checks.
        val request = if (scoped) ManualSkipRequest(
            a, current, target, state.snapshot().generation,
            SystemClock.uptimeMillis()) else null
        manualSkipRequest = request
        if (request != null) handler.postDelayed({
            if (manualSkipRequest === request) {
                manualSkipRequest = null
                trace("manual_skip", "degraded",
                    "reason=selection_not_observed,target=$target")
            }
        }, 2600L)
        val result = try {
            // Original g()/A1() internally pauses the old TTVideoEngine
            // before it binds the selected next holder. Suppressing that
            // pause as if it were a lifecycle exit strands the next video.
            originalSkipEngine.set(currentEngine.get())
            hostCommand.invoke(a)
        } catch (t: Throwable) {
            manualSkipRequest = null
            trace("manual_skip", "failed", t.javaClass.simpleName)
            return "host_exception_" + t.javaClass.simpleName
        } finally {
            originalSkipEngine.remove()
        }
        if (result == false) {
            manualSkipRequest = null
            trace("manual_skip", "rejected",
                "reason=host_command_returned_false,target=$target")
            return "host_command_rejected"
        }
        // The original host returns after the pager request; its business
        // s4 selection can arrive in a later callback. Never report playback
        // merely from the two indices changing.
        val selected = d(a) == target && page(a) == target
        trace("manual_skip",
            if (selected) "selected" else "pending",
            "command=$entry,result=$result,target=$target,D=" +
                (d(a) ?: -1) + ",pager=" + (page(a) ?: -1) +
                ",background=$scoped")
        return if (selected) "selected" else "native_selection_pending"
    }

    fun onExplicitPause() {
        acceptedManualGate = null
        manualSkipRequest = null
        manualSelectedGeneration = -1L
        manualSelectedIndex = null
        state.onPause(state.pendingEngine(), true)
    }

    fun reset() {
        acceptedManualGate = null
        manualSkipRequest = null
        manualSelectedGeneration = -1L
        manualSelectedIndex = null
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
        observedNativeStart = null
        scopedPage.remove()
        currentG4.remove()
    }
}
