package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTranslateWindowsTest {

    private val identity = PlaybackIdentity("s", "media", "a.mkv")
    private val langs = ListenLanguagePair("ja", "zh")

    @Test
    fun windowContaining_alignsToBucket() {
        val (start, end) = ListenTranslateWindows.windowContaining(7_500L, windowMs = 3_000L)
        assertEquals(6_000L, start)
        assertEquals(9_000L, end)
    }

    @Test
    fun windowContaining_clampsToDuration() {
        val (start, end) = ListenTranslateWindows.windowContaining(
            positionMs = 8_000L,
            windowMs = 3_000L,
            durationMs = 10_000L,
        )
        assertEquals(6_000L, start)
        assertEquals(9_000L, end)
        val last = ListenTranslateWindows.windowContaining(
            positionMs = 9_500L,
            windowMs = 3_000L,
            durationMs = 10_000L,
        )
        assertEquals(9_000L, last.first)
        assertEquals(10_000L, last.second)
    }

    @Test
    fun cueAt_picksCoveringSegment() {
        val cues = listOf(
            cue(0, 3_000, "a", "A"),
            cue(3_000, 6_000, "b", "B"),
        )
        assertEquals("a", ListenTranslateWindows.cueAt(cues, 1_000L)?.textSrc)
        assertEquals("b", ListenTranslateWindows.cueAt(cues, 3_000L)?.textSrc)
        assertNull(ListenTranslateWindows.cueAt(cues, 9_000L))
    }

    @Test
    fun cueAt_prefersLaterStartThenHigherRevision_withoutSortedInput() {
        val cues = listOf(
            cue(1_000, 4_000, "older", "旧", rev = 1),
            cue(0, 5_000, "wide", "宽", rev = 9),
            cue(1_000, 3_000, "newer", "新", rev = 2),
        )

        assertEquals("newer", ListenTranslateWindows.cueAt(cues, 2_000L)?.textSrc)
        assertEquals("older", ListenTranslateWindows.cueAt(cues, 3_000L)?.textSrc)
    }

    @Test
    fun cueCache_replacesSameRange_andKeepsOverlappingCoverageSorted() {
        val speech = cue(500, 2_000, "speech", "语音", rev = 1)
        val coverage = cue(0, 3_000, "", "", rev = ListenCoverageRev.CONFIRMED_SILENCE)
        val old = cue(3_000, 6_000, "old", "旧", rev = 1)
        val replacement = cue(3_000, 6_000, "new", "新", rev = 2)

        val withOverlap = ListenCueCache.upsert(listOf(old), speech)
        val withCoverage = ListenCueCache.upsert(withOverlap, coverage)
        val replaced = ListenCueCache.upsert(withCoverage, replacement)

        assertEquals(listOf(0L, 500L, 3_000L), replaced.map { it.startMs })
        assertEquals(listOf("", "speech", "new"), replaced.map { it.textSrc })
        assertEquals(1, replaced.count { it.startMs == 3_000L && it.endMs == 6_000L })
    }

    @Test
    fun needsFill_falseWhenCovered() {
        val cues = listOf(cue(0, 3_000, "a", "A"))
        assertFalse(ListenTranslateWindows.needsFill(cues, 0, 3_000))
        assertTrue(ListenTranslateWindows.needsFill(cues, 3_000, 6_000))
        assertTrue(
            ListenTranslateWindows.needsFill(
                listOf(cue(0, 3_000, "source only", "")),
                0,
                3_000,
            ),
        )
    }

    @Test
    fun blankCoverage_preventsRefill_butSpeechCueWinsOverlay() {
        val cues = listOf(
            cue(0, 3_000, "", "", rev = 1),
            cue(0, 1_800, "hello", "你好"),
        )
        assertFalse(ListenTranslateWindows.needsFill(cues, 0, 3_000))
        assertEquals("hello", ListenTranslateWindows.cueAt(cues, 1_000L)?.textSrc)
        assertEquals("hello", ListenTranslateWindows.cueAt(cues.reversed(), 1_000L)?.textSrc)
        val published = ListenCueCache.upsert(listOf(cues[1]), cues[0])
        assertEquals("hello", ListenTranslateWindows.cueAt(published, 1_000L)?.textSrc)
        assertEquals("", ListenTranslateWindows.cueAt(cues, 2_500L)?.textSrc)
    }

    @Test
    fun onlyLegacyOrUnrecognizedBlankAtPlayhead_requestsRecovery() {
        val legacy = listOf(cue(0, 3_000, "", "", ListenCoverageRev.LEGACY_BLANK))
        val silence = listOf(cue(0, 3_000, "", "", ListenCoverageRev.CONFIRMED_SILENCE))
        val unrecognized = listOf(
            cue(0, 3_000, "", "", ListenCoverageRev.UNRECOGNIZED_SPEECH),
        )

        assertTrue(ListenTranslateWindows.needsBlankRecoveryAt(legacy, 1_000L, 0L, 3_000L))
        assertFalse(ListenTranslateWindows.needsBlankRecoveryAt(silence, 1_000L, 0L, 3_000L))
        assertTrue(
            ListenTranslateWindows.needsBlankRecoveryAt(
                unrecognized,
                1_000L,
                0L,
                3_000L,
            ),
        )
        assertFalse(
            ListenTranslateWindows.needsBlankRecoveryAt(
                listOf(cue(0, 3_000, "speech", "译文")),
                1_000L,
                0L,
                3_000L,
            ),
        )
    }

    @Test
    fun formatOverlay_modes() {
        val c = cue(0, 1_000, "src", "tgt")
        assertEquals("src", ListenTranslateWindows.formatOverlay(c, ListenDisplayMode.SourceOnly))
        assertEquals("tgt", ListenTranslateWindows.formatOverlay(c, ListenDisplayMode.TargetOnly))
        assertEquals("src\ntgt", ListenTranslateWindows.formatOverlay(c, ListenDisplayMode.Bilingual))
        assertEquals(
            "same",
            ListenTranslateWindows.formatOverlay(cue(0, 1_000, "same", "same"), ListenDisplayMode.Bilingual),
        )
        assertEquals("", ListenTranslateWindows.formatOverlay(null, ListenDisplayMode.Bilingual))
    }

    private fun cue(start: Long, end: Long, src: String, tgt: String, rev: Int = 1) =
        ListenTranslateCue(
            id = start,
            identity = identity,
            languages = langs,
            startMs = start,
            endMs = end,
            textSrc = src,
            textTgt = tgt,
            rev = rev,
        )
}
