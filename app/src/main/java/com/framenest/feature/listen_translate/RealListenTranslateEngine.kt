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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
    /** Content-free event time; includes silent PCM, but not an empty decode. */
    private val onPcmReady: (Long) -> Unit = {},
) : ListenTranslateEngine {

    private data class SourceKey(val start: Long, val end: Long, val language: String, val track: Int?)
    private var cachedSource: Pair<SourceKey, ListenWindowResult>? = null

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
        if (srcLang != tgtLang && (!MlKitMtEngine.isSupported(srcLang) || !MlKitMtEngine.isSupported(tgtLang))) {
            throw IllegalStateException("语言对 $srcLang→$tgtLang 不被 ML Kit 支持")
        }

        return processListenWindowStages(
            prepareTranslation = {
                if (srcLang != tgtLang) {
                    timer.measure(ListenPipelineStage.TranslationPreparation) {
                        withContext(Dispatchers.IO) { mt.ensureModel(srcLang, tgtLang) }
                    }
                }
            },
            recognizeSource = { startTranslationPreparation ->
                val key = SourceKey(startMs, endMs, srcLang, selectedAudioTrackOrdinal())
                cachedSource?.takeIf { it.first == key }?.second
                    ?.copy(stageTimings = timer.timings)?.also { startTranslationPreparation() }
                    ?: recognizeWindow(startMs, endMs, srcLang, key.track, timer, startTranslationPreparation).also {
                        currentCoroutineContext().ensureActive()
                        if (it.textSrc.isNotBlank()) cachedSource = key to it
                    }
            },
            publishSource = onSourceRecognized,
            translateSource = { text ->
                timer.measure(ListenPipelineStage.Translation) { mt.translate(text, srcLang, tgtLang) }
            },
            timings = { timer.timings },
        )
    }

    private suspend fun recognizeWindow(
        startMs: Long,
        endMs: Long,
        srcLang: String,
        track: Int?,
        timer: ListenStageTimer,
        startTranslationPreparation: () -> Unit,
    ): ListenWindowResult {
        val modelDir: File = asrModels.modelDir(srcLang)
            ?: throw ModelsNotReadyException(asrModels.incompleteModelMessage(srcLang))
        timer.measure(ListenPipelineStage.ModelCheck) {
            asr.ensureModel(modelDir, srcLang)
        }

        val decodeStartMs = (startMs - CONTEXT_PADDING_MS).coerceAtLeast(0L)
        val decodeEndMs = endMs + CONTEXT_PADDING_MS
        val pcm = try {
            timer.measure(ListenPipelineStage.PcmRead) {
                audio.pcmWindow(
                    startMs = decodeStartMs,
                    endMs = decodeEndMs,
                    preferredAudioTrackOrdinal = track,
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
        currentCoroutineContext().ensureActive()
        if (pcm.isNotEmpty()) onPcmReady(monotonicTimeMs())
        timer.recordRemoteReads(audio.takeRemoteReadStats())
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

        // Do not start/cancel model downloads repeatedly for confirmed silence.
        // Once PCM needs ASR, prepare MT concurrently with native recognition.
        startTranslationPreparation()
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
            wordsInWindow.joinToString(recognition.textSeparator) { it.text }.trim()
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
        val tokenEndMs = wordsInWindow.lastOrNull()
            ?.let { decodeStartMs + it.endMs }
            ?.coerceIn(cueStartMs, endMs)
            ?: endMs

        val cueEndMs = maxOf(tokenEndMs, cueStartMs + MIN_CUE_DURATION_MS).coerceAtMost(endMs)
        return ListenWindowResult(textSrc, "", cueStartMs, cueEndMs, stageTimings = timer.timings)
    }

    fun close() {
        cachedSource = null
        audio.close()
        asr.close()
        mt.close()
    }

    private companion object {
        const val CONTEXT_PADDING_MS = 750L
        const val MIN_CUE_DURATION_MS = 600L
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
    if (experimentalSilenceGate) "$modelId|silence-gate-v1|text-v3" else "$modelId|digital-zero-v2|text-v3"

internal fun selectWordsForWindow(
    words: List<AsrWord>,
    decodeStartMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): List<AsrWord> = words.filter { word ->
    val absoluteMidpoint = decodeStartMs + (word.startMs + word.endMs) / 2L
    absoluteMidpoint in windowStartMs until windowEndMs
}
