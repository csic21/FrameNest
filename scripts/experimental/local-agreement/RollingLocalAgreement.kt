package com.framenest.experiment.localagreement

/**
 * One fixed offline rolling candidate, never wired into the application.
 * Request intervals describe PCM context, NOT word timing or confirmed coverage.
 * The caller owns emitted deltas/closed blocks; this object never accumulates a film.
 * All text, including successful closes, remains NOT_MT_UNIT / finalCoverage=false.
 * Single owner. Caller must generation-gate asynchronous decode delivery.
 */
class RollingLocalAgreement {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val STEP = 48_000L
        const val RETAIN = 96_000L
        const val WINDOW = 192_000L
        const val MAX_REQUEST = 384_000L
    }

    enum class Kind { BOOTSTRAP, CANDIDATE, RECOVERY }
    enum class Status { RESET, NEED_HYPOTHESIS, HANDOVER, RECOVERY_REQUESTED, CLOSED, FINISHED, REJECTED }
    enum class Error {
        NOT_INITIALIZED, INVALID_GENERATION, STALE_GENERATION, GENERATION_MISMATCH,
        INVALID_SOURCE_RANGE, NO_REQUEST, REQUEST_ID_MISMATCH, REQUEST_SPAN_MISMATCH,
        INVALID_SAMPLE_COUNT, TEXT_TOO_LONG, INVALID_UTF16, INVALID_ELIGIBILITY_CURSOR, REQUEST_PENDING,
    }
    enum class MatchFailure {
        INSUFFICIENT_STABLE_LETTERS, OLD_ANCHOR_NOT_UNIQUE,
        CANDIDATE_ANCHOR_ABSENT, CANDIDATE_ANCHOR_AMBIGUOUS,
        ANCHOR_POSITION_MISMATCH, ANCHOR_NOT_STABLE, CANDIDATE_EOF_NEEDS_SECOND,
    }

    data class Request(val id: Long, val generation: Long, val kind: Kind,
        val startSample: Long, val endSample: Long) {
        val numSamples: Long get() = endSample - startSample
    }
    data class Pending(val contextStartSample: Long, val observedEndSample: Long,
        val fullRaw: String, val pendingRawStart: Int,
        val retainedEligibilityRawStart: Int, val retainedEligibilityKeyStart: Int,
        val retainedOriginSample: Long) {
        val text: String get() = fullRaw.substring(pendingRawStart)
    }
    data class Snapshot(val generation: Long, val sourceStartSample: Long, val sourceEndSample: Long,
        val acceptedHypothesisCount: Long, val pending: Pending?, val candidate: Pending?,
        val stableKeyPrefixCodePoints: Int, val singleTranscriptAvailable: Boolean,
        val hardRestartCount: Long, val terminal: Boolean, val terminalReason: String?, val eofObserved: Boolean,
        val retainedRawCodePoints: Int)
    data class ClosedBlock(val status: String, val reason: String,
        val contextStartSample: Long, val observedEndSample: Long, val remainder: String?,
        val oldPending: Pending? = null, val recoveryFull: Pending? = null) {
        val mtUnit: Boolean get() = false
        val finalCoverage: Boolean get() = false
    }
    data class SpliceAudit(
        val generation: Long,
        val oldContextStartSample: Long, val oldObservedEndSample: Long,
        val oldPendingRawStart: Int, val oldStableKeyEndCodePoint: Int,
        val oldRetainedEligibilityRawStart: Int, val oldRetainedEligibilityKeyStart: Int,
        val oldRetainedOriginSample: Long,
        val anchorKey: String, val anchorLetterDigitCount: Int,
        val oldAnchorKeyStartCodePoint: Int, val oldAnchorKeyEndCodePoint: Int,
        val oldAnchorRawStart: Int, val oldAnchorRawEnd: Int,
        val oldDeltaRawStart: Int, val oldDeltaRawEnd: Int,
        val oldReplacedTailRawStart: Int, val oldReplacedTailRawEnd: Int,
        val oldReplacedTail: String,
        val candidateContextStartSample: Long,
        val candidateFirstEndSample: Long, val candidateSecondEndSample: Long,
        val candidateStableKeyEndCodePoint: Int,
        val candidateAnchorKeyStartCodePoint: Int, val candidateAnchorKeyEndCodePoint: Int,
        val candidateFirstAnchorRawStart: Int, val candidateFirstAnchorRawEnd: Int,
        val candidateSecondAnchorRawStart: Int, val candidateSecondAnchorRawEnd: Int,
        val candidateFirstRetainedEligibilityRawStart: Int, val candidateFirstRetainedEligibilityKeyStart: Int,
        val candidateFirstRetainedOriginSample: Long,
        val candidateSecondRetainedEligibilityRawStart: Int, val candidateSecondRetainedEligibilityKeyStart: Int,
        val candidateSecondRetainedOriginSample: Long,
        val newOmittedPrefixRawStart: Int, val newOmittedPrefixRawEnd: Int,
        val newOmittedPrefix: String, val newPendingRawStart: Int,
    )
    data class RecoveryAudit(val generation: Long, val trigger: MatchFailure,
        val contextStartSample: Long, val observedEndSample: Long,
        val witnessRole: String, val witnessRaw: String, val witnessKey: String,
        val witnessRawEnd: Int, val matched: Boolean, val failure: String?,
        val recoveryOmittedRawStart: Int?, val recoveryOmittedRawEnd: Int?,
        val recoveryOmittedRaw: String?)
    data class ResetAudit(val discardedGeneration: Long, val discardedAcceptedHypotheses: Long,
        val discardedRetainedRawCodePoints: Int, val discardedRequestId: Long?,
        val discardedSingleTranscriptAvailable: Boolean)
    data class Result(val status: Status, val snapshot: Snapshot?, val request: Request?,
        val error: Error? = null, val prefixDelta: String? = null,
        val closedBlock: ClosedBlock? = null, val spliceAudit: SpliceAudit? = null,
        val recoveryAudit: RecoveryAudit? = null, val matchFailure: MatchFailure? = null,
        val hardRestartAtSample: Long? = null, val resetAudit: ResetAudit? = null) {
        val textDisposition: String get() = "NOT_MT_UNIT"
        val mtUnit: Boolean get() = false
        val finalCoverage: Boolean get() = false
    }

    private data class Hypothesis(val start: Long, val end: Long, val map: ReversibleAgreementKey,
        val eligibilityRaw: Int, val eligibilityKey: Int) {
        val retainedOrigin: Long get() = maxOf(start, end - RETAIN)
        fun pending(cursor: Int = 0) = Pending(start, end, map.rawText, cursor,
            eligibilityRaw, eligibilityKey, retainedOrigin)
    }
    private data class State(val generation: Long, val start: Long, val eof: Long,
        val accepted: Long = 0, val old: Hypothesis? = null, val candidate: Hypothesis? = null,
        val cursor: Int = 0, val stable: Int = 0, val request: Request? = null,
        val nextRequestId: Long = 1, val recoveryTrigger: MatchFailure? = null,
        val singleTranscript: Boolean = true, val restarts: Long = 0,
        val terminal: Boolean = false, val eofObserved: Boolean = false)
    private data class AnchorMatch(val audit: SpliceAudit?, val failure: MatchFailure?)
    private var state: State? = null

    fun reset(generation: Long, sourceStartSample: Long, knownEofSample: Long): Result {
        if (generation < 0) return reject(Error.INVALID_GENERATION)
        val old = state
        if (old != null && generation <= old.generation) return reject(Error.STALE_GENERATION)
        if (sourceStartSample < 0 || knownEofSample < sourceStartSample) return reject(Error.INVALID_SOURCE_RANGE)
        val audit = old?.let { ResetAudit(it.generation, it.accepted, retainedCodePoints(it),
            it.request?.id, it.singleTranscript) }
        val initial = State(generation, sourceStartSample, knownEofSample,
            terminal = sourceStartSample == knownEofSample)
        state = if (initial.terminal) initial else request(initial, Kind.BOOTSTRAP,
            sourceStartSample, nextEnd(initial, sourceStartSample))
        return result(if (initial.terminal) Status.FINISHED else Status.RESET, resetAudit = audit)
    }

    /**
     * All validation is atomic; an invalid reply leaves the same request in flight.
     * retainedEligibilityRawStart is REQUIRED caller evidence, not an inferred cut.
     * The caller must select the first original token of ANY kind with actual onset
     * >= max(start,end-6s), mapping its start into raw UTF-16 after verifying that the
     * original token concatenation is exactly fullRaw. No qualifying token means
     * fullRaw.length. There is no lexical pre-filter or tolerance. This component
     * validates only the cursor structure, not token provenance or onset accuracy.
     * Token estimates qualify text anchors only; requested PCM is never token-cut.
     */
    fun observe(generation: Long, requestId: Long, startSample: Long, endSample: Long,
        numSamples: Long, fullRaw: String, retainedEligibilityRawStart: Int): Result {
        generationError(generation)?.let { return reject(it) }
        val s = checkNotNull(state)
        val r = s.request ?: return reject(Error.NO_REQUEST)
        if (requestId != r.id) return reject(Error.REQUEST_ID_MISMATCH)
        if (numSamples <= 0 || numSamples > MAX_REQUEST) return reject(Error.INVALID_SAMPLE_COUNT)
        if (startSample != r.startSample || endSample != r.endSample || numSamples != r.numSamples) {
            return reject(Error.REQUEST_SPAN_MISMATCH)
        }
        val built = ReversibleAgreementKey.create(fullRaw)
        if (built.error != null) return reject(when (built.error) {
            ReversibleAgreementKey.Error.TEXT_TOO_LONG -> Error.TEXT_TOO_LONG
            ReversibleAgreementKey.Error.INVALID_UTF16 -> Error.INVALID_UTF16
        })
        if (retainedEligibilityRawStart !in 0..fullRaw.length ||
            (retainedEligibilityRawStart < fullRaw.length && Character.isLowSurrogate(fullRaw[retainedEligibilityRawStart]))) {
            return reject(Error.INVALID_ELIGIBILITY_CURSOR)
        }
        val mapping = checkNotNull(built.mapping)
        val eligibilityKey = keyAtOrAfterRaw(mapping, retainedEligibilityRawStart)
        val h = Hypothesis(startSample, endSample, mapping, retainedEligibilityRawStart, eligibilityKey)
        val accepted = s.copy(accepted = s.accepted + 1, request = null)
        return when (r.kind) {
            Kind.BOOTSTRAP -> bootstrap(accepted, h)
            Kind.CANDIDATE -> candidate(accepted, h)
            Kind.RECOVERY -> recover(accepted, h)
        }
    }

    /** Known EOF controls requests; this only marks receipt after all requested PCM. */
    fun finish(generation: Long): Result {
        generationError(generation)?.let { return reject(it) }
        val s = checkNotNull(state)
        if (s.request != null) return reject(Error.REQUEST_PENDING)
        check(s.terminal)
        state = s.copy(eofObserved = true)
        return result(Status.FINISHED)
    }

    fun snapshot(): Snapshot? = state?.let {
        Snapshot(it.generation, it.start, it.eof, it.accepted, it.old?.pending(it.cursor),
            it.candidate?.pending(), it.stable, it.singleTranscript, it.restarts,
            it.terminal, when {
                !it.terminal -> null
                it.start == it.eof -> "EOF_EMPTY"
                !it.singleTranscript -> "EOF_WITH_AMBIGUOUS_OWNERSHIP"
                else -> "EOF_COMPLETE_BEST_EFFORT"
            }, it.eofObserved, retainedCodePoints(it))
    }

    fun currentRequest(): Request? = state?.request

    private fun bootstrap(s: State, h: Hypothesis): Result {
        val stable = s.old?.let { prefixCodePoints(it.map.comparisonKey, h.map.comparisonKey) } ?: 0
        val next = s.copy(old = h, candidate = null, cursor = 0, stable = stable)
        if (h.end == s.eof) return closeAtEof(next, "BOOTSTRAP_EOF")
        state = if (h.end - h.start < WINDOW) {
            request(next, Kind.BOOTSTRAP, h.start, nextEnd(s, h.end))
        } else {
            firstCandidate(next)
        }
        return result(Status.NEED_HYPOTHESIS)
    }

    private fun candidate(s: State, h: Hypothesis): Result {
        val first = s.candidate
        if (first == null) {
            val next = s.copy(candidate = h)
            if (h.end == s.eof) return requestRecovery(next, h.end, MatchFailure.CANDIDATE_EOF_NEEDS_SECOND)
            state = request(next, Kind.CANDIDATE, h.start, nextEnd(s, h.end))
            return result(Status.NEED_HYPOTHESIS)
        }
        val stable = prefixCodePoints(first.map.comparisonKey, h.map.comparisonKey)
        val match = match(s, first, h, stable)
        val audit = match.audit
        if (audit == null) return requestRecovery(s.copy(candidate = h), h.end, checkNotNull(match.failure))
        val old = checkNotNull(s.old)
        val delta = old.map.rawText.substring(s.cursor, audit.oldAnchorRawStart)
        val next = s.copy(old = h, candidate = null, cursor = audit.newPendingRawStart,
            stable = stable, recoveryTrigger = null)
        if (h.end == s.eof) return closeAtEof(next, "HANDOVER_EOF", delta, audit)
        state = firstCandidate(next)
        return result(Status.HANDOVER, prefixDelta = delta, spliceAudit = audit)
    }

    private fun firstCandidate(s: State): State {
        val old = checkNotNull(s.old)
        return request(s, Kind.CANDIDATE, old.end - RETAIN, nextEnd(s, old.end))
    }

    private fun requestRecovery(s: State, end: Long, trigger: MatchFailure): Result {
        val old = checkNotNull(s.old)
        // Exactly one recovery, from the old context origin, never a token-time cut.
        state = request(s.copy(recoveryTrigger = trigger), Kind.RECOVERY, old.start, end)
        return result(Status.RECOVERY_REQUESTED, matchFailure = trigger)
    }

    private fun recover(s: State, h: Hypothesis): Result {
        val old = checkNotNull(s.old)
        val witness = old.map.rawText.substring(0, s.cursor)
        val witnessMap = checkNotNull(ReversibleAgreementKey.create(witness).mapping)
        val failure = when {
            s.cursor == 0 -> null
            !witnessMap.hasComparisonEvidence -> "EMPTY_KEY_OMITTED_OVERLAP_WITNESS"
            !h.map.comparisonKey.startsWith(witnessMap.comparisonKey) -> "WITNESS_KEY_PREFIX_MISMATCH"
            else -> null
        }
        val cut = if (failure != null) null else if (s.cursor == 0) 0 else
            h.map.rawBoundaryForPrefix(witnessMap.retainedCodePointCount).afterIgnoredTextUtf16
        val audit = RecoveryAudit(s.generation, checkNotNull(s.recoveryTrigger), old.start, h.end,
            "OMITTED_OVERLAP_WITNESS_NOT_CALLER_FULL_PREFIX", witness, witnessMap.comparisonKey,
            s.cursor, failure == null, failure, cut?.let { 0 }, cut,
            cut?.let { h.map.rawText.substring(0, it) })
        val closed = if (cut != null) {
            ClosedBlock("BEST_EFFORT", "RECOVERY_CLOSED", old.start, h.end, h.map.rawText.substring(cut))
        } else {
            ClosedBlock("AMBIGUOUS_OWNERSHIP", "CLOSED_CONFLICT", old.start, h.end, null,
                old.pending(s.cursor), h.pending())
        }
        val terminal = h.end == s.eof
        val cleared = s.copy(old = null, candidate = null, cursor = 0, stable = 0,
            recoveryTrigger = null, singleTranscript = s.singleTranscript && cut != null,
            restarts = s.restarts + if (terminal) 0 else 1, terminal = terminal)
        state = if (terminal) cleared else request(cleared, Kind.BOOTSTRAP, h.end, nextEnd(s, h.end))
        return result(if (terminal) Status.FINISHED else Status.CLOSED, closedBlock = closed,
            recoveryAudit = audit, hardRestartAtSample = if (terminal) null else h.end)
    }

    private fun closeAtEof(s: State, reason: String, delta: String? = null, audit: SpliceAudit? = null): Result {
        val old = checkNotNull(s.old)
        val closed = ClosedBlock("BEST_EFFORT", reason, old.start, old.end, old.map.rawText.substring(s.cursor))
        state = s.copy(old = null, candidate = null, cursor = 0, stable = 0,
            request = null, terminal = true)
        return result(Status.FINISHED, prefixDelta = delta, closedBlock = closed, spliceAudit = audit)
    }

    private fun match(s: State, first: Hypothesis, latest: Hypothesis, stable: Int): AnchorMatch {
        val old = checkNotNull(s.old)
        val oldMap = old.map
        val pendingKeyStart = keyAtOrAfterRaw(oldMap, s.cursor)
        val anchorEnd = s.stable
        val anchorStart = maxOf(pendingKeyStart, anchorEnd - 32, old.eligibilityKey)
        if (anchorEnd <= anchorStart) return AnchorMatch(null, MatchFailure.INSUFFICIENT_STABLE_LETTERS)
        val letters = oldMap.spans.subList(anchorStart, anchorEnd).count { Character.isLetterOrDigit(it.codePoint) }
        if (letters < 8) return AnchorMatch(null, MatchFailure.INSUFFICIENT_STABLE_LETTERS)
        val anchor = oldMap.comparisonKey.substring(oldMap.spans[anchorStart].keyStartUtf16,
            oldMap.spans[anchorEnd - 1].keyEndUtf16)
        if (uniqueIndex(oldMap.comparisonKey, anchor) == null) return AnchorMatch(null, MatchFailure.OLD_ANCHOR_NOT_UNIQUE)
        if (!first.map.comparisonKey.contains(anchor) || !latest.map.comparisonKey.contains(anchor)) {
            return AnchorMatch(null, MatchFailure.CANDIDATE_ANCHOR_ABSENT)
        }
        val firstIndex = uniqueIndex(first.map.comparisonKey, anchor)
            ?: return AnchorMatch(null, MatchFailure.CANDIDATE_ANCHOR_AMBIGUOUS)
        val latestIndex = uniqueIndex(latest.map.comparisonKey, anchor)
            ?: return AnchorMatch(null, MatchFailure.CANDIDATE_ANCHOR_AMBIGUOUS)
        if (firstIndex != latestIndex) return AnchorMatch(null, MatchFailure.ANCHOR_POSITION_MISMATCH)
        val keyStart = latest.map.comparisonKey.codePointCount(0, latestIndex)
        val keyEnd = keyStart + anchorEnd - anchorStart
        if (keyEnd > stable) return AnchorMatch(null, MatchFailure.ANCHOR_NOT_STABLE)
        val oldRawStart = oldMap.spans[anchorStart].rawStartUtf16
        val oldRawEnd = oldMap.spans[anchorEnd - 1].rawEndUtf16
        val firstRawStart = first.map.spans[keyStart].rawStartUtf16
        val firstRawEnd = first.map.spans[keyEnd - 1].rawEndUtf16
        val latestRawStart = latest.map.spans[keyStart].rawStartUtf16
        val latestRawEnd = latest.map.spans[keyEnd - 1].rawEndUtf16
        return AnchorMatch(SpliceAudit(s.generation, old.start, old.end, s.cursor, s.stable,
            old.eligibilityRaw, old.eligibilityKey, old.retainedOrigin,
            anchor, letters, anchorStart, anchorEnd, oldRawStart, oldRawEnd,
            s.cursor, oldRawStart, oldRawStart, oldMap.rawText.length, oldMap.rawText.substring(oldRawStart),
            latest.start, first.end, latest.end, stable, keyStart, keyEnd,
            firstRawStart, firstRawEnd, latestRawStart, latestRawEnd,
            first.eligibilityRaw, first.eligibilityKey, first.retainedOrigin,
            latest.eligibilityRaw, latest.eligibilityKey, latest.retainedOrigin,
            0, latestRawStart, latest.map.rawText.substring(0, latestRawStart), latestRawStart), null)
    }

    private fun uniqueIndex(text: String, needle: String): Int? {
        val index = text.indexOf(needle)
        return if (index >= 0 && text.indexOf(needle, index + 1) < 0) index else null
    }

    private fun keyAtOrAfterRaw(map: ReversibleAgreementKey, rawCursor: Int): Int =
        map.spans.indexOfFirst { it.rawStartUtf16 >= rawCursor }
            .let { if (it < 0) map.spans.size else it }

    private fun prefixCodePoints(a: String, b: String): Int {
        var offset = 0
        var count = 0
        while (offset < a.length && offset < b.length) {
            val cp = a.codePointAt(offset)
            if (cp != b.codePointAt(offset)) break
            offset += Character.charCount(cp)
            count++
        }
        return count
    }

    private fun request(s: State, kind: Kind, start: Long, end: Long): State {
        check(s.request == null && end > start && end - start <= MAX_REQUEST && end <= s.eof)
        return s.copy(request = Request(s.nextRequestId, s.generation, kind, start, end),
            nextRequestId = s.nextRequestId + 1)
    }
    private fun nextEnd(s: State, currentEnd: Long): Long = currentEnd + minOf(STEP, s.eof - currentEnd)
    private fun retainedCodePoints(s: State): Int = listOfNotNull(s.old, s.candidate).sumOf {
        it.map.rawText.codePointCount(0, it.map.rawText.length)
    }
    private fun generationError(generation: Long): Error? {
        if (generation < 0) return Error.INVALID_GENERATION
        val s = state ?: return Error.NOT_INITIALIZED
        return when {
            generation < s.generation -> Error.STALE_GENERATION
            generation > s.generation -> Error.GENERATION_MISMATCH
            else -> null
        }
    }
    private fun reject(error: Error) = result(Status.REJECTED, error = error)
    private fun result(status: Status, error: Error? = null, prefixDelta: String? = null,
        closedBlock: ClosedBlock? = null, spliceAudit: SpliceAudit? = null,
        recoveryAudit: RecoveryAudit? = null, matchFailure: MatchFailure? = null,
        hardRestartAtSample: Long? = null, resetAudit: ResetAudit? = null) =
        Result(status, snapshot(), state?.request, error, prefixDelta, closedBlock, spliceAudit,
            recoveryAudit, matchFailure, hardRestartAtSample, resetAudit)
}
