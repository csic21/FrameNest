package com.framenest.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubSeekPolicyTest {
    @Test
    fun `preview seek is bounded by time and target movement`() {
        assertTrue(shouldPreviewScrubSeek(1_000L, 10_000L, 0L, -1L))
        assertFalse(shouldPreviewScrubSeek(1_100L, 12_000L, 1_000L, 10_000L))
        assertFalse(shouldPreviewScrubSeek(1_200L, 10_400L, 1_000L, 10_000L))
        assertTrue(shouldPreviewScrubSeek(1_200L, 10_500L, 1_000L, 10_000L))
    }
}
