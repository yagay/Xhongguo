package com.yagay.xhongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class NativeHookContractTest {
    class FakeEngine {
        fun play() {}
        fun play(value: Int) = value
        fun getVideoID(): String = "id"
        companion object { @JvmStatic fun staticProbe(): Boolean = true }
    }

    @Test fun exactSignatureRejectsWrongOverloadAndReturnType() {
        val c = NativeHookContract
        val cls = FakeEngine::class.java
        val matched = c.methods(cls, listOf(c.sig("play", "void")))
        assertEquals(1, matched.size)
        assertEquals(0, matched.single().parameterCount)
        assertFalse(c.has(cls, c.sig("getVideoID", "int")))
        assertTrue(c.has(cls, c.sig("getVideoID", "java.lang.String")))
        assertFalse(c.has(cls, c.sig("staticProbe", "boolean")))
        assertTrue(c.has(cls, c.sig("staticProbe", "boolean", static = true)))
    }
    @Test fun latestReferenceNeverInheritsHistoricalObfuscation() {
        val names = TargetNames.namesFor(TargetNames.CN_PACKAGE, "7.4.2.32")
        assertTrue(TargetNames.isUniversal(names))
        assertEquals("", names.shortHolder)
        assertTrue(names.hideIdNames.isEmpty())
        assertTrue(names.doubleTapHandlers.isEmpty())
        assertEquals("AUTO-CN-7.4.2.32", names.profileId)
    }
    @Test fun unknownVersionAlsoUsesEmptyUniversalProfile() {
        val names = TargetNames.namesFor(TargetNames.CN_PACKAGE, "99.0.0")
        assertTrue(TargetNames.isUniversal(names))
        assertTrue(names.staticHideIds.isEmpty())
        assertTrue(names.kmpAcctService.isEmpty())
    }
}
