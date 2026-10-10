package com.framenest.feature.listen_translate.asr

import java.io.File

/**
 * Offline ASR for one PCM window.
 *
 * Implementations ([VoskAsrEngine], [SherpaAsrEngine]) own their native model
 * lifetime. Callers gate silence upstream via `listenPcmBlankReason` and must
 * release with [close], mirroring the previous Vosk-only lifecycle.
 *
 * All implementations consume 16 kHz mono PCM
 * ([PcmAudioMath.TARGET_SAMPLE_RATE_HZ]) and never require network.
 */
interface AsrEngine {
    /** Load (or confirm) the model under [modelDir] for [langTag]; idempotent. */
    suspend fun ensureModel(modelDir: File, langTag: String)

    /** Recognize one window of 16 kHz mono PCM. */
    suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition

    /** Best-effort native release; may be called off main. */
    fun close()
}

/** Engine-neutral recognition: full text plus timed tokens for cue mapping. */
data class AsrRecognition(
    val text: String,
    val words: List<AsrWord>,
) {
    companion object {
        val EMPTY = AsrRecognition("", emptyList())
    }
}

/** One timed token; [startMs]/[endMs] are relative to the decoded PCM start. */
data class AsrWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/** Default only skips exact digital zero; weak/short signals always reach ASR. */
internal fun isNearSilencePcm(pcm16kMono: ShortArray): Boolean =
    pcm16kMono.isNotEmpty() && pcm16kMono.all { it == 0.toShort() }
