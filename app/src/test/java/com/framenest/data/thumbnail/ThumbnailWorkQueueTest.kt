package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailWorkQueueTest {

    @Test
    fun `released offscreen work does not delay the new visible item`() {
        val queue = ThumbnailWorkQueue<String>()
        val staleKeys = (1..500).map { ThumbnailWorkKey(1L, "stale-$it") }
        staleKeys.forEach { key -> assertTrue(queue.offer(key, key.digest)) }
        staleKeys.forEach { key -> assertTrue(queue.cancelPending(key)) }

        val visible = ThumbnailWorkKey(1L, "visible")
        assertTrue(queue.offer(visible, "visible-work"))

        assertEquals(visible to "visible-work", queue.takeNext())
        assertNull(queue.takeNext())
    }

    @Test
    fun `pending and in flight work are deduplicated`() {
        val queue = ThumbnailWorkQueue<String>()
        val key = ThumbnailWorkKey(4L, "same")

        assertTrue(queue.offer(key, "first"))
        assertFalse(queue.offer(key, "duplicate-pending"))
        assertEquals(key to "first", queue.takeNext())
        assertFalse(queue.offer(key, "duplicate-in-flight"))

        assertTrue(queue.finish(key))
        assertTrue(queue.offer(key, "after-finish"))
    }

    @Test
    fun `cancel only removes pending work and permits reentry`() {
        val queue = ThumbnailWorkQueue<String>()
        val key = ThumbnailWorkKey(2L, "video")

        assertTrue(queue.offer(key, "old-interest"))
        assertTrue(queue.cancelPending(key))
        assertFalse(queue.cancelPending(key))
        assertTrue(queue.offer(key, "new-interest"))
        assertEquals(key to "new-interest", queue.takeNext())
    }

    @Test
    fun `clear drops pending and in flight registrations`() {
        val queue = ThumbnailWorkQueue<String>()
        val inFlight = ThumbnailWorkKey(1L, "in-flight")
        val pending = ThumbnailWorkKey(1L, "pending")
        queue.offer(inFlight, "a")
        queue.takeNext()
        queue.offer(pending, "b")

        queue.clear()

        assertEquals(0, queue.pendingSize())
        assertEquals(0, queue.inFlightSize())
        assertTrue(queue.offer(inFlight, "new-a"))
        assertTrue(queue.offer(pending, "new-b"))
    }
}
