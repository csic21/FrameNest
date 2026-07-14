package com.framenest.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressRulesTest {

    @Test
    fun isCompleted_whenRemainingUnder30s() {
        val duration = 120_000L
        // 29s remaining
        assertTrue(PlaybackProgressRules.isCompleted(duration - 29_000L, duration))
        // exactly 30s remaining → not completed by remaining rule alone (90% not met either)
        // 90s / 120s = 0.75 < 0.9, remaining = 30s not < 30s
        assertFalse(PlaybackProgressRules.isCompleted(duration - 30_000L, duration))
    }

    @Test
    fun isCompleted_whenFractionAtLeast90Percent() {
        val duration = 1_000_000L
        assertTrue(PlaybackProgressRules.isCompleted(900_000L, duration))
        assertFalse(PlaybackProgressRules.isCompleted(899_000L, duration))
    }

    @Test
    fun isCompleted_whenPositionAtOrPastDuration() {
        assertTrue(PlaybackProgressRules.isCompleted(100, 100))
        assertTrue(PlaybackProgressRules.isCompleted(150, 100))
    }

    @Test
    fun isCompleted_falseWhenDurationUnknown() {
        assertFalse(PlaybackProgressRules.isCompleted(50_000L, 0L))
        assertFalse(PlaybackProgressRules.isCompleted(50_000L, -1L))
    }

    @Test
    fun resumePosition_zeroWhenCompleted() {
        assertEquals(0L, PlaybackProgressRules.resumePositionMs(95_000L, 100_000L, completed = true))
        assertEquals(0L, PlaybackProgressRules.resumePositionMs(95_000L, 100_000L, completed = false))
    }

    @Test
    fun resumePosition_keepsMidProgress() {
        assertEquals(40_000L, PlaybackProgressRules.resumePositionMs(40_000L, 100_000L, completed = false))
    }

    @Test
    fun shouldPersist_requiresDelta() {
        assertTrue(PlaybackProgressRules.shouldPersist(null, 0L))
        assertFalse(PlaybackProgressRules.shouldPersist(5_000L, 5_500L, minDeltaMs = 1_000L))
        assertTrue(PlaybackProgressRules.shouldPersist(5_000L, 6_000L, minDeltaMs = 1_000L))
    }

    @Test
    fun shortVideo_remainingRuleDominates() {
        // 20s video: watching 5s leaves 15s remaining → completed
        assertTrue(PlaybackProgressRules.isCompleted(5_000L, 20_000L))
    }
}
