package xyz.kejiyu.hongguo.hooks

import java.lang.reflect.Method

/**
 * Version-scoped adapter for the official 7.3.5.32 short-series API.
 *
 * The rest of the app must not manipulate D/k/h directly, synthesise
 * ViewPager2 positions, or call r4 after a delayed UI change. One native
 * business command is responsible for changing episodes.
 */
internal object NativeSeriesPort {
    const val SERIES_ADAPTER = "com.dragon.read.component.shortvideo.impl.v2.view.adapter.f"
    const val BASE_ADAPTER = "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a"
    const val SERIES_FRAGMENT =
        "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
    const val SERIES_PLAYER = "x05.w"
    const val EPISODE_MODEL =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"

    data class Compatibility(
        val present: Int,
        val expected: Int,
        val missing: List<String>
    ) {
        val compatible: Boolean get() = missing.isEmpty()
    }

    /** Observe signature drift once at install time. Class existence alone
     * does not prove that a particular obfuscated business Hook can work. */
    fun verify(loader: ClassLoader): Compatibility {
        val spec = listOf(
            Triple(SERIES_PLAYER, "onCompletion", 1),
            Triple(SERIES_PLAYER, "L", 1),
            Triple(SERIES_PLAYER, "resume", 0),
            Triple(SERIES_PLAYER, "v", 1),
            Triple(BASE_ADAPTER, "A1", 0),
            Triple(BASE_ADAPTER, "s4", 1),
            Triple(BASE_ADAPTER, "l0", 0),
            Triple(SERIES_ADAPTER, "g", 0),
            Triple(SERIES_ADAPTER, "r4", 1),
            Triple(SERIES_FRAGMENT, "nh", 0)
        )
        val missing = ArrayList<String>()
        for ((className, methodName, arity) in spec) {
            val found = try {
                val type = Class.forName(className, false, loader)
                var owner: Class<*>? = type
                var exists = false
                while (owner != null && owner != Any::class.java) {
                    if (owner.declaredMethods.any {
                            it.name == methodName && it.parameterCount == arity
                        }) {
                        exists = true
                        break
                    }
                    owner = owner.superclass
                }
                exists
            } catch (_: Throwable) { false }
            if (!found) missing.add(className + "." + methodName +
                "(" + arity + ")")
        }
        return Compatibility(spec.size - missing.size, spec.size, missing)
    }

    data class Selection(
        val index: Int,
        val loadedCount: Int,
        val pagerAligned: Boolean
    ) {
        fun canMove(delta: Int): Boolean =
            pagerAligned && delta in -1..1 && delta != 0 &&
                index + delta in 0 until loadedCount
    }

    data class CommandResult(
        val invoked: Boolean,
        val target: Int?,
        val accepted: Boolean,
        val reason: String
    )

    private fun field(obj: Any?, name: String): Any? {
        var type = obj?.javaClass
        while (type != null && type != Any::class.java) {
            try {
                return type.getDeclaredField(name).apply {
                    isAccessible = true
                }.get(obj)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            } catch (_: Throwable) { return null }
        }
        return null
    }

    private fun method(obj: Any, name: String, parameters: Int): Method? {
        var type: Class<*>? = obj.javaClass
        while (type != null && type != Any::class.java) {
            val matching = type.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == parameters
            }
            if (matching != null) {
                return try {
                    matching.apply { isAccessible = true }
                } catch (_: Throwable) { null }
            }
            type = type.superclass
        }
        return null
    }

    /**
     * Official APK 7.3.5.32 has a no-Surface playback mode of its own:
     * x05.w.resume() -> c15.d.i (playWithoutSurface) ->
     * c15.d$e.a -> c15.d$e.run() -> TTVideoEngine.play().
     *
     * While backgrounded, a newly selected episode has NO usable UI Surface,
     * so an ordinary resume queues a deferred command instead of playing.
     * For an independently verified accepted next-episode handoff, enable
     * that EXISTING host mode only for the synchronous original resume call.
     * No synthetic playback, second engine, fake Surface or pager replay.
     */
    data class NativeResumeResult(
        val invoked: Boolean,
        val withoutSurface: Boolean,
        val surfaceAvailable: Boolean?,
        val reason: String
    )

    fun resumeAcceptedBackgroundEpisode(
        player: Any?, expectedEngine: Any?
    ): NativeResumeResult {
        if (player == null || expectedEngine == null ||
            player.javaClass.name != SERIES_PLAYER) {
            return NativeResumeResult(false, false, null,
                "invalid_player_or_engine")
        }
        val resume = method(player, "resume", 0)
            ?: return NativeResumeResult(false, false, null,
                "official_resume_missing")
        val task = field(player, "f")
        val validTask = task?.javaClass?.name == "c15.d" &&
            field(task, "b") === expectedEngine
        val holder = if (validTask) field(task, "c") else null
        val surface = try {
            holder?.let {
                (method(it, "isSurfaceAvailable", 0)
                    ?: it.javaClass.methods.firstOrNull { m ->
                        m.name == "isSurfaceAvailable" &&
                            m.parameterCount == 0
                    })?.invoke(it) as? Boolean
            }
        } catch (_: Throwable) { null }

        // Never guess the native flag on an unknown APK signature. Run the
        // original command unchanged if the actual Surface state is unknown.
        val noSurface = validTask && surface == false
        val flag = if (noSurface) try {
            task!!.javaClass.getDeclaredField("i").apply {
                isAccessible = true
            }.takeIf { it.type == Boolean::class.javaPrimitiveType }
        } catch (_: Throwable) { null } else null

        return try {
            if (flag != null && task != null) {
                synchronized(task) {
                    val original = flag.getBoolean(task)
                    try {
                        if (!original) flag.setBoolean(task, true)
                        resume.invoke(player)
                    } finally {
                        if (!original && flag.getBoolean(task))
                            flag.setBoolean(task, false)
                    }
                }
                NativeResumeResult(true, true, surface,
                    "official_resume_without_surface")
            } else {
                resume.invoke(player)
                NativeResumeResult(true, false, surface,
                    if (surface == false)
                        "no_surface_host_flag_unavailable"
                    else "official_resume_unchanged")
            }
        } catch (e: Throwable) {
            NativeResumeResult(false, false, surface,
                "official_resume_failed:" + e.javaClass.simpleName)
        }
    }

    fun selectedModel(adapter: Any?): Any? {
        val a = adapter ?: return null
        val result = try { method(a, "l0", 0)?.invoke(a) }
            catch (_: Throwable) { null }
        return result?.takeIf { it.javaClass.name == EPISODE_MODEL }
    }

    fun selection(adapter: Any?): Selection? {
        val a = adapter ?: return null
        val index = (field(a, "D") as? Number)?.toInt() ?: return null
        val items = field(a, "h") as? List<*> ?: return null
        if (items.size !in 1..5000 || index !in items.indices) return null
        val pager = field(a, "k") ?: return null
        val page = try {
            (method(pager, "getCurrentItem", 0)?.invoke(pager)
                as? Number)?.toInt()
        } catch (_: Throwable) { null }
        return Selection(index, items.size, page == index)
    }

    fun skip(adapter: Any?, delta: Int): CommandResult {
        if (adapter == null || delta !in listOf(-1, 1))
            return CommandResult(false, null, false, "invalid_command")
        val before = selection(adapter)
            ?: return CommandResult(false, null, false, "selection_unavailable")
        if (!before.canMove(delta))
            return CommandResult(false, before.index + delta, false,
                if (!before.pagerAligned) "pager_not_aligned" else "episode_boundary")
        // The APK business controls own both ViewPager2 and adapter selection.
        // A1() is the previous command, f.g() the next command.
        val entry = if (delta < 0) "A1" else "g"
        val native = method(adapter, entry, 0)
            ?: return CommandResult(false, before.index + delta, false,
                "native_method_missing:$entry")
        return try {
            val answer = native.invoke(adapter)
            val selected = selection(adapter)
            val accepted = answer != false && selected?.index ==
                before.index + delta && selected.pagerAligned
            CommandResult(true, before.index + delta, accepted,
                if (accepted) "selected" else if (answer == false)
                    "host_rejected" else "selection_pending")
        } catch (e: Throwable) {
            CommandResult(false, before.index + delta, false,
                "invoke_failed:" + e.javaClass.simpleName)
        }
    }
}
