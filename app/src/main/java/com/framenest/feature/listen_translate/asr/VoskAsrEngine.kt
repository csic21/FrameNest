package com.framenest.feature.listen_translate.asr

import com.framenest.player.audio.PcmAudioMath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline ASR via Vosk (Kaldi). Expects 16 kHz mono PCM.
 * Model directory must already exist (see [VoskModelInstaller]).
 */
class VoskAsrEngine : AsrEngine {
    private val mutex = Mutex()
    private var loadedLang: String? = null
    private var model: Model? = null

    override suspend fun ensureModel(modelDir: File, langTag: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loadedLang == langTag && model != null) return@withLock
            closeLocked()
            if (!modelDir.isDirectory) {
                error("Vosk model missing: ${modelDir.absolutePath}")
            }
            model = Model(modelDir.absolutePath)
            loadedLang = langTag
        }
    }

    override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition = withContext(Dispatchers.IO) {
        mutex.withLock {
            val m = model ?: error("Vosk model not loaded")
            if (pcm16kMono.isEmpty()) return@withLock AsrRecognition.EMPTY
            if (isNearSilencePcm(pcm16kMono)) return@withLock AsrRecognition.EMPTY
            val rec = Recognizer(m, PcmAudioMath.TARGET_SAMPLE_RATE_HZ.toFloat())
            try {
                rec.setWords(true)
                val completed = mutableListOf<VoskRecognition>()
                val chunk = ShortArray(STREAM_CHUNK_SAMPLES)
                var offset = 0
                while (offset < pcm16kMono.size) {
                    val count = minOf(chunk.size, pcm16kMono.size - offset)
                    pcm16kMono.copyInto(
                        destination = chunk,
                        destinationOffset = 0,
                        startIndex = offset,
                        endIndex = offset + count,
                    )
                    if (rec.acceptWaveForm(chunk, count)) {
                        completed += parseResult(rec.result)
                    }
                    offset += count
                }
                completed += parseResult(rec.finalResult)
                mergeVoskRecognitions(completed).toAsrRecognition()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                runCatching { rec.close() }
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
        runCatching { model?.close() }
        model = null
        loadedLang = null
    }

    private fun parseResult(json: String?): VoskRecognition {
        if (json.isNullOrBlank()) return VoskRecognition.EMPTY
        val root = JSONObject(json)
        val text = root.optString("text").trim()
        val result = root.optJSONArray("result") ?: return VoskRecognition(text, emptyList())
        val words = buildList {
            for (index in 0 until result.length()) {
                val item = result.optJSONObject(index) ?: continue
                val word = item.optString("word").trim()
                val startSeconds = item.optDouble("start", Double.NaN)
                val endSeconds = item.optDouble("end", Double.NaN)
                if (word.isNotEmpty() && startSeconds.isFinite() && endSeconds.isFinite()) {
                    add(
                        VoskWord(
                            text = word,
                            startMs = (startSeconds * 1_000.0).toLong().coerceAtLeast(0L),
                            endMs = (endSeconds * 1_000.0).toLong().coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
        return VoskRecognition(text, words)
    }

    companion object {
        private const val STREAM_CHUNK_SAMPLES: Int = 4_000 // 250 ms at 16 kHz
    }
}

/** Engine-neutral view of a Vosk result; word timings are preserved verbatim. */
internal fun VoskRecognition.toAsrRecognition(): AsrRecognition =
    AsrRecognition(
        text = text,
        words = words.map { AsrWord(text = it.text, startMs = it.startMs, endMs = it.endMs) },
    )

internal fun mergeVoskRecognitions(parts: List<VoskRecognition>): VoskRecognition {
    val nonBlank = parts.filter { it.text.isNotBlank() || it.words.isNotEmpty() }
    if (nonBlank.isEmpty()) return VoskRecognition.EMPTY
    return VoskRecognition(
        text = nonBlank.joinToString(" ") { it.text }.trim(),
        words = nonBlank.flatMap { it.words },
    )
}

data class VoskRecognition(
    val text: String,
    val words: List<VoskWord>,
) {
    companion object {
        val EMPTY = VoskRecognition("", emptyList())
    }
}

data class VoskWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)
