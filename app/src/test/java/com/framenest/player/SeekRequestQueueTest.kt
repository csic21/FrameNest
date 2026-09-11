package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekRequestQueueTest {
    @Test
    fun `one hundred rapid retargets issue only the first and final target`() {
        val queue = SeekRequestQueue()
        val issued = mutableListOf<Long>()

        repeat(100) { index ->
            queue.submit((index + 1) * 1_000L)
            queue.poll(nowMs = index * 10L)?.let(issued::add)
        }

        assertEquals(listOf(1_000L), issued)
        assertEquals(100_000L, queue.latestTargetMs)
        assertTrue(queue.hasPending)

        queue.markSettled()
        queue.poll(nowMs = 1_000L)?.let(issued::add)

        assertEquals(listOf(1_000L, 100_000L), issued)
        assertFalse(queue.hasPending)
        assertNull(queue.poll(nowMs = 1_001L))
    }

    @Test
    fun `missing acknowledgement releases only the latest target at timeout`() {
        val queue = SeekRequestQueue(timeoutMs = 1_500L)
        queue.submit(10_000L)
        assertEquals(10_000L, queue.poll(nowMs = 200L))
        queue.submit(20_000L)
        queue.submit(30_000L)

        assertNull(queue.poll(nowMs = 1_699L))
        assertEquals(30_000L, queue.poll(nowMs = 1_700L))
        assertEquals(30_000L, queue.inFlightTargetMs)
        assertFalse(queue.hasPending)
    }

    @Test
    fun `timeout starts again when the next seek is issued`() {
        val queue = SeekRequestQueue(timeoutMs = 1_500L)
        queue.submit(10_000L)
        queue.poll(nowMs = 0L)
        queue.submit(20_000L)
        assertEquals(20_000L, queue.poll(nowMs = 1_500L))
        queue.submit(30_000L)

        assertNull(queue.poll(nowMs = 2_999L))
        assertEquals(30_000L, queue.poll(nowMs = 3_000L))
    }

    @Test
    fun `returning to the in flight target cancels the intermediate target`() {
        val queue = SeekRequestQueue()
        queue.submit(10_000L)
        queue.poll(nowMs = 0L)
        queue.submit(20_000L)
        queue.submit(10_000L)

        assertFalse(queue.hasPending)
        assertEquals(10_000L, queue.latestTargetMs)
        queue.markSettled()
        assertNull(queue.poll(nowMs = 100L))
        assertNull(queue.latestTargetMs)
    }

    @Test
    fun `repeated identical pending targets are issued once`() {
        val queue = SeekRequestQueue()
        queue.submit(10_000L)
        queue.poll(nowMs = 0L)
        repeat(10) { queue.submit(20_000L) }

        queue.markSettled()
        assertEquals(20_000L, queue.poll(nowMs = 100L))
        queue.markSettled()
        assertNull(queue.poll(nowMs = 101L))
    }

    @Test
    fun `relative seek base follows pending then issued target until settled`() {
        val queue = SeekRequestQueue()
        assertNull(queue.latestTargetMs)
        queue.submit(10_000L)
        assertEquals(10_000L, queue.latestTargetMs)
        queue.poll(nowMs = 0L)
        queue.submit(checkNotNull(queue.latestTargetMs) + 10_000L)
        queue.submit(checkNotNull(queue.latestTargetMs) + 10_000L)

        assertEquals(30_000L, queue.latestTargetMs)
        assertEquals(10_000L, queue.inFlightTargetMs)
        queue.markSettled()
        assertEquals(30_000L, queue.latestTargetMs)
        queue.poll(nowMs = 100L)
        assertEquals(30_000L, queue.latestTargetMs)
        queue.markSettled()
        assertNull(queue.latestTargetMs)
    }

    @Test
    fun `reset drops issued and queued targets and immediately permits a fresh seek`() {
        val queue = SeekRequestQueue()
        queue.submit(10_000L)
        queue.poll(nowMs = 0L)
        queue.submit(20_000L)

        queue.reset()

        assertNull(queue.latestTargetMs)
        assertNull(queue.inFlightTargetMs)
        assertFalse(queue.hasPending)
        assertNull(queue.poll(nowMs = 100L))
        queue.submit(0L)
        assertEquals(0L, queue.poll(nowMs = 100L))
    }

    @Test
    fun `expired seek without pending work does not repeat and releases stale target`() {
        val queue = SeekRequestQueue(timeoutMs = 1_500L)
        queue.submit(10_000L)
        queue.poll(nowMs = 0L)

        assertNull(queue.poll(nowMs = 1_500L))
        assertNull(queue.latestTargetMs)
        assertNull(queue.inFlightTargetMs)
    }
}
