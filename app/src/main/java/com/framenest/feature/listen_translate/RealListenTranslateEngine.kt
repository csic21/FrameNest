package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrWord
import com.framenest.feature.listen_translate.asr.ConservativeSpeechGate
import com.framenest.feature.listen_translate.asr.isNearSilencePcm
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Product listen-translate engine (FN-14, FN-51):
 * PCM window → offline ASR ([AsrEngine]: Vosk small or SenseVoice/Sherpa) →
 * ML Kit on-device MT.
 */
class RealListenTranslateEngine(
    private val audio: ListenAudioSource,
    private val asr: AsrEngine,
    private val mt: MlKitMtEngine,
    private val asrModels: AsrModelSupport,
    private val selectedAudioTrackOrdinal: () -> Int? = { null },
    private val asrModelLabel: () -> String = { "asr" },
    private val mtModelLabel: () -> String = { "mlkit-translate" },
    private val experimentalSilenceGate: Boolean = false,
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ListenTranslateEngine {

    override val asrModelId: String
        get() = listenAsrCacheModelId(asrModelLabel(), experimentalSilenceGate)
    override val mtModelId: String get() = mtModelLabel()

    override suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
    ): ListenWindowResult = processWindowWithProgress(
        startMs, endMs, sourceLang, targetLang, onSourceRecognized = {},
    )

    override suspend fun processWindowWithProgress(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
        onSourceRecognized: suspend (ListenWindowResult) -> Unit,
    ): ListenWindowResult {
        val timer = ListenStageTimer(monotonicTimeMs)
        return try {
            processMeasuredWindow(startMs, endMs, sourceLang, targetLang, timer, onSourceRecognized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw ListenPipelineException(failure, timer.timings)
        }
    }

    private suspend fun processMeasuredWindow(
        startMs: Long,
        endMs: Long,
        sourceLang: String,
        targetLang: String,
        timer: ListenStageTimer,
        onSourceRecognized: suspend (ListenWindowResult) -> Unit,
    ): ListenWindowResult {
        val srcLang = sourceLang.lowercase()
        val tgtLang = targetLang.lowercase()
        if (!asrModels.supportedSourceLanguages().contains(srcLang)) {
            throw IllegalStateException(asrModels.unsupportedLanguageMessage(srcLang))
        }
        if (!MlKitMtEngine.isSupported(srcLang) || !MlKitMtEngine.isSupported(tgtLang)) {
            throw IllegalStateException("语言对 $srcLang→$tgtLang 不被 ML Kit 支持")
        }

        val modelDir: File = asrModels.modelDir(srcLang)
            ?: throw ModelsNotReadyException(asrModels.incompleteModelMessage(srcLang))
        timer.measure(ListenPipelineStage.ModelCheck) {
            asr.ensureModel(modelDir, srcLang)
            mt.ensureModel(srcLang, tgtLang)
        }

        val decodeStartMs = (startMs - CONTEXT_PADDING_MS).coerceAtLeast(0L)
        val decodeEndMs = endMs + CONTEXT_PADDING_MS
        val pcm = try {
            timer.measure(ListenPipelineStage.PcmRead) {
                audio.pcmWindow(
                    startMs = decodeStartMs,
                    endMs = decodeEndMs,
                    preferredAudioTrackOrdinal = selectedAudioTrackOrdinal(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw IllegalStateException(
                "音频解码失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        val blank = timer.measure(ListenPipelineStage.SilenceGate) {
            listenPcmBlankReason(pcm, experimentalSilenceGate)
        }
        when (val blankReason = blank) {
            null -> Unit
            else -> return ListenWindowResult(
                textSrc = "",
                textTgt = "",
                blankReason = blankReason,
                stageTimings = timer.timings,
            )
        }

        val recognition = try {
            timer.measure(ListenPipelineStage.Asr) { asr.recognize(pcm) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw IllegalStateException(
                "ASR 失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        val wordsInWindow = selectWordsForWindow(
            words = recognition.words,
            decodeStartMs = decodeStartMs,
            windowStartMs = startMs,
            windowEndMs = endMs,
        )
        val textSrc = if (wordsInWindow.isNotEmpty()) {
            wordsInWindow.joinToString(" ") { it.text }.trim()
        } else if (recognition.words.isEmpty()) {
            recognition.text
        } else {
            ""
        }
        if (textSrc.isBlank()) {
            return ListenWindowResult(
                textSrc = "",
                textTgt = "",
                blankReason = ListenBlankReason.UnrecognizedSpeech,
                stageTimings = timer.timings,
            )
        }

        val cueStartMs = wordsInWindow.firstOrNull()
            ?.let { decodeStartMs + it.startMs }
            ?.coerceIn(startMs, endMs)
            ?: startMs
        val cueEndMs = wordsInWindow.lastOrNull()
            ?.let { decodeStartMs + it.endMs }
            ?.coerceIn(cueStartMs, endMs)
            ?: endMs

        // Keep source text visible and durable even if MT is slow or cancelled.
        // This remains a single bounded window; no detached translation queue.
        onSourceRecognized(
            ListenWindowResult(textSrc, "", cueStartMs, cueEndMs, stageTimings = timer.timings),
        )

        val textTgt = try {
            timer.measure(ListenPipelineStage.Translation) { mt.translate(textSrc, srcLang, tgtLang) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Still keep ASR text if MT fails.
            return ListenWindowResult(
                textSrc = textSrc,
                textTgt = "",
                cueStartMs = cueStartMs,
                cueEndMs = cueEndMs,
                retryableErrorMessage = "翻译失败：${t.message?.take(80) ?: "error"}",
                stageTimings = timer.timings,
            )
        }
        return ListenWindowResult(
            textSrc = textSrc,
            textTgt = textTgt,
            cueStartMs = cueStartMs,
            cueEndMs = cueEndMs,
            stageTimings = timer.timings,
        )
    }

    fun close() {
        audio.close()
        asr.close()
        mt.close()
    }

    private companion object {
        const val CONTEXT_PADDING_MS = 750L
    }
}

internal fun listenPcmBlankReason(
    pcm16kMono: ShortArray,
    experimentalSilenceGate: Boolean = false,
): ListenBlankReason? = when {
    pcm16kMono.isEmpty() -> ListenBlankReason.EmptyPcm
    experimentalSilenceGate -> if (ConservativeSpeechGate.evaluate(pcm16kMono).shouldRecognize) {
        null
    } else {
        ListenBlankReason.NearSilence
    }
    isNearSilencePcm(pcm16kMono) -> ListenBlankReason.NearSilence
    else -> null
}

/** Do not reuse baseline silence coverage when the experimental gate is selected. */
internal fun listenAsrCacheModelId(modelId: String, experimentalSilenceGate: Boolean): String =
    if (experimentalSilenceGate) "$modelId|silence-gate-v1" else "$modelId|digital-zero-v2"

internal fun selectWordsForWindow(
    words: List<AsrWord>,
    decodeStartMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): List<AsrWord> = words.filter { word ->
    val absoluteMidpoint = decodeStartMs + (word.startMs + word.endMs) / 2L
    absoluteMidpoint in windowStartMs until windowEndMs
}
