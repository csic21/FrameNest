package com.framenest.feature.listen_translate.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline ASR via sherpa-onnx + SenseVoice-Small (int8).
 *
 * One ~228MB pack covers zh/yue/en/ja/ko with punctuation (ITN enabled), so
 * subtitle readability no longer depends on the MT stage for sentence breaks.
 * Model directory layout is owned by [SherpaModelInstaller].
 *
 * Threading mirrors [VoskAsrEngine]: a mutex serializes the single native
 * recognizer, and every window gets a fresh native stream that is released in
 * `finally`. Native failures propagate raw on purpose — keeping
 * [UnsatisfiedLinkError] unwrapped lets `listenTranslatePreparationError`
 * recognize a broken native install instead of showing an opaque message.
 *
 * @param assetManager process asset manager (native layer requires one even
 *   when models come from absolute private-storage paths).
 */
class SherpaAsrEngine(
    private val assetManager: AssetManager,
    private val numThreads: Int = DEFAULT_NUM_THREADS,
) : AsrEngine {

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    override suspend fun ensureModel(modelDir: File, langTag: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = modelDir.absolutePath
                if (loadedKey == key && recognizer != null) return@withLock
                closeLocked()
                val onnx = File(modelDir, SherpaModelInstaller.MODEL_FILE)
                val tokens = File(modelDir, SherpaModelInstaller.TOKENS_FILE)
                if (!SherpaModelInstaller.verifyPack(modelDir)) {
                    error("SenseVoice 模型缺失（${SherpaModelInstaller.MODEL_ID}）")
                }
                val senseVoice = OfflineSenseVoiceModelConfig(
                    model = onnx.absolutePath,
                    language = mapLanguage(langTag),
                    useInverseTextNormalization = true,
                )
                val modelConfig = OfflineModelConfig(
                    senseVoice = senseVoice,
                    tokens = tokens.absolutePath,
                    numThreads = numThreads.coerceAtLeast(1),
                    debug = false,
                    provider = "cpu",
                )
                val config = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4,
                )
                recognizer = OfflineRecognizer(assetManager, config)
                loadedKey = key
            }
        }

    override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val rec = recognizer ?: error("SenseVoice 模型未加载")
                if (pcm16kMono.isEmpty()) return@withLock AsrRecognition.EMPTY
                val samples = FloatArray(pcm16kMono.size) { index ->
                    (pcm16kMono[index] / 32768f).coerceIn(-1f, 1f)
                }
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    AsrRecognition(
                        text = result.text?.trim().orEmpty(),
                        words = mapTokensToWords(result.tokens, result.timestamps),
                    )
                } finally {
                    runCatching { stream.release() }
                }
            }
        }

    override fun close() {
        // best-effort; may be called off main
        runCatching {
            kotlinx.coroutines.runBlocking {
                mutex.withLock { closeLocked() }
            }
        }
    }

    private fun closeLocked() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedKey = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000

        /**
         * Two threads balance RK3588-class throughput (~0.1 RTF at 1 thread on
         * A76) against battery/thermal on sustained subtitle prefetch.
         * Measured on-device before raising.
         */
        const val DEFAULT_NUM_THREADS = 2

        /** Single SenseVoice pack covers these UI source-language tags. */
        fun supportedSourceLanguages(): Set<String> = setOf("zh", "yue", "en", "ja", "ko")

        /**
         * Map a UI language tag to a SenseVoice language hint. Unknown tags
         * fall back to `auto` (model language ID) rather than failing.
         */
        fun mapLanguage(langTag: String): String = when (langTag.trim().lowercase()) {
            "zh" -> "zh"
            "yue", "cantonese" -> "yue"
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            else -> "auto"
        }

        /**
         * Pair SenseVoice tokens with their start timestamps into cue words.
         * Blank tokens are dropped together with their timestamp so alignment
         * can never drift; a token end is the next token start (last token is
         * point-length, which is all the window midpoint mapping needs).
         * Punctuation tokens from ITN are kept — subtitles want them.
         */
        internal fun mapTokensToWords(
            tokens: Array<String>?,
            timestamps: FloatArray?,
        ): List<AsrWord> {
            if (tokens == null || timestamps == null) return emptyList()
            val starts = tokens.indices.mapNotNull { index ->
                val text = tokens[index].trim()
                if (text.isEmpty()) return@mapNotNull null
                val startSec = timestamps.getOrNull(index) ?: return@mapNotNull null
                if (!startSec.isFinite() || startSec < 0f) return@mapNotNull null
                text to (startSec * 1000f).toLong().coerceAtLeast(0L)
            }
            return starts.mapIndexed { index, (text, startMs) ->
                val endMs = starts.getOrNull(index + 1)?.second ?: startMs
                AsrWord(text = text, startMs = startMs, endMs = endMs)
            }
        }
    }
}
