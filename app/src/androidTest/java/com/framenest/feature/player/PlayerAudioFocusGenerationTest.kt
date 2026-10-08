package com.framenest.feature.player

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerAudioFocusGenerationTest {
    @Test fun abandonedGainCannotSatisfyNewDelayedRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var gains = 0
            var losses = 0
            val focus = PlayerAudioFocus(instrumentation.targetContext, { losses++ }, { gains++ })
            try {
                focus.request()
                val old = currentListener(focus)
                focus.abandon() // ON_STOP retires this request.
                old.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(0, gains)
                focus.request() // Returning screen issues its own request.
                // The platform may grant or reject in test. Control only arrival
                // ordering by emulating a delayed result on the real request.
                PlayerAudioFocus::class.java.getDeclaredField("waitingForDelayedGain")
                    .apply { isAccessible = true }.setBoolean(focus, true)
                old.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                old.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                assertEquals(0, gains)
                assertEquals(0, losses)
                currentListener(focus).onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(1, gains)
            } finally {
                focus.abandon()
            }
        }
    }

    private fun currentListener(focus: PlayerAudioFocus): AudioManager.OnAudioFocusChangeListener {
        val generation = PlayerAudioFocus::class.java.getDeclaredField("requestGeneration")
            .apply { isAccessible = true }.getLong(focus)
        return PlayerAudioFocus::class.java.getDeclaredMethod("listener", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(focus, generation) as AudioManager.OnAudioFocusChangeListener
    }
}
