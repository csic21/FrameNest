package com.framenest.feature.listen_translate

import org.junit.Assert.*
import org.junit.Test

class RollingCaptionRequestPlannerTest {
    private fun h(id: Long, start: Long, end: Long, raw: String, recovery: Boolean = false, eof: Boolean = false) =
        RollingCaptionRequestPlanner.Hypothesis(id, start * 16_000L, end * 16_000L, raw, recovery, eof)
    private fun previews(d: RollingCaptionRequestPlanner.Decision) = d.requests.filter { it.type == RollingCaptionRequestPlanner.Type.REVISABLE_PREVIEW }
    private fun sentences(d: RollingCaptionRequestPlanner.Decision) = d.requests.filter { it.type == RollingCaptionRequestPlanner.Type.STABLE_SENTENCE_CANDIDATE }

    @Test fun sentenceCanPrecedeHandoverAndKeepsCurrentRawFormatting() {
        val p = RollingCaptionRequestPlanner()
        p.observe(h(1, 0, 3, "日本 語、です。次の文"))
        val d = p.observe(h(2, 0, 6, "日本語，です。次の文が続く"))
        assertEquals("日本語，です。", sentences(d).single().text)
        assertEquals(0, previews(d).size)
        assertTrue(sentences(d).single().stableLexicalRightContext >= 1)
        assertFalse(sentences(d).single().finalCoverage)
        assertEquals(12 * 16_000L, d.nextDeadlineSample)
    }
    @Test fun terminalRequiresStableLexicalRightContextAndNoAsciiPeriod() {
        for (text in listOf("文。", "文。 ", "文。！？", "abc. next")) {
            val p = RollingCaptionRequestPlanner(); p.observe(h(1, 0, 3, text))
            assertTrue(sentences(p.observe(h(2, 0, 6, text))).isEmpty())
        }
        val p = RollingCaptionRequestPlanner(); p.observe(h(1, 0, 3, "文。次"))
        assertTrue(sentences(p.observe(h(2, 0, 6, "文。別"))).isEmpty())
    }
    @Test fun deadlineDoesNotResetWhenOriginChangesAndPreviewIsEntireHypothesis() {
        val p = RollingCaptionRequestPlanner()
        p.observe(h(1, 0, 3, "未確認中"))
        assertEquals("未確認中国語", previews(p.observe(h(2, 0, 6, "未確認中国語"))).single().text)
        p.observe(h(3, 0, 9, "未確認中国語が続く"))
        val d = p.observe(h(4, 6, 12, "国語が続く新しい入力"))
        assertEquals("国語が続く新しい入力", previews(d).single().text)
        assertEquals(12 * 16_000L, d.deadlineSample)
        assertNotNull(previews(d).single().supersedes)
        assertTrue(previews(d).single().leftEdgeUnverified)
    }
    @Test fun sameEndRecoveryIsOneCorrectionNotNewLatencyAndEofIsIdempotent() {
        val p = RollingCaptionRequestPlanner()
        val a = p.observe(h(1, 6, 18, "候補の全文"))
        val b = p.observe(h(2, 0, 18, "回復した全文", recovery = true, eof = true))
        val correction = previews(b).single()
        assertTrue(correction.correction); assertFalse(correction.latencySample)
        assertEquals(previews(a).single().id, correction.supersedes)
        assertTrue(p.observe(h(2, 0, 18, "回復した全文", recovery = true, eof = true)).requests.isEmpty())
        assertTrue(p.observe(h(3, 0, 18, "回復した全文", recovery = true, eof = true)).requests.isEmpty())
    }
    @Test fun earlyEofGetsFullPreviewEvenWithoutStableTail() {
        val p = RollingCaptionRequestPlanner()
        val d = p.observe(h(1, 0, 2, "まだ途中", eof = true))
        assertEquals("まだ途中", previews(d).single().text)
        assertEquals("EOF_COMPLETE_HYPOTHESIS", previews(d).single().reason)
        assertFalse(previews(d).single().finalCoverage)
    }
    @Test fun laterLexicalChangeRetractsEarlierSentenceCandidate() {
        val p = RollingCaptionRequestPlanner(); p.observe(h(1, 0, 3, "文。次"))
        val first = sentences(p.observe(h(2, 0, 6, "文。次"))).single()
        val d = p.observe(h(3, 0, 9, "別。次"))
        assertEquals(first.id, d.revisions.single().unitId)
        assertEquals("LATER_HYPOTHESIS_RETRACTED_SENTENCE_PREFIX", d.revisions.single().reason)
    }
    @Test fun formattingChangeReissuesRawWithoutLosingKeyStability() {
        val p = RollingCaptionRequestPlanner(); p.observe(h(1, 0, 3, "文、字。次"))
        val first = sentences(p.observe(h(2, 0, 6, "文、字。次"))).single()
        val d = p.observe(h(3, 0, 9, "文字。次"))
        assertEquals(first.id, d.revisions.single().unitId)
        assertEquals("文字。", sentences(d).single().text)
    }
    @Test fun multipleSentencesRetainLiteralSpacesAndUnconfirmedTail() {
        val p = RollingCaptionRequestPlanner()
        p.observe(h(1, 0, 3, "一。 二！後で"))
        val d = p.observe(h(2, 0, 6, "一。 二！後から"))
        assertEquals(listOf("一。", " 二！"), sentences(d).map { it.text })
        assertEquals(listOf(2, 5), sentences(d).map { it.rawEnd })
        assertTrue(d.requests.all { !it.finalCoverage })
    }
    @Test fun strictEligibilityUsesFirstAnyKindTokenAtOrAfterThreshold() {
        assertEquals(1, RollingCaptionRequestPlanner.eligibilityRawStart("甲、乙", listOf("甲", "、", "乙"), listOf(5f, 6f, 7f), 0, 12 * 16_000L))
        assertEquals(2, RollingCaptionRequestPlanner.eligibilityRawStart("甲、乙", listOf("甲", "、", "乙"), listOf(5f, 5.9999f, 6f), 0, 12 * 16_000L))
        assertEquals(0, RollingCaptionRequestPlanner.eligibilityRawStart("😀乙", listOf("😀", "乙"), listOf(0f, 1f), 6 * 16_000L, 9 * 16_000L))
        assertEquals(3, RollingCaptionRequestPlanner.eligibilityRawStart("甲、乙", listOf("甲", "、", "乙"), listOf(1f, 2f, 3f), 0, 12 * 16_000L))
    }
    @Test fun eligibilityRejectsReconstructionMismatchAndInvalidOnsets() {
        assertThrows(IllegalArgumentException::class.java) { RollingCaptionRequestPlanner.eligibilityRawStart("甲 乙", listOf("甲", "乙"), listOf(0f, 1f), 0, 96_000) }
        assertThrows(IllegalArgumentException::class.java) { RollingCaptionRequestPlanner.eligibilityRawStart("甲", listOf("甲"), listOf(Float.NaN), 0, 96_000) }
    }
    @Test fun boundsAndMonotonicityFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { RollingCaptionRequestPlanner().observe(h(1, 0, 19, "全文")) }
        val p = RollingCaptionRequestPlanner(); p.observe(h(1, 0, 3, "全文"))
        assertThrows(IllegalArgumentException::class.java) { p.observe(h(2, 0, 2, "後退")) }
    }
}
