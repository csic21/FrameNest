package com.framenest.feature.listen_translate

import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.*
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JapaneseRealEngineTest {
    private class Models : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("ja", "en")
        override fun isInstalled(langTag: String) = true
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit) = File("model")
        override fun modelDir(langTag: String) = File("model")
        override fun unsupportedLanguageMessage(langTag: String) = "unsupported"
        override fun incompleteModelMessage(langTag: String) = "missing"
        override fun downloadingMessage(langTag: String) = "loading"
        override fun readyMessage(sourceLang: String) = "ready"
        override fun modelLabel(sourceLang: String) = "fixture"
    }
    private class Audio(val samples: ShortArray) : ListenAudioSource {
        val reads = mutableListOf<Pair<Long, Long>>()
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
            reads += startMs to endMs
            return samples
        }
    }
    private class Asr(var shorter: String = "この意味わかりますか") : AsrEngine {
        val sizes = mutableListOf<Int>()
        override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition {
            sizes += pcm16kMono.size
            return if (sizes.size % 2 == 1) AsrRecognition(shorter, emptyList(), "") else
                AsrRecognition("この意味わかりますか今世界", listOf(
                    AsrWord("この意味わかりますか", 2880, 6270), AsrWord("今世界", 6270, 6270)), "")
        }
        override fun close() = Unit
    }
    private class Mt : ListenMtEngine {
        val inputs = mutableListOf<String>()
        var preparations = 0
        var beforeTranslation: () -> Unit = {}
        override fun supportsLanguage(langTag: String) = true
        override suspend fun ensureModel(sourceLang: String, targetLang: String) { preparations++ }
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            beforeTranslation(); inputs += text; return "测试译文"
        }
        override fun close() = Unit
    }
    private fun pcm() = ShortArray(240_000) { if (it in 79_360 until 93_760) 0 else 8_000 }
    private fun engine(audio: Audio, asr: Asr, mt: Mt) = RealListenTranslateEngine(
        audio, asr, mt, Models(), experimentalJapaneseSegments = true,
    )

    @Test fun onePcmReadTwoLocalPassesPublishWholeStablePrefix() = runBlocking {
        val audio = Audio(pcm()); val asr = Asr(); val mt = Mt(); val engine = engine(audio, asr, mt)
        var source: ListenWindowResult? = null
        mt.beforeTranslation = { assertNotNull(source) }
        val result = engine.processWindowWithProgress(0, 12_000, "ja", "zh") { source = it }
        assertEquals(listOf(0L to 15_000L), audio.reads)
        assertEquals(listOf(192_000, 240_000), asr.sizes)
        assertEquals(listOf("この意味わかりますか"), mt.inputs)
        assertEquals(5_410L, result.processedThroughMs)
        assertEquals(result.processedThroughMs, source?.processedThroughMs)
        assertEquals(2_880L, result.cueStartMs)
        assertEquals(5_410L, result.cueEndMs)
        assertEquals("TerminalWithAcousticPause", result.segmentBoundaryReason)
        assertTrue(engine.asrModelId.contains("ja-owned-prefix-v1"))
    }
    @Test fun sourceRetryReusesRecognitionButRetainsActualSpan() = runBlocking {
        val audio = Audio(pcm()); val asr = Asr(); val mt = Mt(); val engine = engine(audio, asr, mt)
        val first = engine.processWindow(0, 12_000, "ja", "zh")
        val second = engine.processWindow(0, 12_000, "ja", "zh")
        assertEquals(1, audio.reads.size); assertEquals(2, asr.sizes.size)
        assertEquals(first.processedThroughMs, second.processedThroughMs)
        assertEquals(2, mt.inputs.size)
    }
    @Test fun noAgreementDoesNotPublishOrTranslateUnstableAudio() = runBlocking {
        val audio = Audio(pcm()); val mt = Mt(); val engine = engine(audio, Asr("違う文章"), mt)
        var published = false
        try {
            engine.processWindowWithProgress(0, 12_000, "ja", "zh") { published = true }
            fail("unstable span must fail closed")
        } catch (_: ListenPipelineException) { }
        assertFalse(published); assertTrue(mt.inputs.isEmpty())
    }
    @Test fun digitalZeroOwnedCoreDoesNotCompleteLookaheadSpeech() = runBlocking {
        val audio = Audio(ShortArray(240_000) { if (it < 192_000) 0 else 8_000 })
        val asr = Asr(); val mt = Mt()
        val result = engine(audio, asr, mt).processWindow(0, 12_000, "ja", "zh")
        assertEquals(ListenBlankReason.NearSilence, result.blankReason)
        assertEquals(12_000L, result.processedThroughMs)
        assertTrue(asr.sizes.isEmpty()); assertEquals(0, mt.preparations)
    }
    @Test fun experimentIsJapaneseOnlyAndOffByDefault() {
        val audio = Audio(pcm()); val asr = Asr(); val mt = Mt()
        val experimental = engine(audio, asr, mt)
        assertEquals(12_000L, experimental.adaptiveMaxSpanMsFor("ja"))
        assertNull(experimental.adaptiveMaxSpanMsFor("en"))
        assertNull(experimental.adaptiveMaxSpanMsFor("auto"))
        assertNull(RealListenTranslateEngine(audio, asr, mt, Models()).adaptiveMaxSpanMsFor("ja"))
    }
}
