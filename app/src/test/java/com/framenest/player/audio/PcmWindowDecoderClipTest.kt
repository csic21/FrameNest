package com.framenest.player.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure helpers covered via [PcmAudioMath]; full MediaCodec decode needs device.
 */
class PcmWindowDecoderClipTest {

    @Test
    fun pcmAudioMath_roundtripMonoStereo() {
        val stereo = ByteArray(4)
        // One stereo frame: L=1000 R=3000 → mono 2000
        stereo[0] = (1000 and 0xff).toByte()
        stereo[1] = ((1000 shr 8) and 0xff).toByte()
        stereo[2] = (3000 and 0xff).toByte()
        stereo[3] = ((3000 shr 8) and 0xff).toByte()
        val mono = PcmAudioMath.toMonoSamples(stereo, 2)
        assertEquals(1, mono.size)
        assertEquals(2000.toShort(), mono[0])
    }

    @Test
    fun chooseAudioTrack_usesSelectedOrdinal_andFallsBackSafely() {
        val extractorTracks = listOf(1, 3, 5)
        assertEquals(3, chooseAudioTrack(extractorTracks, preferredAudioTrackOrdinal = 1))
        assertEquals(1, chooseAudioTrack(extractorTracks, preferredAudioTrackOrdinal = 99))
        assertEquals(1, chooseAudioTrack(extractorTracks, preferredAudioTrackOrdinal = null))
        assertNull(chooseAudioTrack(emptyList(), preferredAudioTrackOrdinal = 0))
    }

    @Test
    fun clipPcmByPts_reusesFullyCoveredInput_andCopiesPartialEdges() {
        val samples = ShortArray(1_000) { it.toShort() }

        val full = clipPcmByPts(
            mono = samples,
            ptsUs = 1_000_000L,
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            sourceRateHz = 1_000,
        )
        val partial = clipPcmByPts(
            mono = samples,
            ptsUs = 500_000L,
            startUs = 1_000_000L,
            endUs = 2_000_000L,
            sourceRateHz = 1_000,
        )

        assertSame(samples, full)
        assertEquals(500, partial.size)
        assertEquals(500.toShort(), partial.first())
    }

    @Test
    fun pcmSampleAccumulator_appendsInOrder_andGrowsPastEstimate() {
        val accumulator = PcmSampleAccumulator(initialCapacity = 3)

        accumulator.append(shortArrayOf(1, 2))
        accumulator.append(shortArrayOf(3, 4, 5))

        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), accumulator.toShortArray())
        assertEquals(144_000, expectedPcm16kSamples(9_000L))
        assertEquals(160_000, expectedPcm16kSamples(Long.MAX_VALUE))
    }
}
