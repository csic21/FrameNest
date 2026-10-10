package com.framenest.feature.listen_translate.asr

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConservativeSpeechGateTest {
    @Test
    fun digitalSilence_skipsTheWholeWindow() {
        val decision = ConservativeSpeechGate.evaluate(ShortArray(72_000))

        assertFalse(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.NearDigitalSilence, decision.reason)
        assertEquals(225, decision.frameCount)
        assertEquals(0, decision.signalFrameCount)
    }

    @Test
    fun oneLsbQuantizationNoise_isNearDigitalSilence() {
        val decision = ConservativeSpeechGate.evaluate(ShortArray(16_001) { (it % 3 - 1).toShort() })

        assertFalse(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.NearDigitalSilence, decision.reason)
        assertEquals(51, decision.frameCount)
        assertEquals(0, decision.signalFrameCount)
    }

    @Test
    fun quietSpeechLikeSignal_belowLegacyRmsThreshold_isRecognized() {
        // Synthetic varying-envelope voiced harmonics, not a semantic VAD fixture.
        val pcm = ShortArray(16_000) { index ->
            val time = index / 16_000.0
            val envelope = 0.3 + 0.7 * sin(PI * time) * sin(PI * time)
            (100 * envelope * (sin(2 * PI * 180 * time) + 0.3 * sin(2 * PI * 360 * time))).toInt().toShort()
        }
        assertTrue(normalizedRms(pcm) < 0.008)

        assertSignal(pcm)
    }

    @Test
    fun backgroundMusicAndAmbiguousNoise_areRecognized() {
        val music = ShortArray(16_000) { index ->
            val time = index / 16_000.0
            (80 * sin(2 * PI * 220 * time) + 50 * sin(2 * PI * 330 * time)).toInt().toShort()
        }
        val noise = ShortArray(16_000) { index -> ((index * 37 % 101) - 50).toShort() }

        assertSignal(music)
        assertSignal(noise)
    }

    @Test
    fun shortSparseSpeech_dilutedOverFourAndAHalfSeconds_isRecognized() {
        val pcm = ShortArray(72_000)
        // Only 10 ms of a quiet onset straddles a frame boundary.
        for (index in 24_240 until 24_400) {
            pcm[index] = (120 * sin(2 * PI * 200 * index / 16_000.0)).toInt().toShort()
        }
        assertTrue(normalizedRms(pcm) < 0.008)

        val decision = ConservativeSpeechGate.evaluate(pcm)

        assertTrue(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.SignalPresent, decision.reason)
        assertEquals(225, decision.frameCount)
        assertEquals(2, decision.signalFrameCount)
    }

    @Test
    fun aSingleTwoLsbSample_atEitherWindowEdge_isRecognized() {
        for (position in listOf(0, 71_999, 72_000)) {
            for (amplitude in listOf(-2, 2)) {
                val pcm = ShortArray(72_001)
                pcm[position] = amplitude.toShort()

                val decision = ConservativeSpeechGate.evaluate(pcm)

                assertTrue(decision.shouldRecognize)
                assertEquals(226, decision.frameCount)
                assertEquals(1, decision.signalFrameCount)
            }
        }
    }

    @Test
    fun fullRangePcm_includingMinimumShort_isRecognized() {
        for (amplitude in listOf(Short.MIN_VALUE, Short.MAX_VALUE)) {
            val pcm = ShortArray(960)
            pcm[0] = amplitude

            assertSignal(pcm)
        }
    }

    @Test
    fun emptyInput_failsOpenWithNoInspectedFrames() {
        val decision = ConservativeSpeechGate.evaluate(shortArrayOf())

        assertTrue(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.EmptyInput, decision.reason)
        assertEquals(0, decision.frameCount)
        assertEquals(0, decision.signalFrameCount)
    }

    @Test
    fun shortInput_failsOpenEvenIfSilent() {
        for (length in listOf(1, 319, 320, 959)) {
            val decision = ConservativeSpeechGate.evaluate(ShortArray(length))

            assertTrue(decision.shouldRecognize)
            assertEquals(ConservativeSpeechGate.Reason.InsufficientContext, decision.reason)
            assertEquals(0, decision.frameCount)
            assertEquals(0, decision.signalFrameCount)
        }
    }

    @Test
    fun minimumLengthSilentInput_canBeSkipped() {
        val decision = ConservativeSpeechGate.evaluate(ShortArray(960))

        assertFalse(decision.shouldRecognize)
        assertEquals(3, decision.frameCount)
    }

    @Test
    fun unsupportedOrInvalidFormats_failOpen() {
        for (rate in listOf(-1, 0, 8_000, 44_100, 48_000)) {
            assertUnsupported(ConservativeSpeechGate.evaluate(ShortArray(16_000), sampleRateHz = rate))
        }
        for (channels in listOf(-1, 0, 2, 6)) {
            assertUnsupported(ConservativeSpeechGate.evaluate(ShortArray(16_000), channelCount = channels))
        }
    }

    @Test
    fun evaluation_neverChangesOrCompactsAnySamples() {
        val pcm = ShortArray(72_001) { index ->
            when (index) {
                0 -> Short.MIN_VALUE
                24_001 -> 42
                72_000 -> Short.MAX_VALUE
                else -> 0
            }
        }
        val original = pcm.copyOf()

        ConservativeSpeechGate.evaluate(pcm)

        assertArrayEquals(original, pcm)
        val silent = ShortArray(72_001) { (it % 3 - 1).toShort() }
        val originalSilence = silent.copyOf()
        ConservativeSpeechGate.evaluate(silent)
        assertArrayEquals(originalSilence, silent)
    }

    @Test
    fun windows_areIndependentAcrossCallOrderAndReevaluation() {
        val silence = ShortArray(16_000)
        val signal = ShortArray(16_000).apply { this[lastIndex] = 2 }
        val beforeSignal = ConservativeSpeechGate.evaluate(silence)

        assertSignal(signal)
        assertEquals(beforeSignal, ConservativeSpeechGate.evaluate(silence))
        assertEquals(ConservativeSpeechGate.evaluate(signal), ConservativeSpeechGate.evaluate(signal))
    }

    private fun assertSignal(pcm: ShortArray) {
        val decision = ConservativeSpeechGate.evaluate(pcm)
        assertTrue(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.SignalPresent, decision.reason)
        assertTrue(decision.signalFrameCount > 0)
    }

    private fun assertUnsupported(decision: ConservativeSpeechGate.Decision) {
        assertTrue(decision.shouldRecognize)
        assertEquals(ConservativeSpeechGate.Reason.UnsupportedFormat, decision.reason)
        assertEquals(0, decision.frameCount)
        assertEquals(0, decision.signalFrameCount)
    }

    private fun normalizedRms(pcm: ShortArray): Double =
        sqrt(pcm.sumOf { it.toDouble() * it.toDouble() } / pcm.size) / 32768.0
}
