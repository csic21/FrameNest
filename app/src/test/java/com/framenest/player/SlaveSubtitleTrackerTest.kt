package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlaveSubtitleTrackerTest {

    @Test
    fun addSlave_marksOnlyNewIds() {
        val tracker = SlaveSubtitleTracker()
        tracker.captureBeforeAddSlave(listOf(-1, 0, 1))
        val slaves = tracker.syncWithCurrentIds(listOf(-1, 0, 1, 2))

        assertEquals(setOf(2), slaves)
        assertTrue(tracker.isSlave(2))
        assertFalse(tracker.isSlave(0))
        assertFalse(tracker.isSlave(1))
        assertFalse(tracker.isSlave(-1))
    }

    @Test
    fun secondSlave_accumulatesWithoutReclassifyingEmbedded() {
        val tracker = SlaveSubtitleTracker()
        tracker.captureBeforeAddSlave(listOf(0, 1))
        tracker.syncWithCurrentIds(listOf(0, 1, 4))
        tracker.captureBeforeAddSlave(listOf(0, 1, 4))
        tracker.syncWithCurrentIds(listOf(0, 1, 4, 5))

        assertTrue(tracker.isSlave(4))
        assertTrue(tracker.isSlave(5))
        assertFalse(tracker.isSlave(0))
        assertFalse(tracker.isSlave(1))
    }

    @Test
    fun failedAddSlave_doesNotMarkLaterEmbeddedTracks() {
        val tracker = SlaveSubtitleTracker()
        tracker.captureBeforeAddSlave(listOf(0))
        tracker.cancelPending()
        tracker.syncWithCurrentIds(listOf(0, 1))

        assertFalse(tracker.isSlave(1))
    }

    @Test
    fun reset_clearsSlavesForNewMedia() {
        val tracker = SlaveSubtitleTracker()
        tracker.captureBeforeAddSlave(listOf(0))
        tracker.syncWithCurrentIds(listOf(0, 3))
        tracker.reset()

        assertFalse(tracker.isSlave(3))
        tracker.syncWithCurrentIds(listOf(0, 3))
        assertFalse(tracker.isSlave(3))
    }
}
