package com.framenest.experiment.localagreement

/**
 * Offline evidence only. This file is deliberately outside every Android source set.
 *
 * Each observation must be a NEW, complete ASR decode of growing PCM from the same
 * origin. The caller must establish that provenance and gate asynchronous results
 * by generation. Different end offsets alone cannot prove independent inference.
 * No normalization, token timestamps, audio trimming, word boundaries, MT, coverage,
 * or publication are provided. Even a fully agreed string is NOT a final utterance.
 * Single-owner / not thread safe. Caller-retained inputs/results are outside this
 * component's memory bound; this component retains only latest + confirmed text.
 */
class BoundedLocalAgreement {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_HYPOTHESES = 4
        const val MAX_SOURCE_SAMPLES = 12L * SAMPLE_RATE
        const val MAX_TEXT_CODE_POINTS = 4_096
    }

    enum class Status { RESET, TENTATIVE, STABLE_NOT_FINAL, CONFLICT, TERMINAL, REJECTED }
    enum class Error {
        NOT_INITIALIZED, INVALID_GENERATION, STALE_GENERATION, GENERATION_MISMATCH,
        INVALID_ORIGIN, ORIGIN_CHANGED, UNSUPPORTED_SAMPLE_RATE,
        INVALID_OBSERVATION, DUPLICATE_OBSERVATION, NON_INCREASING_OBSERVATION,
        SAMPLE_COUNT_MISMATCH, SOURCE_BUDGET_EXCEEDED, TEXT_TOO_LONG,
        INVALID_UTF16, ALREADY_TERMINAL,
    }
    enum class TerminalReason {
        HYPOTHESIS_BUDGET, SOURCE_BUDGET, EOF, EOF_WITHOUT_HYPOTHESES,
    }
    enum class EvidenceLabel { NO_HYPOTHESIS, TENTATIVE_ONLY, STABLE_TEXT_NOT_FINAL }

    data class Evidence(
        val generation: Long,
        val originSample: Long,
        val observedEndSample: Long?,
        val sampleCount: Long,
        val hypothesisCount: Int,
        val confirmedPrefix: String,
        val latestHypothesis: String?,
        // null means no hypothesis OR latest cannot be partitioned against confirmed.
        // The full latestHypothesis is always available in the latter case.
        val unconfirmedTail: String?,
        val conflictObserved: Boolean,
        val terminalReasons: List<TerminalReason>,
        val eofObserved: Boolean,
    ) {
        val sampleRate: Int get() = SAMPLE_RATE
        val evidenceLabel: EvidenceLabel get() = when {
            latestHypothesis == null -> EvidenceLabel.NO_HYPOTHESIS
            confirmedPrefix.isEmpty() -> EvidenceLabel.TENTATIVE_ONLY
            else -> EvidenceLabel.STABLE_TEXT_NOT_FINAL
        }
        val finalTranslationOrCoverageAllowed: Boolean get() = false
        // Conservative sum, even when two fields happen to reference the same String.
        val retainedTextCodePoints: Int get() = codePoints(confirmedPrefix) +
            (latestHypothesis?.let(::codePoints) ?: 0)
        val retainedTextCodeUnits: Int get() = confirmedPrefix.length + (latestHypothesis?.length ?: 0)
    }

    /** Reset audit deliberately contains no discarded transcript text and publishes nothing. */
    data class ResetAudit(
        val discardedGeneration: Long,
        val discardedHypothesisCount: Int,
        val discardedSampleCount: Long,
        val discardedLatestCodePoints: Int,
        val discardedConfirmedCodePoints: Int,
        val discardedConflictObserved: Boolean,
        val discardedTerminalReasons: List<TerminalReason>,
        val discardedEofObserved: Boolean,
    )

    data class Result(
        val status: Status,
        val evidence: Evidence?,
        val error: Error? = null,
        val resetAudit: ResetAudit? = null,
    )

    private data class State(
        val generation: Long,
        val originSample: Long,
        val observedEndSample: Long? = null,
        val sampleCount: Long = 0,
        val hypothesisCount: Int = 0,
        val latest: String? = null,
        val confirmed: String = "",
        val conflictObserved: Boolean = false,
        val terminalReasons: List<TerminalReason> = emptyList(),
        val eofObserved: Boolean = false,
    )

    private var state: State? = null

    /** A reset must move strictly forward in generation; invalid resets are atomic. */
    fun reset(generation: Long, originSample: Long, sampleRate: Int = SAMPLE_RATE): Result {
        if (generation < 0) return rejected(Error.INVALID_GENERATION)
        val old = state
        if (old != null && generation <= old.generation) return rejected(Error.STALE_GENERATION)
        if (originSample < 0) return rejected(Error.INVALID_ORIGIN)
        if (sampleRate != SAMPLE_RATE) return rejected(Error.UNSUPPORTED_SAMPLE_RATE)
        val audit = old?.let {
            ResetAudit(it.generation, it.hypothesisCount, it.sampleCount,
                it.latest?.let(::codePoints) ?: 0, codePoints(it.confirmed), it.conflictObserved,
                it.terminalReasons.toList(), it.eofObserved)
        }
        state = State(generation, originSample)
        return Result(Status.RESET, snapshot(), resetAudit = audit)
    }

    /**
     * sampleCount is the cumulative PCM prefix length: observedEndSample - originSample.
     * Bounds and all input validation run before any state mutation. Overshoot (>12 s)
     * is rejected, not truncated; an exactly-12 s accepted decode ends the episode.
     */
    fun observe(
        generation: Long,
        originSample: Long,
        observedEndSample: Long,
        sampleCount: Long,
        fullHypothesis: String,
    ): Result {
        generationError(generation)?.let { return rejected(it) }
        val old = checkNotNull(state)
        if (originSample != old.originSample) return rejected(Error.ORIGIN_CHANGED)
        if (old.terminalReasons.isNotEmpty()) return rejected(Error.ALREADY_TERMINAL)
        if (observedEndSample <= originSample || sampleCount <= 0) return rejected(Error.INVALID_OBSERVATION)
        // Nonnegative origin and end > origin guarantee subtraction cannot overflow.
        if (observedEndSample - originSample != sampleCount) return rejected(Error.SAMPLE_COUNT_MISMATCH)
        if (observedEndSample == old.observedEndSample && sampleCount == old.sampleCount) {
            return rejected(Error.DUPLICATE_OBSERVATION)
        }
        if (old.observedEndSample?.let { observedEndSample <= it } == true || sampleCount <= old.sampleCount) {
            return rejected(Error.NON_INCREASING_OBSERVATION)
        }
        if (sampleCount > MAX_SOURCE_SAMPLES) return rejected(Error.SOURCE_BUDGET_EXCEEDED)
        textError(fullHypothesis)?.let { return rejected(it) }

        val conflict = old.conflictObserved || !fullHypothesis.startsWith(old.confirmed)
        val confirmed = if (conflict || old.latest == null) old.confirmed else {
            // old.latest already starts with old.confirmed in every nonterminal state.
            // Thus this exact, code-point-aligned prefix can only expand confirmed.
            fullHypothesis.substring(0, exactPrefixEnd(old.latest, fullHypothesis))
        }
        val count = old.hypothesisCount + 1
        val reasons = buildList {
            if (count == MAX_HYPOTHESES) add(TerminalReason.HYPOTHESIS_BUDGET)
            if (sampleCount == MAX_SOURCE_SAMPLES) add(TerminalReason.SOURCE_BUDGET)
        }
        state = old.copy(observedEndSample = observedEndSample, sampleCount = sampleCount,
            hypothesisCount = count, latest = fullHypothesis, confirmed = confirmed,
            conflictObserved = conflict, terminalReasons = reasons)
        val status = when {
            reasons.isNotEmpty() -> Status.TERMINAL
            conflict -> Status.CONFLICT
            confirmed.isNotEmpty() -> Status.STABLE_NOT_FINAL
            else -> Status.TENTATIVE
        }
        return Result(status, snapshot())
    }

    /** EOF never promotes a tentative tail. Works after a budget; idempotent. */
    fun finish(generation: Long): Result {
        generationError(generation)?.let { return rejected(it) }
        val old = checkNotNull(state)
        if (!old.eofObserved) {
            val reason = if (old.hypothesisCount == 0) TerminalReason.EOF_WITHOUT_HYPOTHESES else TerminalReason.EOF
            state = old.copy(terminalReasons = old.terminalReasons + reason, eofObserved = true)
        }
        return Result(Status.TERMINAL, snapshot())
    }

    fun snapshot(): Evidence? = state?.let {
        Evidence(it.generation, it.originSample, it.observedEndSample, it.sampleCount,
            it.hypothesisCount, it.confirmed, it.latest,
            it.latest?.takeIf { latest -> latest.startsWith(it.confirmed) }?.substring(it.confirmed.length),
            it.conflictObserved, it.terminalReasons.toList(), it.eofObserved)
    }

    private fun rejected(error: Error) = Result(Status.REJECTED, snapshot(), error)

    private fun generationError(generation: Long): Error? {
        if (generation < 0) return Error.INVALID_GENERATION
        val current = state ?: return Error.NOT_INITIALIZED
        return when {
            generation < current.generation -> Error.STALE_GENERATION
            generation > current.generation -> Error.GENERATION_MISMATCH
            else -> null
        }
    }

    private fun textError(text: String): Error? {
        if (text.length > MAX_TEXT_CODE_POINTS * 2) return Error.TEXT_TOO_LONG
        var offset = 0
        var count = 0
        while (offset < text.length) {
            val c = text[offset]
            if (Character.isHighSurrogate(c)) {
                if (offset + 1 == text.length || !Character.isLowSurrogate(text[offset + 1])) return Error.INVALID_UTF16
                offset += 2
            } else {
                if (Character.isLowSurrogate(c)) return Error.INVALID_UTF16
                offset++
            }
            if (++count > MAX_TEXT_CODE_POINTS) return Error.TEXT_TOO_LONG
        }
        return null
    }

    private fun exactPrefixEnd(previous: String, current: String): Int {
        var offset = 0
        while (offset < previous.length && offset < current.length) {
            val codePoint = previous.codePointAt(offset)
            if (codePoint != current.codePointAt(offset)) break
            offset += Character.charCount(codePoint)
        }
        return offset
    }
}

private fun codePoints(text: String): Int = text.codePointCount(0, text.length)
