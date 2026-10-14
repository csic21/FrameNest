package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPrefetchPolicyTest {
    private val identity = PlaybackIdentity("server", "media", "movie.mkv")
    private val languages = ListenLanguagePair("en", "zh")

    @Test
    fun lookAhead_adaptsToPipelineSpeedAndPlaybackPressure() {
        assertEquals(12_000L, ListenPrefetchPolicy.lookAheadMs(true, false, null))
        assertEquals(30_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.5))
        assertEquals(12_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.8))
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(true, false, 1.2))
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(true, true, 0.5))
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(false, false, 0.5))
    }

    @Test
    fun realtimeFactor_usesStableExponentialAverage() {
        val first = ListenPrefetchPolicy.updateRealtimeFactor(null, 1_500L, 3_000L)
        assertEquals(0.5, first, 0.0001)
        val second = ListenPrefetchPolicy.updateRealtimeFactor(first, 3_000L, 3_000L)
        assertEquals(0.625, second, 0.0001)
    }

    @Test
    fun lookAhead_accountsForPlaybackRateAndRejectsInvalidRates() {
        assertEquals(30_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.5, 1f))
        assertEquals(12_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.5, 2f))
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.6, 2f))
        assertEquals(30_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 1.2, 0.5f))
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(30_000L, ListenPrefetchPolicy.lookAheadMs(true, false, 0.5, invalid))
        }
    }

    @Test
    fun adaptationSubtractsOnlyBoundedExposedPreparationWait() {
        assertEquals(4_000L, ListenPrefetchPolicy.adaptationElapsedMs(4_000L, null))
        assertEquals(4_000L, ListenPrefetchPolicy.adaptationElapsedMs(4_000L, -1L))
        assertEquals(3_000L, ListenPrefetchPolicy.adaptationElapsedMs(4_000L, 1_000L))
        assertEquals(0L, ListenPrefetchPolicy.adaptationElapsedMs(4_000L, Long.MAX_VALUE))
        assertEquals(0L, ListenPrefetchPolicy.adaptationElapsedMs(-1L, 1_000L))
    }

    @Test
    fun nextWindow_prioritizesCurrentThenScansPastCachedFuture() {
        assertEquals(
            3_000L to 6_000L,
            ListenPrefetchPolicy.nextWindow(emptyList(), 4_000L, 30_000L, 3_000L, 30_000L),
        )
        val cached = listOf(cue(3_000L, 6_000L), cue(6_000L, 9_000L))
        assertEquals(
            9_000L to 12_000L,
            ListenPrefetchPolicy.nextWindow(cached, 4_000L, 30_000L, 3_000L, 12_000L),
        )
        assertNull(ListenPrefetchPolicy.nextWindow(cached, 4_000L, 30_000L, 3_000L, 0L))
    }

    @Test
    fun seekAndAudioVariant_areStableAndSafe() {
        assertTrue(ListenPrefetchPolicy.isFarSeek(1_000L, 9_000L, 3_000L))
        assertFalse(ListenPrefetchPolicy.isFarSeek(1_000L, 3_000L, 3_000L))
        assertEquals("s10_m20|audio=1", ListenCacheVariant.contentKey("s10_m20", 1))
        assertEquals("audio=0", ListenCacheVariant.contentKey("", null))
    }

    @Test
    fun preparedAheadStopsAtGapIncompleteTranslationAndMediaEnd() {
        val future = listOf(cue(0L, 3_000L), cue(6_000L, 9_000L))
        assertEquals(2_000L, ListenPrefetchPolicy.contiguousAheadMs(future, 1_000L, 9_000L, 3_000L))
        val incomplete = listOf(cue(0L, 3_000L).copy(textTgt = ""))
        assertEquals(0L, ListenPrefetchPolicy.contiguousAheadMs(incomplete, 0L, 9_000L, 3_000L))
        assertEquals(1_000L, ListenPrefetchPolicy.contiguousAheadMs(listOf(cue(0L, 2_000L)), 1_000L, 2_000L, 3_000L))
        assertEquals(0L, ListenPrefetchPolicy.contiguousAheadMs(future, 9_000L, 9_000L, 3_000L))
    }

    @Test
    fun adaptiveSchedulerMergesAdjacentUnitsAndKeepsTrueTinyGaps() {
        val adjacent = listOf(ListenCompletedInterval(0L, 4_200L), ListenCompletedInterval(4_200L, 7_800L))
        assertEquals(7_800L to 12_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            adjacent, 0L, 60_000L, 3_000L, 12_000L, 12_000L))
        assertEquals(4_800L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(adjacent, 3_000L, 60_000L))
        val tinyGap = listOf(ListenCompletedInterval(0L, 4_200L), ListenCompletedInterval(4_201L, 7_800L))
        assertEquals(4_200L to 4_201L, ListenPrefetchPolicy.nextAdaptiveWindow(
            tinyGap, 0L, 60_000L, 3_000L, 12_000L, 12_000L))
        assertEquals(4_200L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(tinyGap, 0L, 60_000L))
    }

    @Test
    fun adaptiveCurrentGapRespectsFutureCacheAndPausedPlaybackDoesNotPrefetch() {
        val completed = listOf(ListenCompletedInterval(0L, 4_200L), ListenCompletedInterval(6_250L, 9_000L))
        assertNull(ListenPrefetchPolicy.nextAdaptiveWindow(completed, 3_000L, 60_000L, 3_000L, 12_000L, 0L))
        assertEquals(4_200L to 6_250L, ListenPrefetchPolicy.nextAdaptiveWindow(
            completed, 5_000L, 60_000L, 3_000L, 12_000L, 0L))
        // A hole behind the playhead cannot displace the uncovered current interval.
        assertEquals(4_000L to 16_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            listOf(ListenCompletedInterval(3_100L, 4_000L)), 5_000L, 60_000L, 3_000L, 12_000L, 0L))
    }

    @Test
    fun adaptiveBudgetClampsOversizedEnginesAndHonorsSmallerBudget() {
        assertEquals(0L to 12_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 0L, 60_000L, 3_000L, Long.MAX_VALUE, 30_000L))
        assertEquals(0L to 6_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 0L, 60_000L, 3_000L, 6_000L, 30_000L))
        assertEquals(0L to 7_800L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 6_000L, 7_800L, 3_000L, 12_000L, 30_000L))
        assertNull(ListenPrefetchPolicy.nextAdaptiveWindow(emptyList(), 7_800L, 7_800L, 3_000L, 12_000L, 30_000L))
    }

    @Test
    fun adaptiveFutureWorkAndPreparedAheadStayWithinTheirHorizons() {
        val completed = listOf(ListenCompletedInterval(0L, 11_500L))
        assertEquals(11_500L to 12_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            completed, 0L, 60_000L, 3_000L, 12_000L, 12_000L))
        assertEquals(30_000L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(
            listOf(ListenCompletedInterval(0L, 60_000L)), 0L, 60_000L))
        assertEquals(1_000L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(
            listOf(ListenCompletedInterval(0L, 60_000L)), 59_000L, 60_000L))
        assertEquals(0L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(completed, 60_000L, 60_000L))
    }

    @Test
    fun adaptiveUncachedActivationAndSeekUseBoundedSentenceContext() {
        assertEquals(0L to 12_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 3_500L, 60_000L, 3_000L, 12_000L, 0L))
        assertEquals(3_000L to 15_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 12_010L, 60_000L, 3_000L, 12_000L, 0L))
        assertEquals(3_000L to 15_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 14_999L, 60_000L, 3_000L, 12_000L, 0L))
        assertEquals(114_000L to 126_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), 125_000L, 180_000L, 3_000L, 12_000L, 0L))
        assertEquals(117_800L to 129_800L, ListenPrefetchPolicy.nextAdaptiveWindow(
            listOf(ListenCompletedInterval(114_000L, 117_800L)), 125_000L, 180_000L, 3_000L, 12_000L, 0L))
        // A much older cached boundary cannot pull a far seek back into distant work.
        assertEquals(114_000L to 126_000L, ListenPrefetchPolicy.nextAdaptiveWindow(
            listOf(ListenCompletedInterval(0L, 4_200L)), 125_000L, 180_000L, 3_000L, 12_000L, 0L))
    }

    @Test
    fun adaptiveNearLongLimitDoesNotOverflow() {
        val start = Long.MAX_VALUE - 2_000L
        val result = ListenPrefetchPolicy.nextAdaptiveWindow(emptyList(), start, 0L, 3_000L, 12_000L, 30_000L)!!
        assertTrue(result.second > start)
        assertTrue(result.second > result.first)
        assertTrue(result.second - result.first <= 12_000L)
        assertEquals(Long.MAX_VALUE, ListenPrefetchPolicy.nextAdaptiveWindow(
            emptyList(), Long.MAX_VALUE - 1L, 0L, 3_000L, 12_000L, 30_000L)!!.second)
        assertEquals(2_000L, ListenPrefetchPolicy.adaptiveContiguousAheadMs(
            listOf(ListenCompletedInterval(start, Long.MAX_VALUE)), start, 0L))
    }

    private fun cue(startMs: Long, endMs: Long) = ListenTranslateCue(
        id = startMs,
        identity = identity,
        languages = languages,
        startMs = startMs,
        endMs = endMs,
        textSrc = "speech",
        textTgt = "translation",
        rev = 1,
    )
}
