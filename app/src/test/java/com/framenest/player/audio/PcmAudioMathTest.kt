package com.framenest.player.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun toMonoSamples_readsByteBufferSlice_withoutMovingCallerPosition() {
        val pcm = shortLeBytes(99, 1000, 3000, -1000, -3000, 88)
        val buffer = ByteBuffer.wrap(pcm).apply {
            position(2)
            limit(pcm.size - 2)
        }

        val mono = PcmAudioMath.toMonoSamples(buffer, channelCount = 2)

        assertArrayEquals(shortArrayOf(2000, -2000), mono)
        assertEquals(2, buffer.position())
        assertEquals(pcm.size - 2, buffer.limit())
    }

    @Test
    fun floatToMono16_readsLittleEndianStereo_withoutMovingCallerPosition() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(123)
            putFloat(1f)
            putFloat(1f)
            putFloat(0.5f)
            putFloat(-0.5f)
            position(4)
        }

        val mono = PcmAudioMath.floatToMono16(buffer, channelCount = 2)

        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, 0), mono)
        assertEquals(4, buffer.position())
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
        assertSame(src, out)
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
