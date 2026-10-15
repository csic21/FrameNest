package com.framenest.feature.listen_translate.audio

import com.framenest.player.audio.PcmAudioMath
import com.framenest.player.audio.PcmWindowResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun sampleOnlyProvidersKeepUnknownTimelineAndCannotInventEof() = runBlocking {
        val source = CachingListenAudioSource(FakeAudioSource())
        val result = source.pcmWindowDetailed(0L, 3_000L)
        assertNull(result.decodedThroughMs)
        assertFalse(result.reachedEndOfStream)
    }

    @Test
    fun resamplingLossDoesNotTriggerAnotherDecodeOrShortenKnownCoverage() = runBlocking {
        val delegate = DetailedAudioSource(sourceEndMs = 60_000L, lostSamplesPerWindow = 300)
        val source = CachingListenAudioSource(delegate)
        val first = source.pcmWindowDetailed(0L, 15_000L)
        val repeated = source.pcmWindowDetailed(0L, 15_000L)
        assertEquals(1, delegate.bounds.size)
        assertEquals(239_700, first.samples.size)
        assertEquals(first.samples.size, repeated.samples.size)
        assertEquals(15_000L, repeated.decodedThroughMs)
        assertFalse(repeated.reachedEndOfStream)
    }

    @Test
    fun readAheadEofIsHiddenUntilRequestedSliceReachesActualTail() = runBlocking {
        val delegate = DetailedAudioSource(sourceEndMs = 10_000L)
        val source = CachingListenAudioSource(delegate)
        source.pcmWindowDetailed(0L, 3_750L)
        val second = source.pcmWindowDetailed(2_250L, 6_750L)
        assertEquals(6_750L, second.decodedThroughMs)
        assertFalse(second.reachedEndOfStream)
        val third = source.pcmWindowDetailed(5_250L, 9_750L)
        assertFalse(third.reachedEndOfStream)
        val tail = source.pcmWindowDetailed(8_250L, 12_750L)
        assertEquals(10_000L, tail.decodedThroughMs)
        assertEquals(1_750 * 16, tail.samples.size)
        assertTrue(tail.reachedEndOfStream)
        val emptyTail = source.pcmWindowDetailed(10_000L, 25_000L)
        assertTrue(emptyTail.samples.isEmpty())
        assertEquals(10_000L, emptyTail.decodedThroughMs)
        assertTrue(emptyTail.reachedEndOfStream)
        assertEquals(listOf(0L to 3_750L, 2_250L to 12_750L), delegate.bounds)
    }

    @Test
    fun emptyEofCanBeCachedButTrackChangeInvalidatesIt() = runBlocking {
        val delegate = DetailedAudioSource(sourceEndMs = 0L)
        val source = CachingListenAudioSource(delegate)
        assertTrue(source.pcmWindowDetailed(0L, 15_000L, 0).reachedEndOfStream)
        assertTrue(source.pcmWindowDetailed(0L, 15_000L, 0).reachedEndOfStream)
        assertEquals(1, delegate.bounds.size)
        source.pcmWindowDetailed(0L, 15_000L, 1)
        assertEquals(2, delegate.bounds.size)
    }

    @Test
    fun cancellationDoesNotReplacePreviouslyDecodedCache() = runBlocking {
        val delegate = DetailedAudioSource(sourceEndMs = 60_000L)
        val source = CachingListenAudioSource(delegate)
        source.pcmWindowDetailed(0L, 15_000L)
        delegate.cancelNext = true
        try {
            source.pcmWindowDetailed(30_000L, 45_000L)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Keep the last successfully decoded window usable.
        }
        assertEquals(15_000L, source.pcmWindowDetailed(0L, 15_000L).decodedThroughMs)
        assertEquals(2, delegate.bounds.size)
    }

    private class DetailedAudioSource(
        private val sourceEndMs: Long,
        private val lostSamplesPerWindow: Int = 0,
    ) : ListenAudioSource {
        val bounds = mutableListOf<Pair<Long, Long>>()
        var cancelNext = false

        override suspend fun pcmWindow(
            startMs: Long,
            endMs: Long,
            preferredAudioTrackOrdinal: Int?,
        ): ShortArray = error("Cache must preserve detailed metadata")

        override suspend fun pcmWindowDetailed(
            startMs: Long,
            endMs: Long,
            preferredAudioTrackOrdinal: Int?,
        ): PcmWindowResult {
            bounds += startMs to endMs
            if (cancelNext) {
                cancelNext = false
                throw CancellationException("Cancelled decode")
            }
            val throughMs = minOf(endMs, sourceEndMs)
            val sampleCount = ((throughMs - startMs) * 16 - lostSamplesPerWindow)
                .coerceAtLeast(0L).toInt()
            return PcmWindowResult(ShortArray(sampleCount), throughMs, endMs >= sourceEndMs)
        }
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
