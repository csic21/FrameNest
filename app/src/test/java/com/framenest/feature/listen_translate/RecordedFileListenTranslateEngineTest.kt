package com.framenest.feature.listen_translate

import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.*
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import com.framenest.player.audio.PcmWindowResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Fake devices around the actual adapter, coordinator, PCM detector and Kuromoji dictionary. */
class RecordedFileListenTranslateEngineTest {
    private class Audio : ListenAudioSource {
        val reads = mutableListOf<Pair<Long, Long>>()
        var read: suspend (Long, Long, Int) -> PcmWindowResult = { start, end, _ ->
            PcmWindowResult(ShortArray(((end - start) * 16).toInt()) { 4_000 }, end, false)
        }
        override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?) =
            pcmWindowDetailed(startMs, endMs, preferredAudioTrackOrdinal).samples
        override suspend fun pcmWindowDetailed(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): PcmWindowResult {
            reads += startMs to endMs
            return read(startMs, endMs, reads.size)
        }
    }
    private class Asr : AsrEngine {
        var calls = 0
        var recognize: suspend (Int) -> AsrRecognition = { AsrRecognition.EMPTY }
        override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
        override suspend fun recognize(pcm16kMono: ShortArray): AsrRecognition = recognize(++calls)
        override fun close() = Unit
    }
    private class Mt : ListenMtEngine {
        val texts = mutableListOf<String>()
        var preparations = 0
        var translate: suspend (String) -> String = { "译文:$it" }
        override fun supportsLanguage(langTag: String) = langTag in setOf("ja", "zh")
        override suspend fun ensureModel(sourceLang: String, targetLang: String) { preparations++ }
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            texts += text
            return translate(text)
        }
        override fun close() = Unit
    }
    private object Models : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("ja")
        override fun isInstalled(langTag: String) = true
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit) = File("/verified/asr")
        override fun modelDir(langTag: String) = File("/verified/asr")
        override fun unsupportedLanguageMessage(langTag: String) = "unsupported"
        override fun incompleteModelMessage(langTag: String) = "missing"
        override fun downloadingMessage(langTag: String) = "downloading"
        override fun readyMessage(sourceLang: String) = "ready"
        override fun modelLabel(sourceLang: String) = "fake-asr"
    }
    private class Fixture {
        val audio = Audio()
        val asr = Asr()
        val mt = Mt()
        val engine = RecordedFileListenTranslateEngine(audio, asr, mt, Models)
        val cues = mutableListOf<RecordedListenCue>()
        suspend fun step(generation: Long = 1, position: Long = 0, duration: Long = 60_000, lookAhead: Long = 30_000) =
            engine.processAhead(RecordedListenRequest(generation, position, duration, lookAhead, "ja", "zh")) { cues += it }
    }
    private fun recognition(text: String, at: Long = 0) = AsrRecognition(text, listOf(AsrWord(text, at, at + 100)), "")
    private val firstQuestion = "この イラスト の 意味 わかり ま すか"
    private val secondQuestion = " 今、世界 中 で 使 われ て いる の は 何語 でしょ うか"
    private val tail = " 英語、スペイン語、中国語、それ は ビジネス 文章 で は。"
    private fun letters(text: String, start: Long, end: Long): List<AsrWord> {
        val points = text.codePoints().toArray()
        return points.mapIndexed { i, cp ->
            val at = start + i * (end - start) / points.size
            AsrWord(String(Character.toChars(cp)), at, at + 1)
        }
    }
    private fun Fixture.questions() {
        audio.read = { start, end, _ ->
            val samples = ShortArray(((end - start) * 16).toInt()) { 4_000 }
            if (start == 0L) for ((a, b) in listOf(5_100 to 5_500, 10_100 to 10_500)) samples.fill(0, a * 16, b * 16)
            PcmWindowResult(samples, end, false)
        }
        asr.recognize = { index -> if (index == 1) {
            val words = letters(firstQuestion, 1_000, 5_000) + letters(secondQuestion, 6_000, 10_000) + letters(tail, 11_000, 14_500)
            AsrRecognition(words.joinToString("") { it.text }, words, "")
        } else AsrRecognition.EMPTY }
    }

    @Test fun `completed questions translate before next fixed grid decode`() = runBlocking {
        val f = Fixture(); f.questions()
        val initial = f.step()
        assertEquals(15_000L, initial.readThroughMs)
        assertEquals(listOf(firstQuestion, secondQuestion), f.cues.map { it.textSrc })
        assertTrue(f.cues.all { !it.isFinal && it.coverageEndMs == null })
        assertTrue(f.mt.texts.isEmpty())
        f.step(); f.step()
        assertEquals(listOf(firstQuestion, secondQuestion), f.mt.texts)
        assertEquals(2, f.cues.count { it.isFinal })
        assertEquals(listOf(0L to 15_000L), f.audio.reads)
        f.step()
        assertEquals(9_000L to 24_000L, f.audio.reads.last())
        assertTrue(f.mt.texts.none { it.contains("ビジネス") })
        f.engine.close()
    }

    @Test fun `physical EOF flushes complete morphology then completes only after MT`() = runBlocking {
        val f = Fixture()
        f.audio.read = { _, _, _ -> PcmWindowResult(ShortArray(16_000) { 4_000 }, 1_000, true) }
        f.asr.recognize = { recognition("人気です") }
        f.step(duration = 1_000)
        assertTrue(f.cues.isEmpty())
        f.step(duration = 1_000)
        assertEquals("人気です", f.cues.single().textSrc)
        assertFalse(f.cues.single().isFinal)
        val complete = f.step(duration = 1_000)
        assertFalse(complete.pending)
        assertEquals(0L, f.cues.last().coverageStartMs)
        assertEquals(1_000L, f.cues.last().coverageEndMs)
        assertTrue(f.cues.last().isFinal)
        f.step(duration = 1_000)
        assertEquals(1, f.audio.reads.size)
        assertEquals(1, f.mt.texts.size)
        f.engine.close()
    }

    @Test fun `incomplete EOF translates the bounded whole source without final coverage`() = runBlocking {
        val f = Fixture()
        f.audio.read = { _, _, _ -> PcmWindowResult(ShortArray(16_000) { 4_000 }, 1_000, true) }
        f.asr.recognize = { recognition("中国語") }
        repeat(4) { f.step(duration = 1_000) }
        assertTrue(f.cues.any { it.textSrc == "中国語" })
        assertTrue(f.cues.all { !it.isFinal && it.coverageStartMs == null && it.coverageEndMs == null })
        assertEquals("译文:中国語", f.cues.last().textTgt)
        assertEquals(1, f.mt.preparations)
        assertEquals(listOf("中国語"), f.mt.texts)
        f.engine.close()
    }

    @Test fun `failed translation retains source without completed coverage`() = runBlocking {
        val f = Fixture(); f.questions()
        f.mt.translate = { throw IOException("offline") }
        f.step(); f.step()
        assertEquals(firstQuestion, f.cues.last().textSrc)
        assertFalse(f.cues.last().isFinal)
        assertNull(f.cues.last().coverageEndMs)
        assertEquals("", f.cues.last().textTgt)
        assertEquals(1, f.mt.texts.size)
        f.engine.close()
    }

    @Test fun `full 24 second union recovery is accepted without 15 second PCM guard`() = runBlocking {
        val f = Fixture()
        f.asr.recognize = { index -> when (index) {
            1 -> recognition("昨日の事件について", 9_000)
            2 -> recognition("全く別の認識結果", 1_000)
            else -> recognition("復元された文章を確認します", 1_000)
        } }
        f.step(); f.step(); f.step()
        assertEquals(listOf(0L to 15_000L, 9_000L to 24_000L, 0L to 24_000L), f.audio.reads)
        assertTrue("Successful union source must be preserved", f.cues.any { it.textSrc == "復元された文章を確認します" })
        assertFalse(f.cues.any { it.reason == "ALIGNMENT_UNCONFIRMED" })
        assertTrue(f.mt.texts.isEmpty())
        f.engine.close()
    }

    @Test fun `union read failure retains old source and continues the original grid`() = runBlocking {
        val f = Fixture()
        f.audio.read = { start, end, call ->
            if (call == 3) throw IOException("union failed")
            PcmWindowResult(ShortArray(((end - start) * 16).toInt()) { 4_000 }, end, false)
        }
        f.asr.recognize = { index -> if (index == 1) recognition("昨日の事件について", 9_000) else recognition("全く別の認識結果", 1_000) }
        f.step(); f.step(); f.step()
        assertTrue(f.cues.any { it.textSrc == "昨日の事件について" && !it.isFinal })
        repeat(3) { if (f.audio.reads.size == 3) f.step() }
        assertEquals(18_000L, f.audio.reads.last().first)
        assertTrue(f.mt.texts.isEmpty())
        assertTrue(f.cues.all { it.coverageStartMs == null })
        f.engine.close()
    }

    @Test fun `seek resets fixed grid and stale request cannot roll generation backwards`() = runBlocking {
        val f = Fixture()
        f.step(generation = 2, position = 36_000)
        assertEquals(27_000L to 42_000L, f.audio.reads.single())
        runCatching { f.step(generation = 1, position = 0) }
        assertEquals("Stale request must not read old audio", 1, f.audio.reads.size)
        f.step(generation = 3, position = 0)
        assertEquals(0L to 15_000L, f.audio.reads.last())
        f.engine.close()
    }

    @Test fun `cancelled ASR never publishes late and retained same generation source replays safely`() = runBlocking {
        val f = Fixture(); f.questions()
        val normal = f.asr.recognize
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.asr.recognize = { index ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            normal(index)
        }
        val job = launch { f.step() }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(f.cues.isEmpty())
        f.asr.recognize = normal
        f.step()
        assertEquals("Retained publication must not reread audio", listOf(0L to 15_000L), f.audio.reads)
        assertEquals(listOf(firstQuestion, secondQuestion), f.cues.map { it.textSrc })
        assertTrue(f.cues.all { !it.isFinal })
        assertTrue(f.mt.texts.isEmpty())
        f.engine.close()
    }

    @Test fun `failed publication is retried without rerunning MT or losing the cue`() = runBlocking {
        val f = Fixture(); f.questions()
        f.step()
        val request = RecordedListenRequest(1, 0, 60_000, 30_000, "ja", "zh")
        val failure = runCatching { f.engine.processAhead(request) { throw IOException("storage unavailable") } }.exceptionOrNull()
        assertNotNull(failure)
        val translatedCalls = f.mt.texts.size
        f.step()
        assertEquals(translatedCalls, f.mt.texts.size)
        assertEquals(firstQuestion, f.cues.last().textSrc)
        assertTrue(f.cues.last().isFinal)
        f.engine.close()
    }

    @Test fun `cancelled translation cannot publish after a newer generation seek`() = runBlocking {
        val f = Fixture(); f.questions(); f.step()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.mt.translate = {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            "stale translation"
        }
        val job = launch { f.step() }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(f.cues.none { it.isFinal })
        f.cues.clear()
        f.mt.translate = { "fresh" }
        f.step(generation = 2, position = 36_000)
        assertEquals(27_000L to 42_000L, f.audio.reads.last())
        assertTrue(f.cues.isEmpty())
        assertEquals(1, f.mt.texts.size)
        f.engine.close()
    }

    @Test fun `cancelled provisional MT cannot publish after seek`() = runBlocking {
        val f = Fixture()
        f.audio.read = { _, _, _ -> PcmWindowResult(ShortArray(16000) { 4000 }, 1000, true) }
        f.asr.recognize = { recognition("中国語") }
        f.step(duration = 1000); f.step(duration = 1000)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.mt.translate = {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            "late provisional"
        }
        val job = launch { f.step(duration = 1000) }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(f.cues.all { it.textTgt.isEmpty() && !it.isFinal })
        f.audio.read = { _, end, _ -> PcmWindowResult(ShortArray(16000) { 4000 }, end, false) }
        f.asr.recognize = { AsrRecognition.EMPTY }
        f.step(generation = 2, position = 36000)
        assertTrue(f.cues.all { it.textTgt.isEmpty() && it.coverageStartMs == null })
        assertEquals(1, f.mt.texts.size)
        f.engine.close()
    }

    @Test fun `close during uncancellable ASR prevents publication`() = runBlocking {
        val f = Fixture(); f.questions()
        val normal = f.asr.recognize
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.asr.recognize = { index ->
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            normal(index)
        }
        val job = async { runCatching { f.step() } }
        entered.await(); f.engine.close(); release.complete(Unit)
        assertTrue(job.await().isFailure)
        assertTrue(f.cues.isEmpty())
        assertTrue(f.mt.texts.isEmpty())
    }

    @Test fun `media cursor uses explicit decode clock rather than sample count`() = runBlocking {
        val f = Fixture()
        f.audio.read = { _, end, _ -> PcmWindowResult(ShortArray(48_000) { 4_000 }, end, false) }
        assertEquals(15_000L, f.step().readThroughMs)
        f.step()
        assertEquals(9_000L to 24_000L, f.audio.reads.last())
        f.engine.close()
    }

    @Test fun `digital silence advances read clock without ASR hallucinations or MT`() = runBlocking {
        val f = Fixture()
        f.audio.read = { start, end, _ -> PcmWindowResult(ShortArray(((end - start) * 16).toInt()), end, false) }
        f.asr.recognize = { recognition("人気です") } // Must never be called for digital zero.
        val result = f.step()
        assertEquals(15_000L, result.readThroughMs)
        assertEquals(0, f.asr.calls)
        assertTrue(f.cues.isEmpty())
        assertTrue(f.mt.texts.isEmpty())
        f.engine.close()
    }

    @Test fun `read ahead horizon is bounded and resumes with player position`() = runBlocking {
        val f = Fixture()
        f.step(lookAhead = 0)
        val blocked = f.step(lookAhead = 0)
        assertEquals(1, f.audio.reads.size)
        assertFalse(blocked.madeProgress)
        assertEquals(15_000L, blocked.readThroughMs)
        f.step(position = 10_000, lookAhead = 0)
        assertEquals(9_000L to 24_000L, f.audio.reads.last())
        f.engine.close()

        val bounded = Fixture()
        repeat(10) { bounded.step(lookAhead = 1_000_000) }
        assertEquals(listOf(0L, 9_000L, 18_000L, 27_000L), bounded.audio.reads.map { it.first })
        assertEquals(42_000L, bounded.audio.reads.last().second) // Includes the final 15s acoustic window.
        bounded.engine.close()
    }

    @Test fun `empty explicit EOF finishes without ASR or invented coverage`() = runBlocking {
        val f = Fixture()
        f.audio.read = { _, _, _ -> PcmWindowResult(ShortArray(0), 0, true) }
        val result = f.step(duration = 0)
        assertFalse(result.pending)
        assertEquals(0L, result.readThroughMs)
        assertEquals(0, f.asr.calls)
        assertTrue(f.cues.isEmpty())
        assertTrue(f.mt.texts.isEmpty())
        f.engine.close()
    }
}
