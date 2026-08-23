package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubSeekPolicyTest {
    @Test
    fun `release target is precise and clamped`() {
        assertEquals(60_000L, ScrubSeekPolicy.targetMs(120_000L, 0.5f))
        assertEquals(0L, ScrubSeekPolicy.targetMs(120_000L, -0.2f))
        assertEquals(120_000L, ScrubSeekPolicy.targetMs(120_000L, 1.2f))
        assertEquals(0L, ScrubSeekPolicy.targetMs(0L, 0.5f))
    }

    @Test
    fun `first preview emits immediately`() {
        assertTrue(
            ScrubSeekPolicy.shouldEmitPreview(
                nowMs = 1_000L,
                lastPreviewAtMs = 0L,
                lastTargetMs = Long.MIN_VALUE,
                targetMs = 12_000L,
            ),
        )
    }

    @Test
    fun `preview is throttled by time and delta`() {
        assertFalse(
            ScrubSeekPolicy.shouldEmitPreview(
                nowMs = 1_050L,
                lastPreviewAtMs = 1_000L,
                lastTargetMs = 10_000L,
                targetMs = 12_000L,
            ),
        )
        assertFalse(
            ScrubSeekPolicy.shouldEmitPreview(
                nowMs = 1_200L,
                lastPreviewAtMs = 1_000L,
                lastTargetMs = 10_000L,
                targetMs = 10_200L,
            ),
        )
        assertTrue(
            ScrubSeekPolicy.shouldEmitPreview(
                nowMs = 1_200L,
                lastPreviewAtMs = 1_000L,
                lastTargetMs = 10_000L,
                targetMs = 12_000L,
            ),
        )
        assertFalse(
            ScrubSeekPolicy.shouldEmitPreview(
                nowMs = 1_400L,
                lastPreviewAtMs = 1_000L,
                lastTargetMs = 10_000L,
                targetMs = 10_000L,
            ),
        )
    }
}
