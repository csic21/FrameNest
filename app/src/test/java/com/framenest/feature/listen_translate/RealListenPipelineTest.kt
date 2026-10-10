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
    fun experimentPassesFullQuietWindowAndPreservesContextCueOffsets() = runBlocking {
        val pcm = ShortArray(72_000) // 4.5 seconds, including context.
        pcm[16_000] = 30 // Very short/quiet onset, diluted by whole-window RMS.
        val original = pcm.copyOf()
        var now = 0L
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
            experimentalSilenceGate = true, monotonicTimeMs = { now },
        )
        var source: ListenWindowResult? = null
        val result = engine.processWindowWithProgress(3_000L, 6_000L, "en", "en") {
            source = it
            now += 40L // Source cache callback is excluded from ASR/MT stage times.
        }
        assertSame(pcm, recognized)
        assertArrayEquals(original, pcm)
        assertEquals(2_250L to 6_750L, bounds)
        assertEquals(3_250L, result.cueStartMs)
        assertEquals(3_450L, result.cueEndMs)
        assertEquals("quiet", result.textTgt)
        assertNull(source!!.stageTimings!!.translationMs)
        assertEquals(ListenStageTimings(10L, 20L, 0L, 30L, 0L), result.stageTimings)
        engine.close()
    }

    @Test
    fun defaultGateAndExperimentHandleSilenceQuietAndEmptySeparately() {
        val quiet = ShortArray(72_000).apply { this[32_000] = 30 }
        assertEquals(ListenBlankReason.NearSilence, listenPcmBlankReason(quiet))
        assertNull(listenPcmBlankReason(quiet, experimentalSilenceGate = true))
        assertEquals(ListenBlankReason.NearSilence, listenPcmBlankReason(ShortArray(16_000), true))
        assertEquals(ListenBlankReason.EmptyPcm, listenPcmBlankReason(shortArrayOf(), true))
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
