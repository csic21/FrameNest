package com.framenest.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbBenchmarkTest {

    @Test
    fun samplePositions_areWithinReadableRange() {
        val size = 10_000_000L
        val read = 64_000L
        val positions = SmbBenchmark.samplePositions(size, 8, read)
        assertEquals(8, positions.size)
        positions.forEach { pos ->
            assertTrue(pos >= 0)
            assertTrue(pos <= size - read)
        }
        // Strictly increasing for deterministic midpoints
        assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun samplePositions_emptyWhenFileTooSmall() {
        assertTrue(SmbBenchmark.samplePositions(100, 8, 64_000).isEmpty())
    }
}
