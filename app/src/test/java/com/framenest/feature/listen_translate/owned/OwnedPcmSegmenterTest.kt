package com.framenest.feature.listen_translate.owned

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class OwnedPcmSegmenterTest {
    private val rate = OwnedPcmSegmenter.SAMPLE_RATE_HZ
    private val max = OwnedPcmSegmenter.MAX_OWNED_SAMPLES
    private val capacity = OwnedPcmSegmenter.MAX_PENDING_SAMPLES

    @Test fun reconstructsEveryIdenticalSampleIncludingForcedSuffixAndShortEof() {
        val input = ShortArray(max * 3 + 137) { 10_000 }
        val spans = collect(input, intArrayOf(1, 319, 7_333, 21, 87_999))
        assertEquals(listOf(OwnedPcmCutReason.FORCED_LIMIT, OwnedPcmCutReason.FORCED_LIMIT,
            OwnedPcmCutReason.FORCED_LIMIT, OwnedPcmCutReason.EOF), spans.map { it.reason })
        assertEquals(137, spans.last().sampleCount)
        assertOwnership(input, spans)
    }

    @Test fun reconstructsDistinctSignedSamplesBitForBitAcrossRingWraps() {
        val input = ShortArray(max * 5 + 193) { ((it * 7919) xor (it ushr 4)).toShort() }
        val spans = collect(input, intArrayOf(13, 321, 42_797, 2, 65_536))
        assertOwnership(input, spans)
    }

    @Test fun allChunkPartitionsProduceIdenticalCutsReasonsReadinessEvidenceAndPcm() {
        val input = mixedPcm()
        val expected = collect(input, intArrayOf(input.size))
        val partitions = listOf(intArrayOf(1), intArrayOf(319), intArrayOf(320),
            intArrayOf(321), intArrayOf(capacity), intArrayOf(2, 311, 65_537, 1, 19)) +
            (1L..20L).map { seed -> Random(seed).let { random -> IntArray(31) { 1 + random.nextInt(40_000) } } }
        for (partition in partitions) {
            val actual = collect(input, partition)
            assertEquals(expected.map(::metadata), actual.map(::metadata))
            assertOwnership(input, actual)
        }
    }

    @Test fun quietCutUsesMeasuredMidpointAndPreservesPaddingOnBothSides() {
        val input = ShortArray(rate * 4) { 10_000 }
        input.fill(0, rate * 2, rate * 2 + 5_120)
        val spans = collect(input, intArrayOf(13, 4096, 999))
        val first = spans.first()
        assertEquals(OwnedPcmCutReason.QUIET_WINDOW, first.reason)
        assertEquals(34_560L, first.endSampleExclusive)
        assertEquals(37_120L, first.decisionSampleExclusive)
        assertEquals(OwnedPcmQuietWindow(32_000, 37_120), first.quietWindow)
        assertTrue((first.sampleCount - 2_560 until first.sampleCount).all { first.sampleAt(it) == 0.toShort() })
        assertTrue((0 until 2_560).all { spans[1].sampleAt(it) == 0.toShort() })
        assertOwnership(input, spans)
    }

    @Test fun longSilenceIsOwnedRatherThanDroppedAndUsesEarliestLegalWindow() {
        val input = ShortArray(rate * 40 + 71)
        val spans = collect(input, intArrayOf(22_231, 1, 320))
        assertEquals(OwnedPcmQuietWindow(29_440, 34_560), spans.first().quietWindow)
        assertEquals(32_000L, spans.first().endSampleExclusive)
        assertEquals(34_560L, spans.first().decisionSampleExclusive)
        assertTrue(spans.dropLast(1).all { it.sampleCount == 32_000 && it.reason == OwnedPcmCutReason.QUIET_WINDOW })
        assertOwnership(input, spans)
    }

    @Test fun quietWindowSlidesForwardOnlyEnoughToMeetMinimumOwnedSpan() {
        val input = ShortArray(rate * 3) { 10_000 }
        input.fill(0, 28_800, 35_200) // 1.80..2.20 s; the earliest legal midpoint is 2.00 s.
        val first = collect(input, intArrayOf(127)).first()
        assertEquals(32_000L, first.endSampleExclusive)
        assertEquals(34_560L, first.decisionSampleExclusive)
        assertEquals(OwnedPcmQuietWindow(29_440, 34_560), first.quietWindow)
    }

    @Test fun belowMinimumQuietBoundaryDoesNotProduceTinySpan() {
        val input = ShortArray(rate * 3) { 10_000 }
        input.fill(0, 4_800, 12_800)
        val spans = collect(input, intArrayOf(719))
        assertEquals(1, spans.size)
        assertEquals(OwnedPcmCutReason.EOF, spans.single().reason)
        assertOwnership(input, spans)
    }

    @Test fun shortQuietRunsSeparatedBySpeechDoNotAccumulate() {
        val input = ShortArray(rate * 3) { 10_000 }
        input.fill(0, 32_000, 36_800) // 300 ms
        input.fill(0, 37_120, 41_920) // Another 300 ms after one speech frame.
        assertEquals(OwnedPcmCutReason.EOF, collect(input, intArrayOf(1)).single().reason)
    }

    @Test fun twentyMillisecondRmsFramesAreNotSplitByInputReads() {
        val input = ShortArray(rate * 3) { 0 }
        // Every fixed frame contains an above-threshold impulse; tiny read tails must not be quiet frames.
        for (i in input.indices step 320) input[i] = Short.MAX_VALUE
        val spans = collect(input, intArrayOf(319, 1))
        assertEquals(OwnedPcmCutReason.EOF, spans.single().reason)
    }

    @Test fun quietThresholdMatchesExistingMinus35DbHeuristic() {
        assertEquals(OwnedPcmCutReason.QUIET_WINDOW,
            collect(ShortArray(rate * 3) { 582 }, intArrayOf(7)).first().reason)
        assertEquals(OwnedPcmCutReason.EOF,
            collect(ShortArray(rate * 3) { 583 }, intArrayOf(7)).single().reason)
        assertEquals(OwnedPcmCutReason.QUIET_WINDOW,
            collect(ShortArray(rate * 3) { -582 }, intArrayOf(111)).first().reason)
    }

    @Test fun continuousSpeechWaitsForExactLookaheadThenForcesTwelveSeconds() {
        val segmenter = OwnedPcmSegmenter()
        val input = ShortArray(capacity + 701) { 10_000 }
        val before = segmenter.offer(0, 0, input, length = capacity - 1)
        assertNull(before.span)
        assertEquals(capacity - 1, segmenter.pendingSampleCount)
        val at = segmenter.offer(0, segmenter.nextSample, input, capacity - 1, 1)
        assertEquals(1, at.consumedSamples)
        assertEquals(OwnedPcmCutReason.FORCED_LIMIT, at.span!!.reason)
        assertEquals(max.toLong(), at.span.endSampleExclusive)
        assertEquals(capacity.toLong(), at.span.decisionSampleExclusive)
        assertNull(at.span.quietWindow)
        assertEquals(5_120, segmenter.pendingSampleCount)
        assertEquals(max.toLong(), segmenter.pendingStartSample)
        val suffix = segmenter.offer(0, segmenter.nextSample, input, capacity, 701)
        assertEquals(701, suffix.consumedSamples)
        assertOwnership(input, listOf(at.span, segmenter.finish(0, segmenter.nextSample)!!))
    }

    @Test fun quietAcrossTwelveSecondCapUsesLookaheadWithoutChunkDependentForce() {
        val input = ShortArray(rate * 14) { 10_000 }
        input.fill(0, max - 2_560, max + 2_560)
        val spans = collect(input, intArrayOf(max, 1, 319))
        val first = spans.first()
        assertEquals(OwnedPcmCutReason.QUIET_WINDOW, first.reason)
        assertEquals(max.toLong(), first.endSampleExclusive)
        assertEquals((max + 2_560).toLong(), first.decisionSampleExclusive)
        assertEquals(OwnedPcmQuietWindow(max - 2_560L, max + 2_560L), first.quietWindow)
        assertOwnership(input, spans)
    }

    @Test fun lateQuietCannotExtendTheMaximumOwnedSpan() {
        val input = ShortArray(rate * 14) { 10_000 }
        input.fill(0, max - 2_240, max + 5_120) // Earliest quiet midpoint would exceed 12 s.
        val spans = collect(input, intArrayOf(input.size))
        assertEquals(OwnedPcmCutReason.FORCED_LIMIT, spans.first().reason)
        assertEquals(max, spans.first().sampleCount)
        assertOwnership(input, spans)
    }

    @Test fun eofFlushesSubminimumAndPartialFrameSamplesExactly() {
        for (size in listOf(1, 319, 320, 321, 31_999, 32_000, max)) {
            val input = ShortArray(size) { 10_000 }
            val spans = collect(input, intArrayOf(13))
            assertEquals(OwnedPcmCutReason.EOF, spans.single().reason)
            assertEquals(size, spans.single().sampleCount)
            assertOwnership(input, spans)
        }
    }

    @Test fun eofDuringLookaheadDrainsBoundedForcedSpanThenExactRemainder() {
        for (size in listOf(max + 1, max + 319, capacity - 1)) {
            val input = ShortArray(size) { 10_000 }
            val spans = collect(input, intArrayOf(input.size))
            assertEquals(listOf(OwnedPcmCutReason.FORCED_LIMIT, OwnedPcmCutReason.EOF), spans.map { it.reason })
            assertEquals(max, spans.first().sampleCount)
            assertEquals(size - max, spans.last().sampleCount)
            assertEquals(size.toLong(), spans.first().decisionSampleExclusive)
            assertOwnership(input, spans)
        }
    }

    @Test fun emptyAndRepeatedEofNeverCreateCoverageOrEmptySpans() {
        val segmenter = OwnedPcmSegmenter(4, 17)
        assertNull(segmenter.offer(4, 17, shortArrayOf()).span)
        assertFalse(segmenter.inputFinished)
        assertNull(segmenter.finish(4, 17))
        assertTrue(segmenter.inputFinished)
        assertNull(segmenter.finish(4, 17))
        expectIllegalState { segmenter.offer(4, 17, shortArrayOf(3)) }
    }

    @Test fun eofDoesNotClassifyAnUnfinishedQuietFrame() {
        val input = ShortArray(37_119) { 10_000 }
        input.fill(0, 32_000, input.size) // One sample short of a proven 320 ms quiet window.
        val spans = collect(input, intArrayOf(11))
        assertEquals(OwnedPcmCutReason.EOF, spans.single().reason)
        assertOwnership(input, spans)
    }

    @Test fun exactNonzeroSampleCursorAndGenerationArePreservedWithoutMillisecondRounding() {
        val input = mixedPcm()
        val start = 1_815_377L
        val spans = collect(input, intArrayOf(3, 277, 25_117), generation = 91, start = start)
        assertTrue(spans.all { it.generation == 91L })
        assertEquals(start, spans.first().startSample)
        assertOwnership(input, spans, start)
        val zero = collect(input, intArrayOf(input.size))
        assertEquals(zero.map { it.endSampleExclusive }, spans.map { it.endSampleExclusive - start })
    }

    @Test fun resetReportsDiscardedRangeSeparatelyAndRestartsFrameAlignment() {
        val segmenter = OwnedPcmSegmenter(7, 5_003)
        segmenter.offer(7, 5_003, ShortArray(123) { Short.MAX_VALUE })
        val discard = segmenter.reset(8, 99_999)
        assertEquals(DiscardedOwnedPcmOnReset(7, 5_003, 5_126), discard)
        assertEquals(123L, discard!!.sampleCount)
        assertEquals(0, segmenter.pendingSampleCount)
        assertEquals(99_999L, segmenter.nextSample)
        assertEquals(99_999L, segmenter.pendingStartSample)
        val offered = segmenter.offer(8, 99_999, ShortArray(34_560))
        assertEquals(OwnedPcmCutReason.QUIET_WINDOW, offered.span!!.reason)
        assertEquals(8L, offered.span.generation)
        assertEquals(131_999L, offered.span.endSampleExclusive)
        assertEquals(134_559L, offered.span.decisionSampleExclusive)
        assertEquals(OwnedPcmQuietWindow(129_439, 134_559), offered.span.quietWindow)
        assertEquals(2_560, segmenter.pendingSampleCount)
        assertEquals(DiscardedOwnedPcmOnReset(8, 131_999, 134_559), segmenter.reset(9, 0))
        assertNull(segmenter.reset(10, 0))
    }

    @Test fun resetAfterEofReopensOnlyForStrictlyNewGeneration() {
        val segmenter = OwnedPcmSegmenter(3)
        segmenter.offer(3, 0, shortArrayOf(10))
        assertNotNull(segmenter.finish(3, 1))
        assertNull(segmenter.reset(4, 100))
        assertFalse(segmenter.inputFinished)
        assertEquals(1, segmenter.offer(4, 100, shortArrayOf(11)).consumedSamples)
        expectIllegalArgument { segmenter.offer(3, 101, shortArrayOf(12)) }
        expectIllegalArgument { segmenter.finish(3, 101) }
        expectIllegalArgument { segmenter.reset(4, 0) }
        expectIllegalArgument { segmenter.reset(2, 0) }
        assertEquals(101L, segmenter.nextSample)
        assertEquals(1, segmenter.pendingSampleCount)
    }

    @Test fun malformedInputsGapsOverlapsAndStaleGenerationsAreAtomic() {
        val segmenter = OwnedPcmSegmenter(5, 77)
        segmenter.offer(5, 77, shortArrayOf(1, 2, 3))
        val input = shortArrayOf(4, 5)
        val invalid = listOf<() -> Unit>(
            { segmenter.offer(4, 80, input) }, { segmenter.offer(6, 80, input) },
            { segmenter.offer(5, 79, input) }, { segmenter.offer(5, 81, input) },
            { segmenter.offer(5, 80, input, -1, 1) }, { segmenter.offer(5, 80, input, 0, -1) },
            { segmenter.offer(5, 80, input, 3, 0) }, { segmenter.offer(5, 80, input, 1, 2) },
            { segmenter.offer(5, 80, input, Int.MAX_VALUE, Int.MAX_VALUE) },
            { segmenter.finish(4, 80) }, { segmenter.finish(5, 81) },
            { segmenter.reset(6, -1) }, { segmenter.reset(5, 0) },
        )
        for (action in invalid) {
            expectIllegalArgument(action)
            assertEquals(80L, segmenter.nextSample)
            assertEquals(77L, segmenter.pendingStartSample)
            assertEquals(3, segmenter.pendingSampleCount)
            assertEquals(5L, segmenter.generation)
            assertFalse(segmenter.inputFinished)
        }
        assertArrayEquals(shortArrayOf(1, 2, 3), segmenter.finish(5, 80)!!.copyPcm())
    }

    @Test fun sampleAndGenerationOverflowNeverWrapOrPartiallyConsume() {
        expectIllegalArgument { OwnedPcmSegmenter(-1, 0) }
        expectIllegalArgument { OwnedPcmSegmenter(0, -1) }
        val segmenter = OwnedPcmSegmenter(Long.MAX_VALUE, Long.MAX_VALUE - 2)
        expectIllegalArgument { segmenter.offer(Long.MAX_VALUE, Long.MAX_VALUE - 2, shortArrayOf(1, 2, 3)) }
        assertEquals(0, segmenter.pendingSampleCount)
        assertEquals(2, segmenter.offer(Long.MAX_VALUE, Long.MAX_VALUE - 2, shortArrayOf(1, 2)).consumedSamples)
        assertEquals(Long.MAX_VALUE, segmenter.nextSample)
        expectIllegalArgument { segmenter.offer(Long.MAX_VALUE, Long.MAX_VALUE, shortArrayOf(3)) }
        assertArrayEquals(shortArrayOf(1, 2), segmenter.finish(Long.MAX_VALUE, Long.MAX_VALUE)!!.copyPcm())
        expectIllegalArgument { segmenter.reset(Long.MIN_VALUE, 0) }
        expectIllegalArgument { segmenter.reset(Long.MAX_VALUE, 0) }
    }

    @Test fun hugeInputIsPartiallyConsumedWithAtMostOneBoundedOutputAndFixedStorage() {
        val segmenter = OwnedPcmSegmenter()
        val input = ShortArray(capacity * 20 + 17) { 10_000 }
        var offset = 0
        var emitted = 0L
        var first = true
        while (offset < input.size) {
            val result = segmenter.offer(0, offset.toLong(), input, offset, input.size - offset)
            if (first) {
                assertEquals(capacity, result.consumedSamples)
                assertNotNull(result.span)
                first = false
            }
            assertTrue(result.consumedSamples > 0)
            offset += result.consumedSamples
            assertEquals(capacity, segmenter.bufferCapacitySamples)
            assertTrue(segmenter.pendingSampleCount in 0..capacity)
            result.span?.let { assertTrue(it.sampleCount <= max); emitted += it.sampleCount }
            assertEquals(offset.toLong(), emitted + segmenter.pendingSampleCount)
        }
        while (true) {
            val span = segmenter.finish(0, offset.toLong()) ?: break
            assertTrue(span.sampleCount <= max)
            emitted += span.sampleCount
        }
        assertEquals(input.size.toLong(), emitted)
        assertEquals(0, segmenter.pendingSampleCount)
    }

    @Test fun spanFactoryCannotRetainAMutableCallerArrayAlias() {
        val source = shortArrayOf(7, 8, 9, 10)
        val span = OwnedPcmSpan.copyFromRing(2, 99, 3, OwnedPcmCutReason.EOF, 102, null, source, 3)
        source.fill(0)
        assertArrayEquals(shortArrayOf(10, 7, 8), span.copyPcm())
    }

    @Test fun emittedPcmIsDetachedFromInputOutputCopiesRingReuseAndReset() {
        val segmenter = OwnedPcmSegmenter()
        val input = ShortArray(capacity) { 12_345 }
        val span = segmenter.offer(0, 0, input).span!!
        input.fill(-23_456)
        val copy = span.copyPcm()
        copy.fill(0)
        segmenter.reset(1, 0)
        segmenter.offer(1, 0, input)
        assertTrue((0 until span.sampleCount).all { span.sampleAt(it) == 12_345.toShort() })
        val destination = ShortArray(span.sampleCount + 2) { 9 }
        span.copyPcmInto(destination, 1)
        assertEquals(9.toShort(), destination.first())
        assertEquals(9.toShort(), destination.last())
        assertEquals(12_345.toShort(), destination[1])
        expectIllegalArgument { span.copyPcmInto(ShortArray(1)) }
        expectIllegalArgument { span.copyPcmInto(destination, -1) }
        expectIllegalArgument { span.copyPcmInto(destination, 3) }
    }

    @Test fun suppliedSliceAndUnconsumedSuffixRemainCallerOwnedUntilOffered() {
        val input = ShortArray(capacity + 104) { 1_111 }
        val segmenter = OwnedPcmSegmenter(2, 51)
        val first = segmenter.offer(2, 51, input, 7, input.size - 14)
        assertEquals(capacity, first.consumedSamples)
        val suffixOffset = 7 + first.consumedSamples
        input.fill(2_222, suffixOffset, input.size - 7)
        segmenter.offer(2, segmenter.nextSample, input, suffixOffset, input.size - 7 - suffixOffset)
        val last = segmenter.finish(2, segmenter.nextSample)!!
        assertEquals(1_111.toShort(), last.sampleAt(5_119))
        assertEquals(2_222.toShort(), last.sampleAt(5_120))
        assertEquals(5_210, last.sampleCount)
        assertEquals(51L + input.size - 14, last.endSampleExclusive)
    }

    private fun mixedPcm(): ShortArray = ShortArray(rate * 48 + 137) { ((it * 7919) xor (it ushr 4)).toShort() }.apply {
        fill(0, 32_000, 38_400)
        fill(0, rate * 8, rate * 12)
        fill(0, rate * 32, rate * 33)
        fill(0, size - 71, size)
    }

    private fun metadata(span: OwnedPcmSpan): List<Any?> = listOf(span.generation, span.startSample,
        span.endSampleExclusive, span.reason, span.decisionSampleExclusive, span.quietWindow)

    private fun collect(input: ShortArray, chunks: IntArray, generation: Long = 0, start: Long = 0): List<OwnedPcmSpan> {
        val segmenter = OwnedPcmSegmenter(generation, start)
        val spans = mutableListOf<OwnedPcmSpan>()
        var offset = 0
        var chunkIndex = 0
        while (offset < input.size) {
            val chunkEnd = minOf(input.size, offset + chunks[chunkIndex++ % chunks.size])
            while (offset < chunkEnd) {
                val offered = segmenter.offer(generation, start + offset, input, offset, chunkEnd - offset)
                assertTrue(offered.consumedSamples > 0)
                offset += offered.consumedSamples
                offered.span?.let(spans::add)
                assertEquals(start + offset, segmenter.nextSample)
                assertEquals(capacity, segmenter.bufferCapacitySamples)
                assertTrue(segmenter.pendingSampleCount <= capacity)
            }
        }
        while (true) spans += segmenter.finish(generation, start + input.size) ?: break
        assertEquals(0, segmenter.pendingSampleCount)
        assertNull(segmenter.finish(generation, start + input.size))
        return spans
    }

    private fun assertOwnership(input: ShortArray, spans: List<OwnedPcmSpan>, start: Long = 0) {
        val reconstructed = ShortArray(input.size)
        var cursor = start
        var index = 0
        for (span in spans) {
            assertEquals(cursor, span.startSample)
            assertEquals(span.sampleCount.toLong(), span.endSampleExclusive - span.startSample)
            assertTrue(span.sampleCount in 1..max)
            assertTrue(span.decisionSampleExclusive >= span.endSampleExclusive)
            span.copyPcmInto(reconstructed, index)
            cursor = span.endSampleExclusive
            index += span.sampleCount
        }
        assertEquals(start + input.size, cursor)
        assertArrayEquals(input, reconstructed)
    }

    private fun expectIllegalArgument(action: () -> Unit) {
        try { action(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }

    private fun expectIllegalState(action: () -> Unit) {
        try { action(); fail("Expected IllegalStateException") } catch (_: IllegalStateException) { }
    }
}
