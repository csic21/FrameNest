package com.framenest.feature.player

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAudioFocusPolicyTest {

    @Test
    fun mapsGrantedDelayedAndFailedWithoutTreatingDelayAsGranted() {
        assertEquals(
            AudioFocusRequestResult.Granted,
            audioFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
        )
        assertEquals(
            AudioFocusRequestResult.Delayed,
            audioFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_DELAYED),
        )
        assertEquals(
            AudioFocusRequestResult.Failed,
            audioFocusRequestResult(AudioManager.AUDIOFOCUS_REQUEST_FAILED),
        )
    }
}
