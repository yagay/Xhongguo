package com.yagay.xhongguo.hooks

import org.junit.Assert.*
import org.junit.Test

class PlaybackGateTest {
    @Test fun overlappingInterruptionMustNotResumeEarly() {
        val gate = PlaybackGate()
        gate.set(PlaybackGate.Blocker.EXTERNAL, true)
        gate.set(PlaybackGate.Blocker.CALL, true)
        gate.set(PlaybackGate.Blocker.FOCUS, true)
        assertTrue(gate.blocked())
        gate.set(PlaybackGate.Blocker.EXTERNAL, false)
        gate.set(PlaybackGate.Blocker.CALL, false)
        assertTrue(gate.hardBlocked())
        gate.set(PlaybackGate.Blocker.FOCUS, false)
        assertFalse(gate.blocked())
    }

    @Test fun manualHoldRemainsAfterExternalAudioEnds() {
        val gate = PlaybackGate()
        gate.set(PlaybackGate.Blocker.HOLD, true)
        gate.set(PlaybackGate.Blocker.EXTERNAL, true)
        gate.set(PlaybackGate.Blocker.EXTERNAL, false)
        assertTrue(gate.blocked())
        assertFalse(gate.hardBlocked())
        gate.set(PlaybackGate.Blocker.HOLD, false)
        assertFalse(gate.blocked())
    }

    @Test fun manualActionAndResetInvalidateTicket() {
        val gate = PlaybackGate()
        val old = gate.epoch()
        assertTrue(gate.userAction() > old)
        val second = gate.epoch()
        gate.set(PlaybackGate.Blocker.CALL, true)
        gate.reset()
        assertFalse(gate.blocked())
        assertTrue(gate.epoch() > second)
    }
}
