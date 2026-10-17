package com.framenest.feature.listen_translate

/**
 * Narrow failure recovery for a successful, empty MT result. Normal window requests,
 * source callbacks and scheduling keep their fixed-window contract. A recovery may
 * consume only the next canonical window explicitly granted by the session.
 */
interface EmptyTranslationRecoveryEngine : ListenTranslateEngine {
    fun supportsEmptyTranslationRecovery(sourceLang: String, targetLang: String): Boolean

    /**
     * No callbacks or detached work. Null keeps [original] visible and incomplete.
     * Implementations must bound retries to one merged MT attempt per request/generation.
     */
    suspend fun recoverEmptyTranslation(
        startMs: Long,
        endMs: Long,
        nextEndMs: Long,
        sourceLang: String,
        targetLang: String,
        original: ListenWindowResult,
    ): ListenEmptyTranslationRecovery?

    /** Invalidate pending work and its small in-memory retry state on seek/reset. */
    fun resetEmptyTranslationRecovery()
}

/** Both original baseline sources witness a single, exact concatenation. */
data class ListenEmptyTranslationRecovery(
    val nextSource: ListenWindowResult,
    val result: ListenWindowResult,
)

/** Strict validation is separate from the ordinary stable-source span contract. */
internal fun validateEmptyTranslationRecovery(
    startMs: Long,
    endMs: Long,
    nextEndMs: Long,
    original: ListenWindowResult,
    recovery: ListenEmptyTranslationRecovery,
) {
    require(startMs >= 0L && startMs % 3_000L == 0L && endMs - startMs == 3_000L)
    require(nextEndMs > endMs && nextEndMs - endMs <= 3_000L)
    fun validateSource(source: ListenWindowResult, start: Long, end: Long) {
        require(source.textSrc.isNotBlank() && source.textTgt.isBlank())
        require(source.retryableErrorMessage == null && source.blankReason == null)
        require(source.processedThroughMs == null || source.processedThroughMs == end)
        require(source.cueStartMs != null && source.cueEndMs != null)
        require(source.cueStartMs >= start && source.cueEndMs <= end && source.cueEndMs > source.cueStartMs)
    }
    validateSource(original, startMs, endMs)
    validateSource(recovery.nextSource, endMs, nextEndMs)
    val result = recovery.result
    require(result.textSrc == original.textSrc + recovery.nextSource.textSrc)
    require(result.textTgt.isNotBlank() && result.retryableErrorMessage == null && result.blankReason == null)
    require(result.cueStartMs == original.cueStartMs && result.cueEndMs == recovery.nextSource.cueEndMs)
    require(result.processedThroughMs == nextEndMs)
}
