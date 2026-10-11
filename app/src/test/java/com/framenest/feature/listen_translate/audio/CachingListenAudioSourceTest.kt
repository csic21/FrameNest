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
        source.pcmWindow(5_250L, 9_750L, preferredAudioTrackOrdinal = 0)
        assertEquals(listOf(0L to 3_750L, 2_250L to 12_750L), delegate.bounds)
        source.pcmWindow(2_250L, 6_750L, preferredAudioTrackOrdinal = 1)
        assertEquals(2_250L to 6_750L, delegate.bounds.last())
        source.pcmWindow(60_000L, 64_500L, preferredAudioTrackOrdinal = 1)
        assertEquals(60_000L to 64_500L, delegate.bounds.last())

        assertEquals(4, delegate.decodeCount)
        assertEquals(3_750 * 16, first.size)
        assertEquals(4_500 * 16, second.size)
        assertTrue(first.all { it == 1.toShort() })
    }

    private class FakeAudioSource : ListenAudioSource {
        var decodeCount = 0
        val bounds = mutableListOf<Pair<Long, Long>>()

        override suspend fun pcmWindow(
            startMs: Long,
            endMs: Long,
            preferredAudioTrackOrdinal: Int?,
        ): ShortArray {
            decodeCount += 1
            bounds += startMs to endMs
            val samples = ((endMs - startMs) * PcmAudioMath.TARGET_SAMPLE_RATE_HZ / 1_000L)
                .toInt()
            return ShortArray(samples) { decodeCount.toShort() }
        }
    }
}
