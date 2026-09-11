package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeFormatTest {

    @Test
    fun `short clips stay m-ss`() {
        assertEquals("0:00", PlayerTimeFormat.formatDuration(0L))
        assertEquals("0:05", PlayerTimeFormat.formatDuration(5_000L))
        assertEquals("1:05", PlayerTimeFormat.formatDuration(65_000L))
        assertEquals("59:59", PlayerTimeFormat.formatDuration(3_599_999L))
    }

    @Test
    fun `movies at or past one hour use h-mm-ss`() {
        assertEquals("1:00:00", PlayerTimeFormat.formatDuration(3_600_000L))
        assertEquals("1:00:01", PlayerTimeFormat.formatDuration(3_601_000L))
        assertEquals("2:05:09", PlayerTimeFormat.formatDuration(7_509_000L))
        assertEquals("10:00:00", PlayerTimeFormat.formatDuration(36_000_000L))
    }

    @Test
    fun `negative clocks clamp to zero`() {
        assertEquals("0:00", PlayerTimeFormat.formatDuration(-1L))
        assertEquals("0:00", PlayerTimeFormat.formatDuration(Long.MIN_VALUE))
    }

    @Test
    fun `signed deltas keep existing typography`() {
        assertEquals("+0:10", PlayerTimeFormat.formatSignedDelta(10_000L))
        assertEquals("−0:10", PlayerTimeFormat.formatSignedDelta(-10_000L))
        assertEquals("+1:01:01", PlayerTimeFormat.formatSignedDelta(3_661_000L))
        assertEquals("+0:00", PlayerTimeFormat.formatSignedDelta(0L))
    }
}
