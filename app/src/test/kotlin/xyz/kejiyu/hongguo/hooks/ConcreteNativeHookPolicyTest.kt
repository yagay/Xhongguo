package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class ConcreteNativeHookPolicyTest {
    interface NativeController {
        fun i9(): Boolean
        fun y4(): Boolean
    }
    class RuntimeController : NativeController {
        override fun i9(): Boolean = false
        override fun y4(): Boolean = true
    }
    @Test fun rejectsInterfaceAndAcceptsImplementation() {
        val abstractGate = NativeController::class.java.getDeclaredMethod("i9")
        val realGate = RuntimeController::class.java.getDeclaredMethod("i9")
        assertFalse(ConcreteNativeHookPolicy.isConcreteBooleanNoArgs(
            abstractGate, "i9"))
        assertTrue(ConcreteNativeHookPolicy.isConcreteBooleanNoArgs(
            realGate, "i9"))
        assertTrue(ConcreteNativeHookPolicy.isConcreteBooleanNoArgs(
            RuntimeController::class.java.getDeclaredMethod("y4"), "y4"))
        assertFalse(ConcreteNativeHookPolicy.isConcreteBooleanNoArgs(
            realGate, "y4"))
    }
}
