package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrubSeekPolicyTest {
    @Test
    fun `release target is precise and clamped`() {
        assertEquals(60_000L, scrubSeekTargetMs(120_000L, 0.5f))
        assertEquals(0L, scrubSeekTargetMs(120_000L, -0.2f))
        assertEquals(120_000L, scrubSeekTargetMs(120_000L, 1.2f))
        assertEquals(0L, scrubSeekTargetMs(0L, 0.5f))
    }
}
