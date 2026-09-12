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
        assertEquals(40_000L, retryResumePosition(0L, 40_000L))
        assertEquals(0L, retryResumePosition(null, null))
    }
}
