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
