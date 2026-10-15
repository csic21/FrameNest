package com.framenest.feature.listen_translate

/** ViewModel owns and closes these resources; Session owns only work/caching. */
interface OwnedListenTranslateEngine : ListenTranslateEngine {
    fun close()
}

/** Recorded-file producer progress is deliberately independent of completed subtitles. */
interface RecordedListenTranslateEngine : OwnedListenTranslateEngine {
    fun supportsRecordedPair(sourceLang: String, targetLang: String): Boolean
    suspend fun processAhead(
        request: RecordedListenRequest,
        onCue: suspend (RecordedListenCue) -> Unit,
    ): RecordedListenProgress
}

data class RecordedListenRequest(
    val generation: Long,
    val positionMs: Long,
    val durationMs: Long,
    val lookAheadMs: Long,
    val sourceLang: String,
    val targetLang: String,
)

/**
 * Provisional text never certifies coverage, even when it has a translation.
 * A nonfinal textTgt is temporary presentation only: never persist it as a cache hit.
 */
data class RecordedListenCue(
    val displayStartMs: Long,
    val displayEndMs: Long,
    val textSrc: String,
    val textTgt: String,
    val isFinal: Boolean,
    val coverageStartMs: Long? = null,
    val coverageEndMs: Long? = null,
    val decodedThroughMs: Long,
    val confirmedSilence: Boolean = false,
    val reason: String? = null,
    val timings: ListenStageTimings? = null,
    /** Exact former display span when this candidate replaces a provisional unit. */
    val supersededStartMs: Long? = null,
    val supersededEndMs: Long? = null,
)

data class RecordedListenProgress(
    val readThroughMs: Long,
    val newAudioMs: Long,
    val madeProgress: Boolean,
    val pending: Boolean,
    val message: String? = null,
    val timings: ListenStageTimings? = null,
)
