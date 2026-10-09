package com.framenest.feature.player

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
    fun `starts unfired and latches on markFired`() {
        val gate = ResumeSeekGate()
        assertFalse(gate.hasFired)
        gate.markFired()
        assertTrue(gate.hasFired)
    }

    @Test
    fun `reset re-arms for a fresh prepare cycle`() {
        val gate = ResumeSeekGate()
        gate.markFired()
        assertTrue(gate.hasFired)
        gate.reset()
        assertFalse(gate.hasFired)
    }

    @Test
    fun `retry position prefers live snapshot before saved fallback`() {
        assertEquals(42_000L, retryResumePosition(42_000L, 40_000L))
        assertEquals(0L, retryResumePosition(0L, 40_000L))
        assertEquals(40_000L, retryResumePosition(null, 40_000L))
        assertEquals(0L, retryResumePosition(null, null))
    }

    @Test fun `overlapping retry retains target across native idle reset`() {
        val latch = RetryResumeLatch()
        assertEquals(42_000L, latch.capture(42_000L))
        assertEquals(42_000L, latch.capture(0L))
        assertEquals(42_000L, latch.capture(0L))
        latch.ready()
        assertEquals(84_000L, latch.capture(84_000L))
    }

    @Test fun `explicit user seek zero overrides held retry target`() {
        val latch = RetryResumeLatch()
        latch.capture(42_000L)
        latch.userSeek(0L)
        assertEquals(0L, latch.capture(42_000L))
        latch.ready()
        assertEquals(0L, latch.capture(0L))
    }
}
