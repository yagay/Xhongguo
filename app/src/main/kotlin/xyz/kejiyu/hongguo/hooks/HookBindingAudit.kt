package xyz.kejiyu.hongguo.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import xyz.kejiyu.hongguo.LogUtil

internal class HookBindingAudit {
    private var installed = 0
    private var missing = 0
    private var failed = 0
    fun methods(type: Class<*>, name: String, id: String,
                install: (Method, Int) -> Unit): Int {
        if (name.isBlank()) return 0
        val methods = try {
            type.declaredMethods.filter { it.name == name &&
                !Modifier.isAbstract(it.modifiers) }
                .sortedBy { it.toGenericString() }
        } catch (e: Throwable) {
            failed++
            LogUtil.warn("Hook审计: inspect $id " + type.name + "#" + name + " " + e)
            return 0
        }
        if (methods.isEmpty()) {
            missing++
            LogUtil.info("Hook审计: 未匹配 $id " + type.name + "#" + name)
        }
        var count = 0
        for ((index, method) in methods.withIndex()) {
            if (attempt(id, method.toGenericString()) { install(method, index) }) count++
        }
        return count
    }
    fun attempt(id: String, signature: String, install: () -> Unit): Boolean = try {
        install()
        installed++
        true
    } catch (e: Throwable) {
        failed++
        LogUtil.warn("Hook审计: 安装失败 $id $signature " + e.javaClass.name + ": " + e.message)
        false
    }
    fun report() {
        LogUtil.info("Hook审计: helper installed=$installed missing=$missing failed=$failed")
    }
    companion object {
        fun instanceVoid(method: Method, vararg params: String): Boolean =
            method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                method.parameterTypes.map { it.name } == params.toList()
        fun videoEvent(method: Method): Boolean = when (method.name) {
            "onEnginePlayStart" -> instanceVoid(method,
                "com.ss.android.videoshop.api.VideoStateInquirer",
                "com.ss.android.videoshop.entity.PlayEntity", "int")
            "onVideoPlay", "onVideoPause", "onVideoReleased" -> instanceVoid(method,
                "com.ss.android.videoshop.api.VideoStateInquirer",
                "com.ss.android.videoshop.entity.PlayEntity")
            else -> false
        }
    }
}
