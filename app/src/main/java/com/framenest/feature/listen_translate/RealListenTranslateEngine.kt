package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrWord
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
) : ListenTranslateEngine {

    override val asrModelId: String get() = asrModelLabel()
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
        asr.ensureModel(modelDir, srcLang)
        mt.ensureModel(srcLang, tgtLang)

        val decodeStartMs = (startMs - CONTEXT_PADDING_MS).coerceAtLeast(0L)
        val decodeEndMs = endMs + CONTEXT_PADDING_MS
        val pcm = try {
            audio.pcmWindow(
                startMs = decodeStartMs,
                endMs = decodeEndMs,
                preferredAudioTrackOrdinal = selectedAudioTrackOrdinal(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            throw IllegalStateException(
                "音频解码失败: ${t.message?.take(120) ?: t.javaClass.simpleName}",
                t,
            )
        }
        when (val blankReason = listenPcmBlankReason(pcm)) {
            null -> Unit
            else -> return ListenWindowResult(
                textSrc = "",
                textTgt = "",
                blankReason = blankReason,
            )
        }

        val recognition = try {
            asr.recognize(pcm)
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
            ListenWindowResult(textSrc, "", cueStartMs, cueEndMs),
        )

        val textTgt = try {
            mt.translate(textSrc, srcLang, tgtLang)
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
            )
        }
        return ListenWindowResult(
            textSrc = textSrc,
            textTgt = textTgt,
            cueStartMs = cueStartMs,
            cueEndMs = cueEndMs,
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

internal fun listenPcmBlankReason(pcm16kMono: ShortArray): ListenBlankReason? = when {
    pcm16kMono.isEmpty() -> ListenBlankReason.EmptyPcm
    isNearSilencePcm(pcm16kMono) -> ListenBlankReason.NearSilence
    else -> null
}

internal fun selectWordsForWindow(
    words: List<AsrWord>,
    decodeStartMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
): List<AsrWord> = words.filter { word ->
    val absoluteMidpoint = decodeStartMs + (word.startMs + word.endMs) / 2L
    absoluteMidpoint in windowStartMs until windowEndMs
}
