package com.framenest.feature.listen_translate

import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.AsrWord
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RealListenPipelineTest {
    @Test
    fun defaultPassesFullQuietWindowAndPreservesContextCueOffsets() = runBlocking {
        val pcm = ShortArray(72_000) // 4.5 seconds, including context.
        pcm[16_000] = 30 // Very short/quiet onset, diluted by whole-window RMS.
        val original = pcm.copyOf()
        var now = 0L
        var pcmReadyAt: Long? = null
        var recognized: ShortArray? = null
        var bounds: Pair<Long, Long>? = null
        val asr = object : AsrEngine {
            override suspend fun ensureModel(modelDir: File, langTag: String) { now += 10L }
            override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
                recognized = pcm16kMono
                now += 30L
                return AsrRecognition("quiet", listOf(AsrWord("quiet", 1_000L, 1_200L)))
            }
            override fun close() = Unit
        }
        val audio = object : ListenAudioSource {
            override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
                bounds = startMs to endMs
                now += 20L
                return pcm
            }
        }
        val engine = RealListenTranslateEngine(
            audio, asr, MlKitMtEngine(), FakeModels,
            experimentalSilenceGate = false, monotonicTimeMs = { now },
            onPcmReady = { pcmReadyAt = it },
        )
        var source: ListenWindowResult? = null
        val result = engine.processWindowWithProgress(3_000L, 6_000L, "en", "en") {
            source = it
            now += 40L // Source cache callback is excluded from ASR/MT stage times.
        }
        assertEquals(30L, pcmReadyAt)
        assertSame(pcm, recognized)
        assertArrayEquals(original, pcm)
        assertEquals(2_250L to 6_750L, bounds)
        assertEquals(3_250L, result.cueStartMs)
        assertEquals(3_850L, result.cueEndMs)
        assertEquals("quiet", result.textTgt)
        assertNull(source!!.stageTimings!!.translationMs)
        assertEquals(ListenStageTimings(10L, 20L, 0L, 30L, 0L), result.stageTimings)
        engine.close()
    }

    @Test
    fun defaultGateAndExperimentHandleSilenceQuietAndEmptySeparately() {
        val quiet = ShortArray(72_000).apply { this[32_000] = 30 }
        assertNull(listenPcmBlankReason(quiet))
        assertNull(listenPcmBlankReason(quiet, experimentalSilenceGate = true))
        assertEquals(ListenBlankReason.NearSilence, listenPcmBlankReason(ShortArray(16_000), true))
        assertEquals(ListenBlankReason.EmptyPcm, listenPcmBlankReason(shortArrayOf(), true))
    }

    @Test
    fun translationRetryReusesSourceButTrackChangeDoesNot() = runBlocking {
        var reads = 0
        var recognizes = 0
        var track = 0
        var translations = 0
        val mt = MlKitMtEngine(false) { error("translation unavailable") }
        val asr = object : AsrEngine {
            override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
            override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
                recognizes++
                return AsrRecognition("hello", emptyList())
            }
            override fun close() = Unit
        }
        val audio = object : ListenAudioSource {
            override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
                reads++
                return shortArrayOf(42)
            }
        }
        val engine = RealListenTranslateEngine(audio, asr, mt, FakeModels,
            selectedAudioTrackOrdinal = { track })
        // Original-only uses the same source/target pair and never creates a Translator.
        assertEquals("hello", engine.processWindow(0L, 3_000L, "en", "en").textTgt)
        repeat(2) {
            val result = engine.processWindowWithProgress(0L, 3_000L, "en", "zh") {
                assertEquals("hello", it.textSrc)
                translations++
            }
            assertNotNull(result.retryableErrorMessage)
            assertNull(result.stageTimings!!.asrMs)
        }
        assertEquals(2, translations)
        assertEquals(1, reads)
        assertEquals(1, recognizes)
        track = 1
        engine.processWindow(0L, 3_000L, "en", "en")
        assertEquals(2, recognizes)
        engine.processWindow(3_000L, 6_000L, "en", "en")
        assertEquals(3, recognizes)
        engine.close()
    }

    @Test
    fun senseVoicePiecesRenderWholeWordsAndShortCueRemainsVisible() = runBlocking {
        val words = com.framenest.feature.listen_translate.asr.SherpaAsrEngine.mapTokensToWords(
            arrayOf("▁tri", "bal", "▁boy"), floatArrayOf(0.3f, 0.4f, 0.6f),
        )
        val asr = object : AsrEngine {
            override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
            override suspend fun recognize(pcm16kMono: ShortArray) = AsrRecognition("tribal boy", words, "")
            override fun close() = Unit
        }
        val audio = object : ListenAudioSource {
            override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?) = shortArrayOf(20)
        }
        val engine = RealListenTranslateEngine(audio, asr, MlKitMtEngine(), FakeModels)
        val result = engine.processWindow(0L, 3_000L, "en", "en")
        assertEquals("tribal boy", result.textSrc)
        assertEquals(300L, result.cueStartMs)
        assertEquals(900L, result.cueEndMs)
        engine.close()
    }

    @Test
    fun blankPcmNeverConstructsTranslationClient() = runBlocking {
        var clients = 0
        val mt = MlKitMtEngine(false) { clients++; error("must not create MT for blank PCM") }
        val asr = object : AsrEngine {
            override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
            override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition = error("must not recognize blank")
            override fun close() = Unit
        }
        for (pcm in listOf(shortArrayOf(), ShortArray(16_000))) {
            val audio = object : ListenAudioSource {
                override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?) = pcm
            }
            val engine = RealListenTranslateEngine(audio, asr, mt, FakeModels)
            assertTrue(engine.processWindow(0L, 3_000L, "en", "zh").textSrc.isBlank())
            engine.close()
        }
        assertEquals(0, clients)
    }

    private object FakeModels : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("en")
        override fun isInstalled(langTag: String) = true
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit) = File("unused")
        override fun modelDir(langTag: String) = File("unused")
        override fun unsupportedLanguageMessage(langTag: String) = "unsupported"
        override fun incompleteModelMessage(langTag: String) = "incomplete"
        override fun downloadingMessage(langTag: String) = "unused"
        override fun readyMessage(sourceLang: String) = "unused"
        override fun modelLabel(sourceLang: String) = "test-model"
    }
}
