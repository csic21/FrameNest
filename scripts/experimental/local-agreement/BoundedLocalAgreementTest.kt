package com.framenest.experiment.localagreement

import com.framenest.experiment.localagreement.BoundedLocalAgreement.Error
import com.framenest.experiment.localagreement.BoundedLocalAgreement.EvidenceLabel
import com.framenest.experiment.localagreement.BoundedLocalAgreement.Status
import com.framenest.experiment.localagreement.BoundedLocalAgreement.TerminalReason
import org.junit.Assert.*
import org.junit.Test

class BoundedLocalAgreementTest {
    private fun component(generation: Long = 1, origin: Long = 0) = BoundedLocalAgreement().also {
        assertEquals(Status.RESET, it.reset(generation, origin).status)
    }

    private fun push(c: BoundedLocalAgreement, samples: Long, text: String): BoundedLocalAgreement.Result {
        val s = checkNotNull(c.snapshot())
        return c.observe(s.generation, s.originSample, s.originSample + samples, samples, text)
    }

    private fun evidence(r: BoundedLocalAgreement.Result) = checkNotNull(r.evidence)

    private fun rejectedUnchanged(c: BoundedLocalAgreement, error: Error, action: () -> BoundedLocalAgreement.Result) {
        val before = c.snapshot()
        val result = action()
        assertEquals(Status.REJECTED, result.status)
        assertEquals(error, result.error)
        assertEquals(before, result.evidence)
        assertEquals(before, c.snapshot())
    }

    @Test fun firstHypothesisIsEntirelyTentativeAndPreservedExactly() {
        val c = component()
        val raw = "  日本語。\t😀\n"
        val r = push(c, 48_000, raw)
        assertEquals(Status.TENTATIVE, r.status)
        assertEquals("", evidence(r).confirmedPrefix)
        assertEquals(raw, evidence(r).latestHypothesis)
        assertEquals(raw, evidence(r).unconfirmedTail)
        assertEquals(EvidenceLabel.TENTATIVE_ONLY, evidence(r).evidenceLabel)
        assertFalse(evidence(r).finalTranslationOrCoverageAllowed)
    }

    @Test fun japanesePartialWordAgreementIsStableTextNeverFinal() {
        val c = component()
        push(c, 48_000, "握りこ")
        val r = push(c, 96_000, "握りこぶしを上げる")
        assertEquals(Status.STABLE_NOT_FINAL, r.status)
        assertEquals("握りこ", evidence(r).confirmedPrefix)
        assertEquals("ぶしを上げる", evidence(r).unconfirmedTail)
        assertEquals(EvidenceLabel.STABLE_TEXT_NOT_FINAL, evidence(r).evidenceLabel)
        assertFalse(evidence(r).finalTranslationOrCoverageAllowed)
        val eof = evidence(c.finish(1))
        assertEquals("握りこ", eof.confirmedPrefix)
        assertEquals("ぶしを上げる", eof.unconfirmedTail)
        assertFalse(eof.finalTranslationOrCoverageAllowed)
    }

    @Test fun exactCompleteAgreementStillCannotAuthorizeFinalTranslationOrCoverage() {
        val c = component()
        push(c, 1, "今日は晴れです。")
        val e = evidence(push(c, 2, "今日は晴れです。"))
        assertEquals("今日は晴れです。", e.confirmedPrefix)
        assertEquals("", e.unconfirmedTail)
        assertEquals(EvidenceLabel.STABLE_TEXT_NOT_FINAL, e.evidenceLabel)
        assertFalse(e.finalTranslationOrCoverageAllowed)
    }

    @Test fun eachNewPrefixRequiresTheImmediatelyPreviousCompleteDecode() {
        val c = component()
        push(c, 1, "abc")
        val second = evidence(push(c, 2, "ax"))
        assertEquals("a", second.confirmedPrefix)
        val third = evidence(push(c, 3, "abc"))
        assertEquals("a", third.confirmedPrefix) // Nonconsecutive first/third cannot confirm bc.
        val fourth = evidence(push(c, 4, "abcd"))
        assertEquals("abc", fourth.confirmedPrefix)
        assertEquals("d", fourth.unconfirmedTail)
    }

    @Test fun noCommonPrefixLeavesTheEntireLatestHypothesisTentative() {
        val c = component()
        push(c, 1, "京都")
        val r = push(c, 2, "東京")
        assertEquals(Status.TENTATIVE, r.status)
        assertEquals("", evidence(r).confirmedPrefix)
        assertEquals("東京", evidence(r).unconfirmedTail)
        assertFalse(evidence(r).conflictObserved)
    }

    @Test fun differentEmojiSharingAHighSurrogateDoNotConfirmAHalfSurrogate() {
        val c = component()
        push(c, 1, "A😀tail")
        val e = evidence(push(c, 2, "A😁tail"))
        assertEquals("A", e.confirmedPrefix)
        assertEquals("😁tail", e.unconfirmedTail)
        assertEquals(e.latestHypothesis, e.confirmedPrefix + e.unconfirmedTail)
    }

    @Test fun matchingEmojiRemainWholeInTheConfirmedPrefix() {
        val c = component()
        push(c, 1, "😀甲")
        val e = evidence(push(c, 2, "😀乙"))
        assertEquals("😀", e.confirmedPrefix)
        assertEquals(2, e.confirmedPrefix.length)
        assertEquals("乙", e.unconfirmedTail)
    }

    @Test fun punctuationAndWhitespaceAreComparedExactly() {
        val c = component()
        push(c, 1, " A,  B!\n")
        val e = evidence(push(c, 2, " A, B!\n"))
        assertEquals(" A, ", e.confirmedPrefix)
        assertEquals("B!\n", e.unconfirmedTail)
        val other = component()
        push(other, 1, "A, B")
        assertEquals("A", evidence(push(other, 2, "A， B")).confirmedPrefix)
    }

    @Test fun composedAndDecomposedUnicodeAreNotNormalized() {
        val c = component()
        push(c, 1, "é")
        val e = evidence(push(c, 2, "e\u0301"))
        assertEquals("", e.confirmedPrefix)
        assertEquals("e\u0301", e.unconfirmedTail)
    }

    @Test fun confirmedRetractionIsExplicitAndRetainsBothIncompatibleTexts() {
        val c = component()
        push(c, 1, "東京です")
        push(c, 2, "東京ですよ")
        val r = push(c, 3, "京都でした")
        assertEquals(Status.CONFLICT, r.status)
        val e = evidence(r)
        assertEquals("東京です", e.confirmedPrefix)
        assertEquals("京都でした", e.latestHypothesis)
        assertNull(e.unconfirmedTail)
        assertTrue(e.conflictObserved)
        assertTrue(e.terminalReasons.isEmpty())
    }

    @Test fun conflictIsStickyAndStillRetainsTheFourthIncompatibleHypothesis() {
        val c = component()
        push(c, 1, "abc")
        push(c, 2, "abcd")
        push(c, 3, "xyz")
        val r = push(c, 4, "latest complete text")
        assertEquals(Status.TERMINAL, r.status)
        val e = evidence(r)
        assertTrue(e.conflictObserved)
        assertEquals("abc", e.confirmedPrefix)
        assertEquals("latest complete text", e.latestHypothesis)
        assertNull(e.unconfirmedTail)
        assertEquals(4, e.hypothesisCount)
        val eof = evidence(c.finish(1))
        assertEquals(e.latestHypothesis, eof.latestHypothesis)
        assertNull(eof.unconfirmedTail)
        assertTrue(eof.conflictObserved)
    }

    @Test fun returningToConfirmedPrefixAfterConflictDoesNotResumeConfirmation() {
        val c = component()
        push(c, 1, "abc")
        push(c, 2, "abcdef")
        push(c, 3, "ab")
        val e = evidence(push(c, 4, "abcdefghi"))
        assertTrue(e.conflictObserved)
        assertEquals("abc", e.confirmedPrefix)
        assertEquals("defghi", e.unconfirmedTail)
        assertEquals("abcdefghi", e.latestHypothesis)
    }

    @Test fun fourHypothesisBudgetRetainsFullLatestAndUnconfirmedTail() {
        val c = component()
        push(c, 1, "a")
        push(c, 2, "ab")
        push(c, 3, "abc")
        val r = push(c, 4, "abcdTAIL")
        assertEquals(Status.TERMINAL, r.status)
        val e = evidence(r)
        assertEquals(listOf(TerminalReason.HYPOTHESIS_BUDGET), e.terminalReasons)
        assertEquals("abc", e.confirmedPrefix)
        assertEquals("dTAIL", e.unconfirmedTail)
        assertEquals("abcdTAIL", e.latestHypothesis)
        rejectedUnchanged(c, Error.ALREADY_TERMINAL) { push(c, 5, "fifth") }
    }

    @Test fun twelveSecondBudgetDoesNotRequireFourHypothesesAndNeverFinalizesTail() {
        val c = component(origin = 700)
        val r = push(c, 192_000, "one complete raw decode")
        assertEquals(Status.TERMINAL, r.status)
        val e = evidence(r)
        assertEquals(listOf(TerminalReason.SOURCE_BUDGET), e.terminalReasons)
        assertEquals("", e.confirmedPrefix)
        assertEquals(e.latestHypothesis, e.unconfirmedTail)
        assertEquals(192_700L, e.observedEndSample)
        assertEquals(192_000L, e.sampleCount)
    }

    @Test fun simultaneousBudgetsBothRemainInTerminalAndEofReports() {
        val c = component()
        push(c, 48_000, "a")
        push(c, 96_000, "ab")
        push(c, 144_000, "abc")
        val cap = evidence(push(c, 192_000, "abcd"))
        assertEquals(listOf(TerminalReason.HYPOTHESIS_BUDGET, TerminalReason.SOURCE_BUDGET), cap.terminalReasons)
        val eof = evidence(c.finish(1))
        assertEquals(cap.confirmedPrefix, eof.confirmedPrefix)
        assertEquals(cap.latestHypothesis, eof.latestHypothesis)
        assertEquals(cap.unconfirmedTail, eof.unconfirmedTail)
        assertEquals(cap.terminalReasons + TerminalReason.EOF, eof.terminalReasons)
        assertTrue(eof.eofObserved)
        assertEquals(eof, evidence(c.finish(1)))
    }

    @Test fun eofWithoutAnyHypothesesIsExplicitAndIdempotent() {
        val c = component()
        val r = c.finish(1)
        assertEquals(Status.TERMINAL, r.status)
        val e = evidence(r)
        assertEquals(listOf(TerminalReason.EOF_WITHOUT_HYPOTHESES), e.terminalReasons)
        assertEquals(0, e.hypothesisCount)
        assertEquals(0L, e.sampleCount)
        assertNull(e.latestHypothesis)
        assertNull(e.unconfirmedTail)
        assertEquals(EvidenceLabel.NO_HYPOTHESIS, e.evidenceLabel)
        assertEquals(r, c.finish(1))
    }

    @Test fun eofAfterOneDecodeRetainsTheWholeTentativeText() {
        val c = component()
        push(c, 1, "unfinished wor")
        val e = evidence(c.finish(1))
        assertEquals("", e.confirmedPrefix)
        assertEquals("unfinished wor", e.unconfirmedTail)
        assertEquals(listOf(TerminalReason.EOF), e.terminalReasons)
        assertFalse(e.finalTranslationOrCoverageAllowed)
    }

    @Test fun emptyAsrTextIsAValidCompleteHypothesisButNotEvidenceOfFinality() {
        val c = component()
        push(c, 1, "")
        val e = evidence(push(c, 2, ""))
        assertEquals(2, e.hypothesisCount)
        assertEquals("", e.latestHypothesis)
        assertEquals("", e.unconfirmedTail)
        assertEquals(EvidenceLabel.TENTATIVE_ONLY, e.evidenceLabel)
    }

    @Test fun duplicateObservationCannotCountAsAnIndependentHypothesis() {
        val c = component()
        push(c, 48_000, "same")
        rejectedUnchanged(c, Error.DUPLICATE_OBSERVATION) { push(c, 48_000, "same") }
        rejectedUnchanged(c, Error.DUPLICATE_OBSERVATION) { push(c, 48_000, "different") }
        assertEquals("", c.snapshot()!!.confirmedPrefix)
        assertEquals(1, c.snapshot()!!.hypothesisCount)
    }

    @Test fun reversedChangedOriginAndInconsistentSampleCountsAreAtomicRejections() {
        val c = component(origin = 100)
        push(c, 48_000, "first")
        rejectedUnchanged(c, Error.NON_INCREASING_OBSERVATION) { push(c, 47_999, "earlier") }
        rejectedUnchanged(c, Error.ORIGIN_CHANGED) { c.observe(1, 101, 96_101, 96_000, "shift") }
        rejectedUnchanged(c, Error.SAMPLE_COUNT_MISMATCH) { c.observe(1, 100, 96_100, 48_000, "count") }
        rejectedUnchanged(c, Error.SAMPLE_COUNT_MISMATCH) { c.observe(1, 100, 48_100, 96_000, "end") }
    }

    @Test fun zeroNegativeAndOverBudgetSourcesAreRejectedWithoutTruncation() {
        val c = component()
        rejectedUnchanged(c, Error.INVALID_OBSERVATION) { push(c, 0, "zero") }
        rejectedUnchanged(c, Error.INVALID_OBSERVATION) { c.observe(1, 0, -1, -1, "negative") }
        push(c, 48_000, "valid")
        rejectedUnchanged(c, Error.SOURCE_BUDGET_EXCEEDED) { push(c, 192_001, "overshoot") }
        assertEquals(1, c.snapshot()!!.hypothesisCount)
    }

    @Test fun overlongBmpAndSupplementaryInputsAreRejectedAtomically() {
        val c = component()
        push(c, 1, "saved")
        rejectedUnchanged(c, Error.TEXT_TOO_LONG) { push(c, 2, "a".repeat(4097)) }
        rejectedUnchanged(c, Error.TEXT_TOO_LONG) { push(c, 2, "😀".repeat(4097)) }
        assertEquals("saved", c.snapshot()!!.latestHypothesis)
        assertEquals(1, c.snapshot()!!.hypothesisCount)
    }

    @Test fun loneSurrogatesAreRejectedWithoutChangingAcceptedState() {
        val c = component()
        push(c, 1, "saved")
        for (raw in listOf("\uD83D", "\uDE00", "A\uD83DB", "A\uDE00B", "\uD83D\uD83D")) {
            rejectedUnchanged(c, Error.INVALID_UTF16) { push(c, 2, raw) }
        }
    }

    @Test fun maximumSupplementaryTextHasBoundedStateAndNoRetainedHistory() {
        val c = component()
        val max = "😀".repeat(4096)
        push(c, 1, max)
        val second = evidence(push(c, 2, max))
        assertEquals(8192, second.retainedTextCodePoints)
        assertEquals(16384, second.retainedTextCodeUnits)
        assertEquals(max, second.confirmedPrefix)
        assertEquals("", second.unconfirmedTail)
        // Later full strings replace latest; accepted-history text is not stored.
        push(c, 3, "other")
        val fourth = evidence(push(c, 4, "last"))
        assertEquals(4096 + 4, fourth.retainedTextCodePoints)
        assertEquals(8192 + 4, fourth.retainedTextCodeUnits)
        val reset = c.reset(2, 1000)
        assertEquals(0, evidence(reset).retainedTextCodePoints)
        assertEquals(4, reset.resetAudit!!.discardedLatestCodePoints)
        assertEquals(4096, reset.resetAudit!!.discardedConfirmedCodePoints)
    }

    @Test fun exactly4096BmpCodePointsAreAccepted() {
        val c = component()
        val raw = "日".repeat(4096)
        assertEquals(raw, evidence(push(c, 1, raw)).latestHypothesis)
        assertEquals(raw, evidence(push(c, 2, raw)).confirmedPrefix)
    }

    @Test fun resetDiscardsOldTextWithOnlyAnAuditAndRejectsStaleAsyncResults() {
        val c = component(generation = 7, origin = 100)
        push(c, 1, "private old text")
        push(c, 2, "private old text!")
        val reset = c.reset(8, 20_000)
        val e = evidence(reset)
        assertEquals(8L, e.generation)
        assertEquals(20_000L, e.originSample)
        assertEquals(0, e.hypothesisCount)
        assertEquals("", e.confirmedPrefix)
        assertNull(e.latestHypothesis)
        assertNull(e.unconfirmedTail)
        assertEquals(7L, reset.resetAudit!!.discardedGeneration)
        assertEquals(2, reset.resetAudit!!.discardedHypothesisCount)
        assertFalse(reset.resetAudit.toString().contains("private old text"))
        rejectedUnchanged(c, Error.STALE_GENERATION) { c.observe(7, 100, 103, 3, "stale") }
        rejectedUnchanged(c, Error.STALE_GENERATION) { c.finish(7) }
        rejectedUnchanged(c, Error.GENERATION_MISMATCH) { c.observe(9, 20_000, 20_001, 1, "future") }
        rejectedUnchanged(c, Error.GENERATION_MISMATCH) { c.finish(9) }
        assertEquals("new", evidence(push(c, 1, "new")).latestHypothesis)
    }

    @Test fun invalidResetIsAtomicIncludingGenerationOriginAndRate() {
        val c = component(generation = 3)
        push(c, 1, "keep")
        rejectedUnchanged(c, Error.STALE_GENERATION) { c.reset(3, 0) }
        rejectedUnchanged(c, Error.STALE_GENERATION) { c.reset(2, 0) }
        rejectedUnchanged(c, Error.INVALID_GENERATION) { c.reset(-1, 0) }
        rejectedUnchanged(c, Error.INVALID_ORIGIN) { c.reset(4, -1) }
        rejectedUnchanged(c, Error.UNSUPPORTED_SAMPLE_RATE) { c.reset(4, 0, 0) }
        rejectedUnchanged(c, Error.UNSUPPORTED_SAMPLE_RATE) { c.reset(4, 0, 48_000) }
    }

    @Test fun uninitializedInputsAndNegativeGenerationsAreExplicit() {
        val c = BoundedLocalAgreement()
        rejectedUnchanged(c, Error.NOT_INITIALIZED) { c.observe(1, 0, 1, 1, "text") }
        rejectedUnchanged(c, Error.NOT_INITIALIZED) { c.finish(0) }
        rejectedUnchanged(c, Error.INVALID_GENERATION) { c.finish(-1) }
        assertNull(c.snapshot())
    }

    @Test fun absoluteOffsetsNearLongMaxDoNotOverflow() {
        val origin = Long.MAX_VALUE - 192_000
        val c = component(origin = origin)
        val r = c.observe(1, origin, Long.MAX_VALUE, 192_000, "tail")
        assertEquals(Status.TERMINAL, r.status)
        assertEquals(192_000L, evidence(r).sampleCount)
        assertEquals(Long.MAX_VALUE, evidence(r).observedEndSample)
        val impossible = component(origin = Long.MAX_VALUE)
        rejectedUnchanged(impossible, Error.INVALID_OBSERVATION) {
            impossible.observe(1, Long.MAX_VALUE, Long.MIN_VALUE, 1, "overflow")
        }
        val huge = component()
        rejectedUnchanged(huge, Error.SOURCE_BUDGET_EXCEEDED) {
            huge.observe(1, 0, Long.MAX_VALUE, Long.MAX_VALUE, "huge")
        }
    }

    @Test fun maximumGenerationCannotWrapOnReset() {
        val c = component(generation = Long.MAX_VALUE)
        rejectedUnchanged(c, Error.INVALID_GENERATION) { c.reset(Long.MIN_VALUE, 0) }
        rejectedUnchanged(c, Error.STALE_GENERATION) { c.reset(Long.MAX_VALUE, 0) }
        assertEquals(Status.TERMINAL, c.finish(Long.MAX_VALUE).status)
    }

    @Test fun mutatingReturnedReasonListsCannotChangeStoredState() {
        val c = component()
        push(c, 1, "a")
        push(c, 2, "a")
        push(c, 3, "a")
        val e = evidence(push(c, 192_000, "a"))
        (e.terminalReasons as MutableList).clear()
        assertEquals(2, c.snapshot()!!.terminalReasons.size)
    }
}
