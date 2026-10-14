package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenCompletedIntervalsTest {
    private fun span(start: Long, end: Long) = ListenCompletedInterval(start, end)
    private fun cue(start: Long, end: Long, src: String = "原文", tgt: String = "译文", rev: Int = 1) =
        ListenTranslateCue(0L, PlaybackIdentity("server", "share", "video"),
            ListenLanguagePair("ja", "zh"), start, end, src, tgt, rev)

    @Test fun adjacentAdaptiveSpansMergeAndResumeAtExactBoundary() {
        val spans = listOf(span(4_200L, 7_800L), span(0L, 4_200L))
        assertEquals(listOf(span(0L, 7_800L)), ListenCompletedIntervals.union(spans))
        assertEquals(span(7_800L, 12_000L), ListenCompletedIntervals.firstGap(spans, 3_000L, 12_000L))
        assertEquals(4_800L, ListenCompletedIntervals.contiguousAheadMs(spans, 3_000L, 12_000L))
    }

    @Test fun nestedOverlappingAndDuplicateSpansDoNotDoubleCount() {
        val spans = listOf(span(2L, 5L), span(0L, 4L), span(1L, 3L), span(0L, 4L))
        assertEquals(listOf(span(0L, 5L)), ListenCompletedIntervals.union(spans))
        assertEquals(5L, ListenCompletedIntervals.contiguousAheadMs(spans, 0L, 10L))
    }

    @Test fun oneMillisecondGapIsNotHiddenByFutureCoverage() {
        val spans = listOf(span(0L, 4_200L), span(4_201L, 7_800L))
        assertEquals(span(4_200L, 4_201L), ListenCompletedIntervals.firstGap(spans, 0L, 12_000L))
        assertEquals(4_200L, ListenCompletedIntervals.contiguousAheadMs(spans, 0L, 12_000L))
        assertEquals(0L, ListenCompletedIntervals.contiguousAheadMs(spans, 4_200L, 12_000L))
    }

    @Test fun gapStopsAtFutureCompletedBoundary() {
        val spans = listOf(span(6_250L, 9_000L))
        assertEquals(span(3_000L, 6_250L), ListenCompletedIntervals.firstGap(spans, 3_000L, 12_000L))
        assertEquals(0L, ListenCompletedIntervals.contiguousAheadMs(spans, 3_000L, 12_000L))
    }

    @Test fun coveredRangeHasNoGapAndClipsToMediaEnd() {
        val spans = listOf(span(0L, 9_000L))
        assertNull(ListenCompletedIntervals.firstGap(spans, 4_000L, 8_000L))
        assertEquals(4_000L, ListenCompletedIntervals.contiguousAheadMs(spans, 4_000L, 8_000L))
    }

    @Test fun emptyCoverageAndEmptyQueryAreWellDefined() {
        assertEquals(span(1L, 10L), ListenCompletedIntervals.firstGap(emptyList(), 1L, 10L))
        assertNull(ListenCompletedIntervals.firstGap(emptyList(), 10L, 10L))
        assertEquals(0L, ListenCompletedIntervals.contiguousAheadMs(emptyList(), 10L, 10L))
    }

    @Test fun pastAndBeyondHorizonIntervalsDoNotAffectFirstGap() {
        assertEquals(span(5L, 10L), ListenCompletedIntervals.firstGap(
            listOf(span(0L, 5L), span(20L, 30L)), 5L, 10L))
    }

    @Test fun sourceOnlyProgressNeverCompletesBilingualCoverage() {
        val cues = listOf(cue(0L, 4_200L, tgt = ""), cue(4_200L, 7_800L))
        val spans = ListenCompletedIntervals.fromCues(cues)
        assertEquals(listOf(span(4_200L, 7_800L)), spans)
        assertEquals(0L, ListenCompletedIntervals.contiguousAheadMs(spans, 0L, 9_000L))
        assertEquals(listOf(span(0L, 7_800L)), ListenCompletedIntervals.fromCues(cues, requireTranslation = false))
    }

    @Test fun sameLanguageDoesNotImplicitlyRelaxExplicitBilingualRequirement() {
        val source = cue(0L, 4L, tgt = "").copy(languages = ListenLanguagePair("ja", "ja"))
        assertEquals(emptyList<ListenCompletedInterval>(), ListenCompletedIntervals.fromCues(listOf(source)))
    }

    @Test fun onlyConfirmedBlankCoverageIsAccepted() {
        val cues = listOf(cue(0L, 3L, "", "", 0), cue(3L, 6L, "", "", 2),
            cue(6L, 9L, "", "", 1), cue(9L, 12L, "", "", 99))
        assertEquals(listOf(span(6L, 9L)), ListenCompletedIntervals.fromCues(cues))
    }

    @Test fun malformedAndTargetOnlyCuesDoNotCertifyCoverage() {
        val cues = listOf(cue(-1L, 2L), cue(2L, 2L), cue(4L, 3L), cue(5L, 6L, src = ""))
        assertEquals(emptyList<ListenCompletedInterval>(), ListenCompletedIntervals.fromCues(cues))
    }

    @Test fun identicalRepeatedTextInDistinctAudioSpansIsNotDeduplicated() {
        val cues = listOf(cue(0L, 3L), cue(5L, 8L))
        assertEquals(listOf(span(0L, 3L), span(5L, 8L)), ListenCompletedIntervals.fromCues(cues))
    }

    @Test fun largeTimestampsDoNotOverflow() {
        val spans = listOf(span(Long.MAX_VALUE - 10L, Long.MAX_VALUE))
        assertEquals(10L, ListenCompletedIntervals.contiguousAheadMs(spans, Long.MAX_VALUE - 10L, Long.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCompletedInterval() { span(3L, 3L) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReversedQuery() { ListenCompletedIntervals.firstGap(emptyList(), 3L, 2L) }
}
