package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewSessionTest {
    @Test
    fun `repeated paused seeks retarget one preview without losing pause obligation`() {
        val session = SeekPreviewSession()

        assertTrue(session.startOrRetarget(10_000L))
        assertFalse(session.startOrRetarget(35_000L))

        assertTrue(session.active)
        assertEquals(35_000L, session.targetMs)
    }

    @Test
    fun `clear ends preview and next seek starts a fresh session`() {
        val session = SeekPreviewSession()
        session.startOrRetarget(10_000L)

        assertTrue(session.clear())
        assertFalse(session.active)
        assertEquals(-1L, session.targetMs)
        assertTrue(session.startOrRetarget(20_000L))
    }
}
