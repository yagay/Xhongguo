package xyz.kejiyu.hongguo.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * In APK 7.3.5.32 j15.d declares abstract i9()/y4().
 * Only the actual runtime implementation can be installed as a hook.
 */
internal object ConcreteNativeHookPolicy {
    fun isConcreteBooleanNoArgs(method: Method, name: String): Boolean =
        method.name == name &&
            method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            !Modifier.isAbstract(method.modifiers)
}
