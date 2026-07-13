package com.framenest.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {

    @Test
    fun canPlay_whenReadyOrPausedOrEnded() {
        assertTrue(PlayerState(phase = PlayerState.Phase.Ready).canPlay)
        assertTrue(PlayerState(phase = PlayerState.Phase.Paused).canPlay)
        assertTrue(PlayerState(phase = PlayerState.Phase.Ended).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Preparing).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Playing).canPlay)
        assertFalse(PlayerState(phase = PlayerState.Phase.Error).canPlay)
    }

    @Test
    fun canPause_onlyWhenPlaying() {
        assertTrue(PlayerState(phase = PlayerState.Phase.Playing).canPause)
        assertFalse(PlayerState(phase = PlayerState.Phase.Ready).canPause)
        assertFalse(PlayerState(phase = PlayerState.Phase.Paused).canPause)
    }
}
