package com.framenest.feature.listen_translate

/**
 * On-device ASR + MT for one time window.
 * FN-12 ships a [StubListenTranslateEngine]; FN-13 swaps in real models.
 */
interface ListenTranslateEngine {
    val asrModelId: String
    val mtModelId: String

    /**
     * Opt-in sentence-span requests, capped by the session at 12 seconds. Null keeps
     * the legacy fixed-window scheduler. The engine must include its segmentation
     * policy/version in [asrModelId] so incompatible cached boundaries are retired.
     */
    val adaptiveMaxSpanMs: Long? get() = null

    /** Engines may limit an experiment to explicitly supported source languages. */
    fun adaptiveMaxSpanMsFor(sourceLang: String): Long? = adaptiveMaxSpanMs

    /**
     * Produce source (ASR) and target (MT) text for [startMs, endMs).
     * Implementations must not require network for cached models.
     */
    suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult

    /**
     * Publish a stable source cue before waiting for MT. The callback belongs to the
     * calling coroutine: do not retain it or launch detached work. Legacy engines
     * keep their one-result contract through this default implementation.
     */
    suspend fun processWindowWithProgress(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
        onSourceRecognized: suspend (ListenWindowResult) -> Unit,
    ): ListenWindowResult = processWindow(startMs, endMs, sourceLang, targetLang)
}

data class ListenWindowResult(
    val textSrc: String,
    val textTgt: String,
    /** Optional speech bounds inside the requested window. */
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    /** Non-fatal stage failure: source text may be shown, but the window remains retryable. */
    val retryableErrorMessage: String? = null,
    /** Why an otherwise successful window produced no cue. */
    val blankReason: ListenBlankReason? = null,
    /** Local measurements only; never persisted with subtitle content. */
    val stageTimings: ListenStageTimings? = null,
    /**
     * Exclusive end of audio actually consumed for this unit, not decoded read-ahead.
     * Null preserves the legacy requested end. Must be in (request start, request end],
     * and identical in the stable source callback and final result for that unit.
     * A shorter-than-requested span requires the engine's adaptive scheduling opt-in.
     */
    val processedThroughMs: Long? = null,
    /** Bounded, content-free experimental cut classification; never persisted. */
    val segmentBoundaryReason: String? = null,
)

enum class ListenBlankReason {
    EmptyPcm,
    NearSilence,
    UnrecognizedSpeech,
}

/**
 * Deterministic offline stub so UI/Room can be demoed without models.
 * Real PCM/ASR is wired in a later model task (FN-13); window text is stable for tests.
 */
class StubListenTranslateEngine : ListenTranslateEngine {
    override val asrModelId: String = "stub-asr-1"
    override val mtModelId: String = "stub-mt-1"

    override suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult {
        val s = (startMs / 1000L).coerceAtLeast(0L)
        val e = (endMs / 1000L).coerceAtLeast(s)
        val src = "[$sourceLang] ${s}s–${e}s"
        val tgt = when (targetLang.lowercase()) {
            "zh" -> "【$s–${e}秒】"
            "en" -> "[$s–${e}s]"
            else -> "[$targetLang] $s–${e}s"
        }
        return ListenWindowResult(textSrc = src, textTgt = tgt)
    }
}
