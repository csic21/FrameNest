package com.framenest.feature.listen_translate.recorded

import com.framenest.feature.listen_translate.RecordedExpansion
import com.framenest.feature.listen_translate.RecordedHypothesis

/** Absolute media times measured from PCM, never inferred from recognizer token gaps. */
internal data class RecordedQuietSpan(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

internal object RecordedCaptionPolicy { const val VERSION = "recorded-source-units-kuromoji-v3" }

internal enum class RecordedSourceStatus { FINAL, PROVISIONAL, UNCONFIRMED }
internal enum class RecordedSourceReason {
    MORPHOLOGY_AND_PCM_QUIET, PUNCTUATION_AND_PCM_QUIET, MORPHOLOGY_AT_EOF,
    NO_BOUNDARY_CONTEXT_LIMIT, INCOMPLETE_EOF, ALIGNMENT_UNCONFIRMED, LEFT_CONTEXT_UNCONFIRMED,
}

/** UTF-16 offsets into EXACT source text; timestamps are estimates, not word alignment. */
internal data class RecordedCharacterAnchor(
    val unitRawStart: Int, val unitRawEnd: Int,
    val hypothesisStartMs: Long, val hypothesisEndMs: Long,
    val hypothesisRawStart: Int, val hypothesisRawEnd: Int,
    val estimatedTokenStartMs: Long,
)
internal data class RecordedUnitId(val generation: Long, val sequence: Long)
internal data class RecordedSourceUnit(
    val id: RecordedUnitId,
    val text: String,
    val status: RecordedSourceStatus,
    val reason: RecordedSourceReason,
    val displayStartMs: Long,
    val displayEndMs: Long,
    /** Tentative source span, not coverage until a FINAL unit's nonempty MT succeeds. */
    val coverageStartMs: Long,
    val coverageEndMs: Long,
    val anchors: List<RecordedCharacterAnchor>,
    val supersedes: RecordedUnitId? = null,
) {
    val timingIsEstimated: Boolean get() = true
    val policyVersion: String get() = RecordedCaptionPolicy.VERSION
}
internal data class RecordedTranslationTicket(val unit: RecordedSourceUnit, val attempt: Int = 1)
internal data class RecordedCompletedCoverage(val unitId: RecordedUnitId, val startMs: Long, val endMs: Long)
internal enum class RecordedResultState { SOURCE_READY, TRANSLATED, TRANSLATION_FAILED, SUPERSEDED }
/** Persist every returned record before dropping it. The coordinator keeps no growing history. */
internal data class RecordedCaptionRecord(
    val unit: RecordedSourceUnit,
    val state: RecordedResultState,
    val translatedText: String? = null,
    val error: String? = null,
    val supersededBy: RecordedUnitId? = null,
    /** Intentional source-only handling is not an MT failure and grants no retry/coverage. */
    val deferredReason: String? = null,
)
internal sealed interface RecordedNextAction {
    data class DecodeWindow(val startMs: Long, val durationMs: Long = 15_000) : RecordedNextAction
    data class Translate(val ticket: RecordedTranslationTicket) : RecordedNextAction
    data class DecodeUnion(val request: RecordedExpansion) : RecordedNextAction
    data object Finished : RecordedNextAction
}
internal data class RecordedPendingSource(
    val text: String,
    val estimatedStartMs: Long,
    val observedThroughMs: Long,
    val anchors: List<RecordedCharacterAnchor>,
)
internal data class RecordedCoordinatorResult(
    val accepted: Boolean,
    val readThroughMs: Long,
    val nextWindowStartMs: Long,
    val readyUnits: List<RecordedTranslationTicket> = emptyList(),
    val pendingText: String = "",
    val records: List<RecordedCaptionRecord> = emptyList(),
    val completedCoverage: RecordedCompletedCoverage? = null,
    val retryTranslation: RecordedTranslationTicket? = null,
    val nextAction: RecordedNextAction,
    val error: String? = null,
    val pendingSource: RecordedPendingSource? = null,
)
internal data class RecordedWindowInput(
    val hypothesis: RecordedHypothesis,
    val quietSpans: List<RecordedQuietSpan>,
)
