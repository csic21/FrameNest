package com.framenest.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioMathTest {

    @Test
    fun toMonoSamples_averagesStereo() {
        // Two frames, stereo: L=1000,R=3000 then L=0,R=0 → mono 2000, 0
        val pcm = shortLeBytes(
            1000, 3000,
            0, 0,
        )
        val mono = PcmAudioMath.toMonoSamples(pcm, channelCount = 2)
        assertEquals(2, mono.size)
        assertEquals(2000.toShort(), mono[0])
        assertEquals(0.toShort(), mono[1])
    }

    @Test
    fun resampleMonoTo16k_halvesRateWhenFrom32k() {
        val src = ShortArray(32_000) { (it % 100).toShort() }
        val out = PcmAudioMath.resampleMonoTo16k(src, sourceRateHz = 32_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun resampleMonoTo16k_identityAt16k() {
        val src = shortArrayOf(1, 2, 3, 4)
        val out = PcmAudioMath.resampleMonoTo16k(src, sourceRateHz = 16_000)
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun rmsNormalized_silenceIsZero() {
        assertEquals(0f, PcmAudioMath.rmsNormalized(ShortArray(64)), 1e-6f)
    }

    @Test
    fun rmsNormalized_fullScalePositive() {
        val samples = ShortArray(8) { Short.MAX_VALUE }
        val rms = PcmAudioMath.rmsNormalized(samples)
        assertTrue(rms in 0.99f..1.01f)
    }

    private fun shortLeBytes(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = s.toShort().toInt()
            out[i++] = (v and 0xff).toByte()
            out[i++] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
