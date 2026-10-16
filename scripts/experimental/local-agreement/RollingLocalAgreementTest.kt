package com.framenest.experiment.localagreement

import com.framenest.experiment.localagreement.RollingLocalAgreement.Error
import com.framenest.experiment.localagreement.RollingLocalAgreement.Kind
import com.framenest.experiment.localagreement.RollingLocalAgreement.MatchFailure
import com.framenest.experiment.localagreement.RollingLocalAgreement.Status
import org.junit.Assert.*
import org.junit.Test

class RollingLocalAgreementTest {
    private val stable = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn"
    private val anchor = stable.takeLast(32)
    private fun start(seconds: Long = 40) = RollingLocalAgreement().also { it.reset(1, 0, seconds * 16000) }
    // Default zero is an explicit synthetic eligibility contract, not measured onsets.
    // Real replay must provide its independently verified token/onset cursor per reply.
    private fun feed(c: RollingLocalAgreement, raw: String, eligibility: Int = 0): RollingLocalAgreement.Result {
        val r = checkNotNull(c.currentRequest())
        return c.observe(r.generation, r.id, r.startSample, r.endSample, r.numSamples, raw, eligibility)
    }
    private fun bootstrap(c: RollingLocalAgreement, text: String = stable) {
        feed(c, "initial")
        feed(c, "second")
        feed(c, text + "X")
        feed(c, text + "Y")
    }
    private fun splice(c: RollingLocalAgreement, lead: String = "lead "): RollingLocalAgreement.Result {
        bootstrap(c)
        feed(c, lead + anchor + "first tail")
        return feed(c, lead + anchor + "second tail")
    }
    private fun rejectAtomic(c: RollingLocalAgreement, error: Error, f: () -> RollingLocalAgreement.Result) {
        val snapshot = c.snapshot()
        val request = c.currentRequest()
        val r = f()
        assertEquals(Status.REJECTED, r.status)
        assertEquals(error, r.error)
        assertEquals(snapshot, c.snapshot())
        assertEquals(request, c.currentRequest())
        assertNull(r.prefixDelta)
        assertNull(r.closedBlock)
    }

    @Test fun fixedRequestsBootstrapThenSixSecondRetainedCandidates() {
        val c = start()
        for (seconds in listOf(3L, 6L, 9L, 12L)) {
            assertEquals(Kind.BOOTSTRAP, c.currentRequest()!!.kind)
            assertEquals(0L, c.currentRequest()!!.startSample)
            assertEquals(seconds * 16000, c.currentRequest()!!.endSample)
            feed(c, stable)
        }
        assertEquals(Kind.CANDIDATE, c.currentRequest()!!.kind)
        assertEquals(96000L, c.currentRequest()!!.startSample)
        assertEquals(240000L, c.currentRequest()!!.endSample)
        assertEquals(144000L, c.currentRequest()!!.numSamples)
        feed(c, stable)
        assertEquals(96000L, c.currentRequest()!!.startSample)
        assertEquals(288000L, c.currentRequest()!!.endSample)
        assertEquals(192000L, c.currentRequest()!!.numSamples)
    }

    @Test fun stabilityUsesLastTwoFullKeysAndCanRetract() {
        val c = start()
        feed(c, "abcdefghijklm")
        feed(c, "abcdefghijklmNEXT")
        assertEquals(13, c.snapshot()!!.stableKeyPrefixCodePoints)
        feed(c, "aZ")
        assertEquals(1, c.snapshot()!!.stableKeyPrefixCodePoints)
        feed(c, "bZ")
        assertEquals(0, c.snapshot()!!.stableKeyPrefixCodePoints)
        assertEquals("bZ", c.snapshot()!!.pending!!.fullRaw)
    }

    @Test fun uniqueHandoverEmitsOnlyOldPrefixAndKeepsAnchorInNewFullPending() {
        val c = start()
        val r = splice(c)
        assertEquals(Status.HANDOVER, r.status)
        assertEquals(stable.dropLast(32), r.prefixDelta)
        assertEquals("lead " + anchor + "second tail", r.snapshot!!.pending!!.fullRaw)
        assertEquals(anchor + "second tail", r.snapshot!!.pending!!.text)
        assertEquals(5, r.snapshot!!.pending!!.pendingRawStart)
        val a = r.spliceAudit!!
        assertEquals(anchor, a.anchorKey)
        assertEquals(32, a.anchorLetterDigitCount)
        assertEquals(anchor + "Y", a.oldReplacedTail)
        assertEquals("lead ", a.newOmittedPrefix)
        assertEquals(a.oldDeltaRawEnd, a.oldReplacedTailRawStart)
        assertEquals(192000L, r.request!!.startSample) // 18s - 6s.
        assertEquals(336000L, r.request!!.endSample)
        assertFalse(r.mtUnit)
        assertFalse(r.finalCoverage)
    }

    @Test fun cjkAndCommaSpaceVariantsUseKeyButPreserveBothRawFormsInAudit() {
        val c = start()
        val text = "日本語字幕確認処理映像音声研究実験"
        bootstrap(c, text)
        feed(c, "前、" + text + "後X")
        val r = feed(c, "前 " + text.replace("字幕", "字，幕") + "後Y")
        assertEquals(Status.HANDOVER, r.status)
        assertEquals("前 ", r.spliceAudit!!.newOmittedPrefix)
        assertTrue(r.snapshot!!.pending!!.fullRaw.contains("字，幕"))
        assertEquals(text, r.spliceAudit!!.anchorKey)
    }

    @Test fun chosenLongestAnchorIsNotShortenedToFindAnotherMatch() {
        val c = start()
        bootstrap(c)
        feed(c, anchor.takeLast(16) + "x")
        val r = feed(c, anchor.takeLast(16) + "y")
        assertEquals(Status.RECOVERY_REQUESTED, r.status)
        assertEquals(MatchFailure.CANDIDATE_ANCHOR_ABSENT, r.matchFailure)
        assertNull(r.prefixDelta)
    }

    @Test fun anchorNeedsEightLettersOrDigitsAndCannotUseOnlyPunctuation() {
        for (text in listOf("短い文", "abcdefg" + "?!。".repeat(9), "?!。".repeat(12))) {
            val c = start()
            bootstrap(c, text)
            feed(c, text + "X")
            val r = feed(c, text + "Y")
            assertEquals(MatchFailure.INSUFFICIENT_STABLE_LETTERS, r.matchFailure)
        }
    }

    @Test fun duplicateOldOrCandidateAnchorsFailWithoutApproximateMatching() {
        val oldDuplicate = start()
        bootstrap(oldDuplicate, "a".repeat(64))
        feed(oldDuplicate, "a".repeat(32) + "X")
        assertEquals(MatchFailure.OLD_ANCHOR_NOT_UNIQUE, feed(oldDuplicate, "a".repeat(32) + "Y").matchFailure)
        val candidateDuplicate = start()
        bootstrap(candidateDuplicate)
        feed(candidateDuplicate, anchor + "." + anchor + "X")
        assertEquals(MatchFailure.CANDIDATE_ANCHOR_AMBIGUOUS,
            feed(candidateDuplicate, anchor + "." + anchor + "Y").matchFailure)
    }

    @Test fun anchorMustHaveSamePositionAndBeInsideCandidateLcp() {
        val shifted = start()
        bootstrap(shifted)
        feed(shifted, "a" + anchor)
        assertEquals(MatchFailure.ANCHOR_POSITION_MISMATCH, feed(shifted, "bb" + anchor).matchFailure)
        val unstable = start()
        bootstrap(unstable)
        feed(unstable, "aa" + anchor)
        assertEquals(MatchFailure.ANCHOR_NOT_STABLE, feed(unstable, "bb" + anchor).matchFailure)
    }

    @Test fun firstFailedHandoverRequestsExactlyOneFullRecoveryThenHardRestarts() {
        val c = start()
        bootstrap(c, "short")
        feed(c, "candidate one")
        val failed = feed(c, "candidate two")
        assertEquals(Kind.RECOVERY, failed.request!!.kind)
        assertEquals(0L, failed.request!!.startSample)
        assertEquals(288000L, failed.request!!.endSample)
        val closed = feed(c, "whole recovered source")
        assertEquals("BEST_EFFORT", closed.closedBlock!!.status)
        assertEquals("whole recovered source", closed.closedBlock!!.remainder)
        assertEquals("", closed.recoveryAudit!!.witnessRaw)
        assertTrue(closed.recoveryAudit!!.matched)
        assertEquals(288000L, closed.hardRestartAtSample)
        assertEquals(Kind.BOOTSTRAP, closed.request!!.kind)
        assertEquals(288000L, closed.request!!.startSample)
        assertEquals(336000L, closed.request!!.endSample)
        assertEquals(0, closed.snapshot!!.retainedRawCodePoints)
        assertFalse(closed.closedBlock!!.finalCoverage)
    }

    @Test fun recoveryStripsOnlyMatchedMappedWitnessAndPreservesCallerDelta() {
        val c = start()
        val handover = splice(c)
        val ledger = handover.prefixDelta!!
        feed(c, "missing one")
        feed(c, "missing two")
        assertEquals(96000L, c.currentRequest()!!.startSample)
        assertEquals(384000L, c.currentRequest()!!.endSample)
        val recovery = feed(c, "l e,a d, RECOVERED whole remainder")
        assertEquals("lead ", recovery.recoveryAudit!!.witnessRaw)
        assertEquals("lead", recovery.recoveryAudit!!.witnessKey)
        assertEquals("l e,a d, ", recovery.recoveryAudit!!.recoveryOmittedRaw)
        assertEquals("RECOVERED whole remainder", recovery.closedBlock!!.remainder)
        assertNull(recovery.prefixDelta)
        assertEquals(stable.dropLast(32) + "RECOVERED whole remainder", ledger + recovery.closedBlock!!.remainder)
        assertTrue(recovery.snapshot!!.singleTranscriptAvailable)
    }

    @Test fun witnessLexicalOrQuestionMarkMismatchClosesWithBothAlternativesAndNoDelta() {
        for (lead in listOf("lead ", "lead? ")) {
            val c = start()
            val handover = splice(c, lead)
            feed(c, "missing one")
            feed(c, "missing two")
            val recoveredRaw = if (lead.contains('?')) "lead! full recovery" else "changed full recovery"
            val r = feed(c, recoveredRaw)
            assertEquals("AMBIGUOUS_OWNERSHIP", r.closedBlock!!.status)
            assertEquals("CLOSED_CONFLICT", r.closedBlock!!.reason)
            assertNull(r.closedBlock!!.remainder)
            assertNull(r.prefixDelta)
            assertEquals(handover.snapshot!!.pending, r.closedBlock!!.oldPending)
            assertEquals(recoveredRaw, r.closedBlock!!.recoveryFull!!.fullRaw)
            assertFalse(r.snapshot!!.singleTranscriptAvailable)
            assertEquals(Kind.BOOTSTRAP, r.request!!.kind)
            assertEquals(384000L, r.hardRestartAtSample)
        }
    }

    @Test fun ignoredOnlyNonemptyWitnessCannotEstablishOwnership() {
        val c = start()
        splice(c, " , ")
        feed(c, "missing one")
        feed(c, "missing two")
        val r = feed(c, " , " + anchor + "full recovery")
        assertEquals("EMPTY_KEY_OMITTED_OVERLAP_WITNESS", r.recoveryAudit!!.failure)
        assertEquals("AMBIGUOUS_OWNERSHIP", r.closedBlock!!.status)
        assertNull(r.closedBlock!!.remainder)
    }

    @Test fun zeroAndShortSourceEofNeverInventASecondObservation() {
        val zero = start(0)
        assertNull(zero.currentRequest())
        assertEquals("EOF_EMPTY", zero.snapshot()!!.terminalReason)
        assertEquals(0L, zero.snapshot()!!.acceptedHypothesisCount)
        val c = start(2)
        assertEquals(32000L, c.currentRequest()!!.endSample)
        val r = feed(c, "short raw")
        assertEquals(Status.FINISHED, r.status)
        assertEquals("short raw", r.closedBlock!!.remainder)
        assertNull(r.request)
        assertEquals(1L, r.snapshot!!.acceptedHypothesisCount)
        val eof = c.finish(1)
        assertNull(eof.prefixDelta)
        assertNull(eof.closedBlock)
        assertEquals(eof, c.finish(1))
    }

    @Test fun candidateEofWithOneObservationRecoversAllRemainingPcmOnce() {
        val c = start(13)
        bootstrap(c)
        assertEquals(208000L, c.currentRequest()!!.endSample)
        val r = feed(c, "one clipped candidate")
        assertEquals(MatchFailure.CANDIDATE_EOF_NEEDS_SECOND, r.matchFailure)
        assertEquals(Kind.RECOVERY, r.request!!.kind)
        assertEquals(0L, r.request!!.startSample)
        assertEquals(208000L, r.request!!.endSample)
        val done = feed(c, "full thirteen seconds")
        assertTrue(done.snapshot!!.terminal)
        assertEquals("full thirteen seconds", done.closedBlock!!.remainder)
        assertNull(done.request)
        assertNull(done.hardRestartAtSample)
    }

    @Test fun twoCandidateEofObservationsCanHandoverAndCloseWithoutRepeatedInference() {
        val c = start(17)
        val r = splice(c)
        assertTrue(r.snapshot!!.terminal)
        assertEquals(Status.FINISHED, r.status)
        assertEquals(stable.dropLast(32), r.prefixDelta)
        assertEquals(anchor + "second tail", r.closedBlock!!.remainder)
        assertEquals(272000L, r.closedBlock!!.observedEndSample)
        assertNull(r.request)
        assertEquals(6L, r.snapshot!!.acceptedHypothesisCount)
    }

    @Test fun malformedDuplicateStaleAndWrongSpanRepliesLeaveRequestAndStateUntouched() {
        val c = start()
        val r = c.currentRequest()!!
        rejectAtomic(c, Error.REQUEST_ID_MISMATCH) { c.observe(1, 9, 0, 48000, 48000, "raw", 0) }
        rejectAtomic(c, Error.REQUEST_SPAN_MISMATCH) { c.observe(1, r.id, 1, 48001, 48000, "raw", 0) }
        rejectAtomic(c, Error.REQUEST_SPAN_MISMATCH) { c.observe(1, r.id, 0, 48000, 47999, "raw", 0) }
        rejectAtomic(c, Error.INVALID_SAMPLE_COUNT) { c.observe(1, r.id, 0, 0, 0, "raw", 0) }
        rejectAtomic(c, Error.INVALID_SAMPLE_COUNT) { c.observe(1, r.id, 0, 48000, 384001, "raw", 0) }
        rejectAtomic(c, Error.INVALID_UTF16) { feed(c, "\uD83D") }
        rejectAtomic(c, Error.TEXT_TOO_LONG) { feed(c, "😀".repeat(4097)) }
        rejectAtomic(c, Error.REQUEST_PENDING) { c.finish(1) }
        feed(c, "first")
        rejectAtomic(c, Error.REQUEST_ID_MISMATCH) { c.observe(1, r.id, 0, 48000, 48000, "duplicate", 0) }
        c.reset(2, 100, 100000)
        rejectAtomic(c, Error.STALE_GENERATION) { c.observe(1, r.id, 0, 48000, 48000, "stale", 0) }
        rejectAtomic(c, Error.STALE_GENERATION) { c.finish(1) }
        rejectAtomic(c, Error.GENERATION_MISMATCH) { c.finish(3) }
    }

    @Test fun memoryRetainsAtMostOldAndLatestCandidateAndResetDoesNotPublishOldText() {
        val c = start()
        val max = "😀".repeat(4096)
        repeat(4) { feed(c, max) }
        val candidate = feed(c, max)
        assertEquals(8192, candidate.snapshot!!.retainedRawCodePoints)
        feed(c, max)
        assertEquals(8192, c.snapshot()!!.retainedRawCodePoints)
        val reset = c.reset(2, 0, 48000)
        assertEquals(0, reset.snapshot!!.retainedRawCodePoints)
        assertNull(reset.prefixDelta)
        assertNull(reset.closedBlock)
        assertEquals(8192, reset.resetAudit!!.discardedRetainedRawCodePoints)
        assertFalse(reset.resetAudit.toString().contains("😀"))
    }

    @Test fun longMaxAbsoluteOffsetsAndInvalidResetsDoNotOverflow() {
        val c = RollingLocalAgreement()
        c.reset(1, Long.MAX_VALUE - 16000, Long.MAX_VALUE)
        assertEquals(16000L, c.currentRequest()!!.numSamples)
        rejectAtomic(c, Error.INVALID_SOURCE_RANGE) { c.reset(2, -1, 5) }
        rejectAtomic(c, Error.INVALID_SOURCE_RANGE) { c.reset(2, 10, 5) }
        rejectAtomic(c, Error.STALE_GENERATION) { c.reset(1, 0, 5) }
        rejectAtomic(c, Error.INVALID_GENERATION) { c.reset(-1, 0, 5) }
        assertTrue(feed(c, "end").snapshot!!.terminal)
    }

    @Test fun repeatedRecoveriesRemainBoundedAndCoverTheEntireFixedSource() {
        val c = start(36)
        var closed = 0
        val spans = mutableSetOf<Pair<Long, Long>>()
        while (c.currentRequest() != null) {
            val request = c.currentRequest()!!
            assertTrue(request.numSamples in 1..RollingLocalAgreement.MAX_REQUEST)
            assertTrue(spans.add(request.startSample to request.endSample)) // Never repeat identical PCM.
            val r = feed(c, "short")
            assertTrue(r.snapshot!!.retainedRawCodePoints <= 8192)
            if (r.closedBlock != null) closed++
            assertFalse(r.mtUnit)
            assertFalse(r.finalCoverage)
        }
        assertEquals(2, closed)
        assertEquals(14L, c.snapshot()!!.acceptedHypothesisCount)
        assertEquals(1L, c.snapshot()!!.hardRestartCount)
        assertTrue(spans.contains(0L to 288000L))
        assertTrue(spans.contains(288000L to 576000L))
        assertTrue(c.snapshot()!!.terminal)
    }

    @Test fun fixedCrossRangeAnchorUsesOnlyTheEligibleStableSuffix() {
        val c = start()
        feed(c, "initial")
        feed(c, "second")
        val eligible = stable.length - 17
        feed(c, stable + "X", eligible)
        feed(c, stable + "Y", eligible)
        val retained = stable.takeLast(17)
        feed(c, "lead, " + retained + "tailA")
        val r = feed(c, "lead " + retained + "tailB")
        assertEquals(Status.HANDOVER, r.status)
        assertEquals(stable.take(eligible), r.prefixDelta)
        val a = r.spliceAudit!!
        assertEquals(retained, a.anchorKey)
        assertEquals(17, a.anchorLetterDigitCount)
        assertEquals(eligible, a.oldAnchorKeyStartCodePoint)
        assertEquals(eligible, a.oldRetainedEligibilityRawStart)
        assertEquals(eligible, a.oldRetainedEligibilityKeyStart)
        assertEquals(96000L, a.oldRetainedOriginSample)
        assertEquals(5, a.newPendingRawStart)
        assertEquals(96000L, r.snapshot!!.pending!!.contextStartSample)
    }

    @Test fun fullyExpiredStableAnchorDoesNotSearchEarlierTextOrUseTheTail() {
        val c = start()
        feed(c, "initial")
        feed(c, "second")
        feed(c, stable + "X", stable.length + 1)
        feed(c, stable + "Y", stable.length + 1)
        feed(c, stable + "X")
        val r = feed(c, stable + "Y")
        assertEquals(MatchFailure.INSUFFICIENT_STABLE_LETTERS, r.matchFailure)
        assertNull(r.prefixDelta)
        assertEquals(Kind.RECOVERY, r.request!!.kind)
    }

    @Test fun exactlyEightEligibleLettersAtBoundaryPassButSevenFail() {
        for (remaining in listOf(8, 7)) {
            val c = start()
            feed(c, "initial")
            feed(c, "second")
            val eligibility = stable.length - remaining
            feed(c, stable + "X", eligibility)
            feed(c, stable + "Y", eligibility)
            feed(c, stable.takeLast(remaining) + "X")
            val r = feed(c, stable.takeLast(remaining) + "Y")
            if (remaining == 8) {
                assertEquals(Status.HANDOVER, r.status)
                assertEquals(stable.takeLast(8), r.spliceAudit!!.anchorKey)
                assertEquals(eligibility, r.spliceAudit!!.oldAnchorRawStart)
            } else {
                assertEquals(MatchFailure.INSUFFICIENT_STABLE_LETTERS, r.matchFailure)
                assertNull(r.prefixDelta)
            }
        }
    }

    @Test fun eligibilityMapsRawUnicodeAndIgnoredGapsToKeyOffsetsWithoutPcmCuts() {
        val c = start()
        val prefix = "😀 ,"
        val raw = prefix + stable
        val eligible = prefix.length + stable.length - 17
        feed(c, "initial")
        feed(c, "second")
        feed(c, raw + "X", eligible)
        feed(c, raw + "Y", eligible)
        feed(c, stable.takeLast(17) + "X")
        val r = feed(c, stable.takeLast(17) + "Y")
        val a = r.spliceAudit!!
        assertEquals(eligible, a.oldRetainedEligibilityRawStart)
        assertEquals(1 + stable.length - 17, a.oldRetainedEligibilityKeyStart)
        assertEquals(prefix + stable.dropLast(17), r.prefixDelta)
        assertEquals(96000L, a.candidateContextStartSample)
        assertEquals(192000L, r.request!!.startSample)
        assertEquals(336000L, r.request!!.endSample)
    }

    @Test fun eligibilityRangeAndSurrogateBoundaryValidationAreAtomic() {
        val c = start()
        rejectAtomic(c, Error.INVALID_ELIGIBILITY_CURSOR) { feed(c, "😀A", -1) }
        rejectAtomic(c, Error.INVALID_ELIGIBILITY_CURSOR) { feed(c, "😀A", 4) }
        rejectAtomic(c, Error.INVALID_ELIGIBILITY_CURSOR) { feed(c, "😀A", 1) }
        val r = feed(c, "😀 ,A", 2)
        assertEquals(2, r.snapshot!!.pending!!.retainedEligibilityRawStart)
        assertEquals(1, r.snapshot!!.pending!!.retainedEligibilityKeyStart)
        assertEquals(0L, r.snapshot!!.pending!!.retainedOriginSample)
        val noEligible = feed(c, "😀 ,A", "😀 ,A".length)
        assertEquals(2, noEligible.snapshot!!.pending!!.retainedEligibilityKeyStart)
    }

    @Test fun candidateEligibilityBecomesNextOldEligibilityWithoutChangingTheCurrentAnchor() {
        val c = start()
        bootstrap(c)
        feed(c, "lead " + anchor + "first tail")
        val futureEligibility = 5 + anchor.length - 4
        val r = feed(c, "lead " + anchor + "second tail", futureEligibility)
        assertEquals(Status.HANDOVER, r.status)
        assertEquals(anchor, r.spliceAudit!!.anchorKey)
        assertEquals(futureEligibility, r.snapshot!!.pending!!.retainedEligibilityRawStart)
        assertEquals(futureEligibility, r.spliceAudit!!.candidateSecondRetainedEligibilityRawStart)
        assertEquals(192000L, r.snapshot!!.pending!!.retainedOriginSample)
        feed(c, anchor + "X")
        val next = feed(c, anchor + "Y")
        assertEquals(MatchFailure.INSUFFICIENT_STABLE_LETTERS, next.matchFailure)
    }

    @Test fun eligibilityAtNonIgnoredPunctuationDoesNotMoveForwardToFirstLetter() {
        for (keepQuestion in listOf(true, false)) {
            val c = start()
            val expired = "EXPIREDPREFIX"
            val retained = "?abcdefgh"
            feed(c, "initial")
            feed(c, "second")
            feed(c, expired + retained + "X", expired.length)
            feed(c, expired + retained + "Y", expired.length)
            val candidate = if (keepQuestion) retained else retained.drop(1)
            feed(c, candidate + "X")
            val r = feed(c, candidate + "Y")
            if (keepQuestion) {
                assertEquals(Status.HANDOVER, r.status)
                assertEquals(retained, r.spliceAudit!!.anchorKey)
                assertEquals(8, r.spliceAudit!!.anchorLetterDigitCount)
            } else {
                assertEquals(MatchFailure.CANDIDATE_ANCHOR_ABSENT, r.matchFailure)
            }
        }
    }
}
