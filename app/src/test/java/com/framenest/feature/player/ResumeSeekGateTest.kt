package com.framenest.feature.player

import com.framenest.player.PlayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM rules for [ResumeSeekGate]. Mirrors the style of
 * [com.framenest.data.history.PlaybackProgressRulesTest]: no Android, no coroutines.
 */
class ResumeSeekGateTest {
    @Test
    fun `does not fire before first frame even in Ready`() {
        val gate = ResumeSeekGate()
        assertFalse(gate.shouldFire(firstFrameReady = false, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
    }

    @Test
    fun `does not fire when start position is zero`() {
        val gate = ResumeSeekGate()
        assertFalse(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 0L))
    }

    @Test
    fun `does not fire outside Ready or Paused`() {
        val gate = ResumeSeekGate()
        // Preparing / Playing / Ended / Idle / Error must not trigger the resume seek.
        for (phase in listOf(
            PlayerState.Phase.Preparing,
            PlayerState.Phase.Playing,
            PlayerState.Phase.Ended,
            PlayerState.Phase.Idle,
            PlayerState.Phase.Error,
        )) {
            assertFalse(
                gate.shouldFire(firstFrameReady = true, phase = phase, startPositionMs = 5_000L)
            )
        }
    }

    @Test
    fun `fires once on Ready then never again until reset`() {
        val gate = ResumeSeekGate()
        assertTrue(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
        // Subsequent states (including Paused) must not re-trigger.
        assertFalse(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Paused, startPositionMs = 5_000L))
        assertFalse(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
    }

    @Test
    fun `fires once on Paused if Ready was missed`() {
        // User pressed play race: collect skipped Ready, next event is Paused.
        val gate = ResumeSeekGate()
        assertTrue(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Paused, startPositionMs = 5_000L))
        assertFalse(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Paused, startPositionMs = 5_000L))
    }

    @Test
    fun `markFired suppresses a later natural fire`() {
        // play() / retry() manual path latches the gate before collect sees Ready.
        val gate = ResumeSeekGate()
        gate.markFired()
        assertFalse(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
    }

    @Test
    fun `reset re-arms for a fresh prepare cycle`() {
        val gate = ResumeSeekGate()
        assertTrue(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
        gate.reset()
        assertTrue(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 9_000L))
    }

    @Test
    fun `hasFired tracks natural and manual latch`() {
        val gate = ResumeSeekGate()
        assertFalse(gate.hasFired)
        assertTrue(gate.shouldFire(firstFrameReady = true, phase = PlayerState.Phase.Ready, startPositionMs = 5_000L))
        assertTrue(gate.hasFired)
        gate.reset()
        assertFalse(gate.hasFired)
        gate.markFired()
        assertTrue(gate.hasFired)
    }

    @Test
    fun `retry position prefers live snapshot before saved fallback`() {
        assertEquals(42_000L, retryResumePosition(42_000L, 40_000L))
        assertEquals(40_000L, retryResumePosition(0L, 40_000L))
        assertEquals(0L, retryResumePosition(null, null))
    }
}
