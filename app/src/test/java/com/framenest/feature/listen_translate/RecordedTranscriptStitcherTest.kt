package com.framenest.feature.listen_translate

import org.junit.Assert.*
import org.junit.Test

class RecordedTranscriptStitcherTest {
    private val phrase = "昨日の事件について詳しく説明します"
    private fun letters(text: String, start: Long) = text.codePoints().toArray().mapIndexed { i, cp ->
        RecordedToken(String(Character.toChars(cp)), start + i * 120L)
    }
    private fun first(text: String = phrase, generation: Long = 0L) = RecordedHypothesis(generation, 0L, 15_000L,
        listOf(RecordedToken("導入🙂。", 1_000L)) + letters(text, 9_000L))
    private fun second(text: String = phrase, generation: Long = 0L) = RecordedHypothesis(generation, 9_000L, 24_000L,
        letters(text, 9_000L) + listOf(RecordedToken("次の話です。", 17_000L)))

    @Test fun pendingIsNotFinalAndAlignedTailReconstructsTextWithoutRepeat() {
        val s = RecordedTranscriptStitcher()
        assertNull(s.push(first()).frozenText)
        val r = s.push(second())
        assertNull(r.conflict)
        assertEquals("導入🙂。" + phrase + "次の話です。", r.frozenText!!.text + r.pending!!.text)
        assertEquals(12_000L, r.frozenText.coarseLabelEndMs)
        assertEquals(12_000L, r.pending.coarseLabelStartMs)
        assertNotNull(r.audit)
    }

    @Test fun firstCharacterSubstitutionDoesNotCauseExactPrefixStall() {
        val s = RecordedTranscriptStitcher()
        s.push(first())
        val r = s.push(second("昨".replace("昨", "一") + phrase.drop(1)))
        assertNull(r.conflict)
        assertNotNull(r.frozenText)
        assertTrue(r.audit!!.matchedCodePoints >= 8)
    }

    @Test fun oneInternalEditCanUseUniqueLaterAnchors() {
        val s = RecordedTranscriptStitcher()
        s.push(first())
        val r = s.push(second(phrase.replace("詳", "細")))
        assertNull(r.conflict)
        assertNotNull(r.frozenText)
    }

    @Test fun repeatedPhraseWithAmbiguousOccurrenceIsRejected() {
        val text = "同じ話をします"
        val a = RecordedHypothesis(0L, 0L, 15_000L, letters(text + text, 9_000L))
        val b = RecordedHypothesis(0L, 9_000L, 24_000L, letters(text, 9_000L))
        val s = RecordedTranscriptStitcher(); s.push(a)
        val r = s.push(b)
        assertNotNull(r.conflict)
        assertNull(r.frozenText)
    }

    @Test fun laterIdenticalTextOutsideAudioOverlapIsNotDeduplicated() {
        val a = RecordedHypothesis(0L, 0L, 15_000L, letters(phrase, 1_000L))
        val s = RecordedTranscriptStitcher(); s.push(a)
        val r = s.push(second())
        assertNotNull(r.conflict)
        assertNull(r.frozenText)
    }

    @Test fun timingConstraintRejectsWrongOccurrenceInsideBroadTextSearch() {
        val a = RecordedHypothesis(0L, 0L, 15_000L, letters(phrase, 8_000L))
        val b = RecordedHypothesis(0L, 9_000L, 24_000L, letters(phrase, 12_000L))
        val s = RecordedTranscriptStitcher(); s.push(a)
        assertNotNull(s.push(b).conflict)
    }

    @Test fun unicodeRawOffsetsNeverSplitSurrogatesAndKeepOriginalPunctuation() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val r = s.push(second())
        val audit = r.audit!!
        assertEquals(first().text.substring(audit.oldRetainedStart, audit.oldRetainedEnd), r.frozenText!!.text)
        assertEquals(second().text.substring(audit.newPendingStart), r.pending!!.text)
        assertTrue(r.frozenText.text.contains("🙂。"))
        assertFalse(r.pending.text.first().isLowSurrogate())
    }

    @Test fun compatibilityNormalizationMapsBackToUnmodifiedRawText() {
        val aText = "ＡＢＣＤＥＦＧＨＩＪＫＬ"
        val bText = "ABCDEFGHIJKL"
        val a = RecordedHypothesis(0L, 0L, 15_000L, letters(aText, 9_000L))
        val b = RecordedHypothesis(0L, 9_000L, 24_000L, letters(bText, 9_000L))
        val s = RecordedTranscriptStitcher(); s.push(a)
        val r = s.push(b)
        assertNull(r.conflict)
        assertTrue(r.frozenText!!.text.first() == 'Ａ')
        assertEquals(a.text.substring(0, r.audit!!.oldRetainedEnd), r.frozenText.text)
    }

    @Test fun maximumAlignmentSideIsStrictlyBounded() {
        val text = "a".repeat(513)
        val a = RecordedHypothesis(0L, 0L, 15_000L, listOf(RecordedToken(text, 9_000L)))
        val b = RecordedHypothesis(0L, 9_000L, 24_000L, listOf(RecordedToken(text, 9_000L)))
        val s = RecordedTranscriptStitcher(); s.push(a)
        assertEquals("alignment_bound_exceeded", s.push(b).conflict)
    }

    @Test fun conflictEmitsExpansionOnceAndDoesNotCertifyCoverageOrFlush() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val r = s.push(second("完全に違う内容しかありません"))
        assertNotNull(r.expansion)
        assertEquals(0L, r.expansion!!.startMs)
        assertEquals(24_000L, r.expansion.endMs)
        assertNull(r.frozenText)
        assertNull(s.push(second()).expansion)
        assertNull(s.finish(0L).frozenText)
        assertFalse(s.finish(0L).eof)
    }

    @Test fun resetAbandonsOnlyPendingAndRejectsLateGeneration() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val reset = s.reset(1L)
        assertEquals(first().text, reset.abandonedPending!!.text)
        assertEquals("stale_generation", s.push(second()).conflict)
        assertNotNull(s.push(first(generation = 1L)).pending)
        assertEquals("stale_generation", s.finish(0L).conflict)
    }

    @Test fun eofFlushIsExplicitAndIdempotent() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val first = s.finish(0L)
        assertTrue(first.eof)
        assertEquals(first().text, first.frozenText!!.text)
        assertNull(s.finish(0L).frozenText)
        assertEquals("already_ended", s.push(second()).conflict)
    }

    @Test fun externalUnionReplacementDoesNotPretendToHaveRunModel() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        s.push(second("別の候補で完全に不一致です"))
        val expanded = RecordedHypothesis(0L, 0L, 24_000L, first().tokens + listOf(RecordedToken("追加", 20_000L)))
        val r = s.replacePendingWithExpansion(expanded)
        assertNull(r.frozenText)
        assertNotNull(r.abandonedPending)
        assertEquals(expanded.text, r.pending!!.text)
    }

    @Test fun nonOverlappingAudioCannotEstablishTextSplice() {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val next = RecordedHypothesis(0L, 15_000L, 30_000L, letters(phrase, 16_000L))
        val r = s.push(next)
        assertEquals("non_overlapping_or_non_advancing_window", r.conflict)
        assertNull(r.expansion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedNormalWindow() {
        RecordedTranscriptStitcher().push(RecordedHypothesis(0L, 0L, 15_001L, listOf(RecordedToken("text", 0L))))
    }
    @Test fun identicalRepeatedWholeRunsRemainAmbiguousRatherThanBeingDeleted() {
        val text = "同じ話をします同じ話をします"
        val s = RecordedTranscriptStitcher()
        s.push(RecordedHypothesis(0L, 0L, 15_000L, letters(text, 9_000L)))
        val r = s.push(RecordedHypothesis(0L, 9_000L, 24_000L, letters(text, 9_000L)))
        assertNotNull(r.conflict)
        assertNull(r.frozenText)
    }

    @Test fun auditCarriesBoundedOriginalCharacterCorrespondences() {
        val s = RecordedTranscriptStitcher(); val a = first(); val b = second()
        s.push(a); val r = s.push(b); val audit = r.audit!!
        assertEquals(audit.matchedCodePoints, audit.matchedOffsets.size)
        assertTrue(audit.matchedOffsets.size <= 512)
        audit.matchedOffsets.forEach {
            assertEquals(a.text.substring(it.oldStart, it.oldEnd), b.text.substring(it.newStart, it.newEnd))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfOrderTokenTimes() {
        RecordedTranscriptStitcher().push(RecordedHypothesis(0L, 0L, 15_000L,
            listOf(RecordedToken("前", 2L), RecordedToken("後", 1L))))
    }

    private fun laterConflict(): Pair<RecordedTranscriptStitcher, RecordedStitchResult> {
        val s = RecordedTranscriptStitcher(); s.push(first())
        val frozen = s.push(second())
        val conflict = s.push(RecordedHypothesis(0L, 18_000L, 33_000L,
            listOf(RecordedToken("この候補とは整合しません", 19_000L))))
        assertNotNull(conflict.expansion)
        return s to frozen
    }

    @Test fun laterUnionRecoveryAnchorsActualFrozenSuffixAndDoesNotRewriteIt() {
        val (s, previous) = laterConflict()
        val expanded = RecordedHypothesis(0L, 9_000L, 33_000L,
            letters(phrase, 9_000L) + listOf(RecordedToken("修正された続き", 25_000L)))
        val r = s.replacePendingWithExpansion(expanded)
        assertNull(r.conflict)
        assertNull(r.frozenText)
        assertNotNull(r.recoveryAudit)
        assertEquals("導入🙂。" + phrase + "修正された続き", previous.frozenText!!.text + r.pending!!.text)
        assertTrue(r.recoveryAudit!!.matchedCodePoints >= 8)
        assertEquals(previous.pending!!.coarseLabelStartMs, r.pending.coarseLabelStartMs)
    }

    @Test fun noFrozenSuffixAnchorKeepsPendingAndDoesNotFlush() {
        val (s, previous) = laterConflict()
        val r = s.replacePendingWithExpansion(RecordedHypothesis(0L, 9_000L, 33_000L,
            letters("全く別の文字列しか存在しません", 9_000L)))
        assertEquals("frozen_prefix_anchor_not_found", r.conflict)
        assertEquals(previous.pending, r.pending)
        assertNull(s.finish(0L).frozenText)
    }

    @Test fun repeatedFrozenSuffixAnchorCannotChooseAnOccurrence() {
        val (s, _) = laterConflict()
        val expanded = RecordedHypothesis(0L, 9_000L, 33_000L,
            letters(phrase, 9_000L) + letters(phrase, 20_000L))
        assertEquals("ambiguous_frozen_prefix_anchor", s.replacePendingWithExpansion(expanded).conflict)
    }

    @Test fun recoveryCannotUseIdenticalSuffixAtInconsistentTime() {
        val (s, _) = laterConflict()
        val expanded = RecordedHypothesis(0L, 9_000L, 33_000L, letters(phrase, 25_000L))
        assertEquals("frozen_prefix_anchor_not_found", s.replacePendingWithExpansion(expanded).conflict)
    }

    @Test fun staleUnionCannotRecoverNewGeneration() {
        val (s, _) = laterConflict(); s.reset(1L)
        assertEquals("stale_generation", s.replacePendingWithExpansion(
            RecordedHypothesis(0L, 9_000L, 33_000L, letters(phrase, 9_000L))).conflict)
    }

    @Test fun recoveryPreservesFrozenRawPunctuationInsteadOfSilentlyNormalizingItAway() {
        val original = RecordedHypothesis(0L, 0L, 15_000L, letters("abcdefghij。", 9_000L))
        val anchor = RecordedFrozenAnchor(original, 0, original.text.length)
        val changed = RecordedHypothesis(0L, 9_000L, 33_000L,
            letters("abcdefghij、next", 9_000L))
        assertEquals("frozen_anchor_trailing_raw_disagrees", RecordedOverlapAlignment.recover(anchor, changed).error)
    }

    @Test fun recoveryNeedsTextAfterAnchorAndDoesNotGuessFinalBoundary() {
        val original = RecordedHypothesis(0L, 0L, 15_000L, letters("abcdefghij", 9_000L))
        val anchor = RecordedFrozenAnchor(original, 0, original.text.length)
        val expanded = RecordedHypothesis(0L, 9_000L, 33_000L, letters("abcdefghij", 9_000L))
        assertEquals("frozen_anchor_has_no_right_context", RecordedOverlapAlignment.recover(anchor, expanded).error)
    }

}
