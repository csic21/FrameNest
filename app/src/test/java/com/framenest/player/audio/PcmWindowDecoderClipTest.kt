package com.framenest.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
