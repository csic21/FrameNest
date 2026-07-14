package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCandidatePolicyTest {

    @Test
    fun preferred_usesTenSecondsForLongVideos() {
        assertEquals(10_000L, ThumbnailCandidatePolicy.preferredTimestampMs(120_000L))
        assertEquals(10_000L, ThumbnailCandidatePolicy.preferredTimestampMs(15_000L))
    }

    @Test
    fun preferred_usesTwentyPercentForShortVideos() {
        // 10s video → 2s
        assertEquals(2_000L, ThumbnailCandidatePolicy.preferredTimestampMs(10_000L))
        // 5s → 1s
        assertEquals(1_000L, ThumbnailCandidatePolicy.preferredTimestampMs(5_000L))
    }

    @Test
    fun candidates_areFiniteAndCapped() {
        val candidates = ThumbnailCandidatePolicy.candidateTimestampsMs(3_600_000L)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.size <= ThumbnailCandidatePolicy.MAX_CANDIDATES)
        assertEquals(10_000L, candidates.first())
    }

    @Test
    fun candidates_shortVideo_startsNearTwentyPercent() {
        val candidates = ThumbnailCandidatePolicy.candidateTimestampsMs(8_000L)
        assertEquals(1_600L, candidates.first())
        assertTrue(candidates.all { it in 0L until 8_000L })
    }

    @Test
    fun candidates_negativeDuration_empty() {
        assertTrue(ThumbnailCandidatePolicy.candidateTimestampsMs(-1L).isEmpty())
    }

    @Test
    fun candidates_unknownDuration_singlePreferred() {
        assertEquals(listOf(10_000L), ThumbnailCandidatePolicy.candidateTimestampsMs(0L))
    }

    @Test
    fun candidates_neverExceedDuration() {
        val duration = 3_000L
        val candidates = ThumbnailCandidatePolicy.candidateTimestampsMs(duration)
        assertTrue(candidates.all { it < duration })
        assertTrue(candidates.size <= ThumbnailCandidatePolicy.MAX_CANDIDATES)
    }
}
