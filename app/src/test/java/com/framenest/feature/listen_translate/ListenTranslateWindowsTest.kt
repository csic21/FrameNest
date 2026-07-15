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
            cue(0, 3_000, "", "", rev = 0),
            cue(0, 1_800, "hello", "你好"),
        )
        assertFalse(ListenTranslateWindows.needsFill(cues, 0, 3_000))
        assertEquals("hello", ListenTranslateWindows.cueAt(cues, 1_000L)?.textSrc)
        assertEquals("", ListenTranslateWindows.cueAt(cues, 2_500L)?.textSrc)
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
