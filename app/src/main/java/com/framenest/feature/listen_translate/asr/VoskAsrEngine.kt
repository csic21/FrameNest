package com.framenest.feature.listen_translate.asr

import com.framenest.player.audio.PcmAudioMath
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline ASR via Vosk (Kaldi). Expects 16 kHz mono PCM.
 * Model directory must already exist (see [VoskModelInstaller]).
 */
class VoskAsrEngine {
    private val mutex = Mutex()
    private var loadedLang: String? = null
    private var model: Model? = null

    suspend fun ensureModel(modelDir: File, langTag: String) = withContext(Dispatchers.IO) {
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

    suspend fun recognize(pcm16kMono: ShortArray): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val m = model ?: error("Vosk model not loaded")
            if (pcm16kMono.isEmpty()) return@withLock ""
            val rms = PcmAudioMath.rmsNormalized(pcm16kMono)
            if (rms < 0.008f) return@withLock "" // near silence
            val rec = Recognizer(m, PcmAudioMath.TARGET_SAMPLE_RATE_HZ.toFloat())
            try {
                val bytes = shortsToLeBytes(pcm16kMono)
                // Feed in chunks to keep native buffer modest.
                var offset = 0
                val chunk = 8000 * 2 // 0.5s of 16-bit mono
                while (offset < bytes.size) {
                    val len = minOf(chunk, bytes.size - offset)
                    val slice = bytes.copyOfRange(offset, offset + len)
                    rec.acceptWaveForm(slice, slice.size)
                    offset += len
                }
                parseText(rec.finalResult)
            } finally {
                runCatching { rec.close() }
            }
        }
    }

    fun close() {
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

    private fun shortsToLeBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        return out
    }

    private fun parseText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        // {"text" : "hello world"} or partial results
        val key = "\"text\""
        val idx = json.indexOf(key)
        if (idx < 0) return ""
        val colon = json.indexOf(':', idx + key.length)
        if (colon < 0) return ""
        val firstQuote = json.indexOf('"', colon + 1)
        if (firstQuote < 0) return ""
        val secondQuote = json.indexOf('"', firstQuote + 1)
        if (secondQuote < 0) return ""
        return json.substring(firstQuote + 1, secondQuote).trim()
    }
}
