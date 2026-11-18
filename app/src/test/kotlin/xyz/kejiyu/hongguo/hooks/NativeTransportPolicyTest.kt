package xyz.kejiyu.hongguo.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTransportPolicyTest {
    @Test fun protectOnlyBackgroundOrMiniLifecyclePauses() {
        assertTrue(NativeTransportPolicy.holdLifecyclePause(
            false, false, false, true, false))
        assertTrue(NativeTransportPolicy.holdLifecyclePause(
            false, false, false, false, true))
        assertFalse(NativeTransportPolicy.holdLifecyclePause(
            false, false, false, false, false))
    }

    @Test fun alwaysYieldToRealHostTransportAndManualEpisodeChange() {
        assertFalse(NativeTransportPolicy.holdLifecyclePause(
            true, false, false, true, true))
        assertFalse(NativeTransportPolicy.holdLifecyclePause(
            false, true, false, true, true))
        assertFalse(NativeTransportPolicy.holdLifecyclePause(
            false, false, true, true, true))
    }

    @Test fun onlyExplicitManualSelectionMayOverrideOffscreenPageGate() {
        assertFalse(NativeTransportPolicy.allowScopedPageOverride(false, true))
        assertFalse(NativeTransportPolicy.allowScopedPageOverride(true, false))
        assertTrue(NativeTransportPolicy.allowScopedPageOverride(true, true))
    }
}
