package com.framenest.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmWindowTimelineTest {
    @Test
    fun perBufferResamplingLossDoesNotShortenMediaClockOrImplyEof() {
        val timeline = PcmWindowTimeline(0L, 15_000L)
        var sourceFrames = 0
        var resampledFrames = 0
        while (sourceFrames < 661_500) {
            val frames = minOf(1_024, 661_500 - sourceFrames)
            timeline.onOutputBuffer(sourceFrames * 1_000_000L / 44_100L, frames, 44_100)
            sourceFrames += frames
            resampledFrames += frames * 16_000 / 44_100
        }
        // One floor per codec buffer produces fewer samples than the full fifteen-second span.
        assertTrue(resampledFrames < 240_000)
        val result = timeline.result(ShortArray(resampledFrames))
        assertEquals(15_000L, result.decodedThroughMs)
        assertFalse(result.reachedEndOfStream)
    }

    @Test
    fun syntheticWindowEndEosIsNotExtractorEof() {
        val timeline = PcmWindowTimeline(9_000L, 24_000L)
        timeline.onOutputBuffer(9_000_000L, 240_000, 16_000)
        timeline.onOutputEndOfStream()
        assertEquals(24_000L, timeline.result(shortArrayOf(1)).decodedThroughMs)
        assertFalse(timeline.result(shortArrayOf(1)).reachedEndOfStream)
    }

    @Test
    fun realEofRequiresOutputDrainage() {
        val timeline = PcmWindowTimeline(9_000L, 24_000L)
        timeline.onOutputBuffer(9_000_000L, 72_000, 16_000)
        timeline.onExtractorEndOfStream()
        assertFalse(timeline.result(shortArrayOf(1)).reachedEndOfStream)
        timeline.onOutputEndOfStream()
        val result = timeline.result(shortArrayOf(1))
        assertEquals(13_500L, result.decodedThroughMs)
        assertTrue(result.reachedEndOfStream)
    }

    @Test
    fun realEofInInputLookAheadDoesNotDiscardTailClippedFromThisWindow() {
        val timeline = PcmWindowTimeline(0L, 15_000L)
        timeline.onOutputBuffer(14_990_000L, 960, 16_000)
        timeline.onExtractorEndOfStream()
        timeline.onOutputEndOfStream()
        val result = timeline.result(ShortArray(160))
        assertEquals(15_000L, result.decodedThroughMs)
        assertFalse(result.reachedEndOfStream)
    }

    @Test
    fun fractionalMillisecondTailRoundsUpWithoutRepeatedOneMillisecondDecode() {
        val timeline = PcmWindowTimeline(9_000L, 24_000L)
        timeline.onOutputBuffer(13_499_000L, 44, 44_100)
        timeline.onExtractorEndOfStream()
        timeline.onOutputEndOfStream()
        assertEquals(13_500L, timeline.result(shortArrayOf(1)).decodedThroughMs)
        assertTrue(timeline.result(shortArrayOf(1)).reachedEndOfStream)
    }

    @Test
    fun emptyTrueEofHasKnownTimeline() {
        val timeline = PcmWindowTimeline(15_000L, 30_000L)
        timeline.onExtractorEndOfStream()
        timeline.onOutputEndOfStream()
        val result = timeline.result(ShortArray(0))
        assertEquals(15_000L, result.decodedThroughMs)
        assertTrue(result.reachedEndOfStream)
    }

    @Test(expected = IllegalStateException::class)
    fun legacyApiStillRejectsEmptyTrueEof() {
        PcmWindowResult(ShortArray(0), 15_000L, true).requirePcmSamples()
    }
}
