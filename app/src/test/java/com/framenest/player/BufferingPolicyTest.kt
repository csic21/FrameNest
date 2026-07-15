package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferingPolicyTest {

    @Test
    fun fromEventProgress_fullClearsBuffering() {
        val snap = BufferingPolicy.fromEventProgress(100f)
        assertFalse(snap.isBuffering)
        assertEquals(100f, snap.percent, 0f)
    }

    @Test
    fun fromEventProgress_partialIsBuffering() {
        val snap = BufferingPolicy.fromEventProgress(42.5f)
        assertTrue(snap.isBuffering)
        assertEquals(42.5f, snap.percent, 0f)
    }

    @Test
    fun fromEventProgress_clampsAndHandlesNaN() {
        assertEquals(0f, BufferingPolicy.fromEventProgress(-5f).percent, 0f)
        assertTrue(BufferingPolicy.fromEventProgress(-5f).isBuffering)
        assertFalse(BufferingPolicy.fromEventProgress(Float.NaN).isBuffering)
        assertEquals(100f, BufferingPolicy.fromEventProgress(150f).percent, 0f)
        assertFalse(BufferingPolicy.fromEventProgress(150f).isBuffering)
    }

    @Test
    fun showOverlay_midStreamOnlyWithFirstFrame() {
        assertTrue(
            BufferingPolicy.showOverlay(
                PlayerState(
                    phase = PlayerState.Phase.Playing,
                    firstFrameReady = true,
                    isBuffering = true,
                    bufferPercent = 30f,
                ),
            ),
        )
        assertFalse(
            BufferingPolicy.showOverlay(
                PlayerState(
                    phase = PlayerState.Phase.Playing,
                    firstFrameReady = false,
                    isBuffering = true,
                    bufferPercent = 30f,
                ),
            ),
        )
    }

    @Test
    fun showOverlay_preparingYes_errorNo() {
        assertTrue(
            BufferingPolicy.showOverlay(
                PlayerState(phase = PlayerState.Phase.Preparing, isBuffering = true),
            ),
        )
        assertFalse(
            BufferingPolicy.showOverlay(
                PlayerState(
                    phase = PlayerState.Phase.Error,
                    isBuffering = true,
                    bufferPercent = 10f,
                ),
            ),
        )
        assertFalse(
            BufferingPolicy.showOverlay(
                PlayerState(
                    phase = PlayerState.Phase.Playing,
                    firstFrameReady = true,
                    isBuffering = false,
                ),
            ),
        )
    }
}
