package com.framenest.feature.listen_translate.audio

import com.framenest.player.audio.PcmAudioMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingListenAudioSourceTest {

    @Test
    fun sequentialWindowsReuseReadAhead_andTrackChangeInvalidatesCache() = runBlocking {
        val delegate = FakeAudioSource()
        val source = CachingListenAudioSource(delegate, readAheadMs = 6_000L)

        val first = source.pcmWindow(0L, 3_750L, preferredAudioTrackOrdinal = 0)
        val second = source.pcmWindow(2_250L, 6_750L, preferredAudioTrackOrdinal = 0)
        source.pcmWindow(2_250L, 6_750L, preferredAudioTrackOrdinal = 1)

        assertEquals(2, delegate.decodeCount)
        assertEquals(3_750 * 16, first.size)
        assertEquals(4_500 * 16, second.size)
        assertTrue(first.all { it == 1.toShort() })
    }

    private class FakeAudioSource : ListenAudioSource {
        var decodeCount = 0

        override suspend fun pcmWindow(
            startMs: Long,
            endMs: Long,
            preferredAudioTrackOrdinal: Int?,
        ): ShortArray {
            decodeCount += 1
            val samples = ((endMs - startMs) * PcmAudioMath.TARGET_SAMPLE_RATE_HZ / 1_000L)
                .toInt()
            return ShortArray(samples) { decodeCount.toShort() }
        }
    }
}
