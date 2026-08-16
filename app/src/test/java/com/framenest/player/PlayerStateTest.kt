package com.framenest.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {

    @Test
    fun canPlay_requiresFirstFrameReady() {
        assertFalse(PlayerState(phase = PlayerState.Phase.Ready, firstFrameReady = false).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Paused, firstFrameReady = false).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Ended, firstFrameReady = false).canPlay)

        assertTrue(PlayerState(phase = PlayerState.Phase.Ready, firstFrameReady = true).canPlay)
        assertTrue(PlayerState(phase = PlayerState.Phase.Paused, firstFrameReady = true).canPlay)
        assertTrue(PlayerState(phase = PlayerState.Phase.Ended, firstFrameReady = true).canPlay)

        assertFalse(PlayerState(phase = PlayerState.Phase.Preparing, firstFrameReady = false).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Playing, firstFrameReady = true).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Error, firstFrameReady = false).canPlay)
    }

    @Test
    fun canPause_onlyWhenPlaying() {
        assertTrue(PlayerState(phase = PlayerState.Phase.Playing).canPause)
        assertFalse(PlayerState(phase = PlayerState.Phase.Ready).canPause)
        assertFalse(PlayerState(phase = PlayerState.Phase.Paused).canPause)
    }

    @Test
    fun pausedSeek_keepsPlayAsTheNextUserAction() {
        val state = PlayerState(
            phase = PlayerState.Phase.Paused,
            firstFrameReady = true,
            isSeeking = true,
        )

        assertTrue(state.canPlay)
        assertFalse(state.canPause)
    }
}
