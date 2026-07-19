package com.framenest.feature.listen_translate

/**
 * On-device ASR + MT for one time window.
 * FN-12 ships a [StubListenTranslateEngine]; FN-13 swaps in real models.
 */
interface ListenTranslateEngine {
    val asrModelId: String
    val mtModelId: String

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
