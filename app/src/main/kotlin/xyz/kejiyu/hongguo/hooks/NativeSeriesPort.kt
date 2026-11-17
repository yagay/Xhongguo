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
    const val SERIES_PLAYER = "x05.w"
    const val EPISODE_MODEL =
        "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData"

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
