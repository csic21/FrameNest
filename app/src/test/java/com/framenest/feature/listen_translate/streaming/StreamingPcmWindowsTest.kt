package com.framenest.feature.listen_translate.streaming

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamingPcmWindowsTest {
    @Test fun fixedSizedChunksKeepEverySampleAndAbsoluteReadWindow() = runTest {
        val reads = mutableListOf<Pair<Long, Long>>()
        val chunks = streamingPcmChunks(StreamingSubtitleWindow(1L, 10_000L, 16_250L)) { start, end ->
            reads += start to end
            val offset = ((start - 10_000L) * 16L).toInt()
            ShortArray(((end - start) * 16L).toInt()) { (offset + it).toShort() }
        }.toList()
        assertEquals(listOf(10_000L to 13_000L, 13_000L to 16_000L, 16_000L to 16_250L), reads)
        assertEquals(100_000, chunks.sumOf { it.samples.size })
        var offset = 0L
        chunks.forEach { chunk ->
            assertEquals(offset, chunk.offsetSamples)
            assertTrue(chunk.samples.size <= 3_200)
            chunk.samples.forEachIndexed { index, value -> assertEquals((offset + index).toShort(), value) }
            offset += chunk.samples.size
        }
    }

    @Test fun cancellationDoesNotReadWholeTrancheAhead() = runTest {
        var reads = 0
        val chunks = streamingPcmChunks(StreamingSubtitleWindow(1L, 0L, 30_000L)) { start, end ->
            reads++; ShortArray(((end - start) * 16L).toInt())
        }.take(1).toList()
        assertEquals(1, reads)
        assertEquals(1, chunks.size)
    }

    @Test fun shortReadDoesNotInventSilenceOrSkipAnUnknownGap() = runTest {
        var reads = 0
        val chunks = streamingPcmChunks(StreamingSubtitleWindow(1L, 0L, 6_000L)) { _, _ ->
            reads++; ShortArray(3_201)
        }.toList()
        assertEquals(1, reads)
        assertEquals(3_201, chunks.sumOf { it.samples.size })
        assertEquals(3_200L, chunks.last().offsetSamples)
    }

    @Test fun overlongReaderOutputFailsRatherThanTruncatingTimeline() = runTest {
        assertNotNull(runCatching {
            streamingPcmChunks(StreamingSubtitleWindow(1L, 0L, 100L)) { _, _ -> ShortArray(1_601) }.toList()
        }.exceptionOrNull())
    }
}
