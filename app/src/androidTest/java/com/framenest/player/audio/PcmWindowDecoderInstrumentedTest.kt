package com.framenest.player.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmWindowDecoderInstrumentedTest {

    @Test
    fun bundledSample_decodesTo16kMonoPcm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pcm = PcmWindowDecoder().decodeRaw(
            context = context,
            resId = R.raw.sample_h264,
            startMs = 0L,
            endMs = 3_000L,
            preferredAudioTrackOrdinal = 0,
        )

        assertTrue("expected decoded PCM", pcm.isNotEmpty())
        assertTrue("expected no more than requested window", pcm.size <= 3_000 * 16 + 1_000)
    }
}
