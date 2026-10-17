package com.framenest.feature.listen_translate

import com.framenest.data.settings.AsrEngineChoice
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.AsrRecognition
import com.framenest.feature.listen_translate.asr.AsrWord
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.mt.ListenMtEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class EmptyTranslationRecoveryEngineTest {
    private class Fixture(
        val left: String = "図書",
        val right: String = "館です",
        val enabled: Boolean = true,
        var duration: Long = 6_000L,
    ) {
        val reads = mutableListOf<Pair<Long, Long>>()
        val mtInputs = mutableListOf<String>()
        var asrCalls = 0
        var track = 0
        var onRead: suspend (Int) -> ShortArray = { shortArrayOf(2_000) }
        var onRecognize: suspend (Int) -> AsrRecognition = { call ->
            if (call % 2 == 1) AsrRecognition("outside-left" + left + "outside-right", listOf(
                AsrWord(left, 2_400, 2_700), AsrWord("outside-right", 3_100, 3_500)), "")
            else AsrRecognition("ignored-hypothesis", listOf(
                AsrWord("outside-left", 0, 100), AsrWord(right, 900, 1_200),
                AsrWord("outside-right", 4_000, 4_400)), "")
        }
        var onTranslate: suspend (String) -> String = { if (it == left + right) "图书馆" else "" }
        val engine = RealListenTranslateEngine(
            audio = object : ListenAudioSource {
                override suspend fun pcmWindow(startMs: Long, endMs: Long, preferredAudioTrackOrdinal: Int?): ShortArray {
                    reads += startMs to endMs
                    return onRead(reads.size)
                }
            },
            asr = object : AsrEngine {
                override suspend fun ensureModel(modelDir: File, langTag: String) = Unit
                override suspend fun recognize(pcm16kMono: ShortArray) = onRecognize(++asrCalls)
                override fun close() = Unit
            },
            mt = object : ListenMtEngine {
                override fun supportsLanguage(langTag: String) = true
                override suspend fun ensureModel(sourceLang: String, targetLang: String) = Unit
                override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
                    mtInputs += text
                    return onTranslate(text)
                }
                override fun close() = Unit
            },
            asrModels = Models,
            selectedAudioTrackOrdinal = { track },
            knownMediaDurationMs = { duration },
            emptyTranslationRecoveryEnabled = enabled,
        )
        suspend fun baseline(source: String = "ja", target: String = "zh") = engine.processWindow(0, 3_000, source, target)
        suspend fun recover(original: ListenWindowResult, nextEnd: Long = 6_000) =
            engine.recoverEmptyTranslation(0, 3_000, nextEnd, "ja", "zh", original)
        fun morphologyInitialized(): Boolean {
            val field = engine.javaClass.getDeclaredField("recoveryMorphology\$delegate")
            field.isAccessible = true
            return (field.get(engine) as Lazy<*>).isInitialized()
        }
    }

    @Test fun nonemptyBaselineIsExactAndNeverReadsAheadOrLoadsMorphology() = runBlocking {
        val f = Fixture()
        f.onTranslate = { "  original target \n" }
        var published: ListenWindowResult? = null
        val result = f.engine.processWindowWithProgress(0, 3_000, "ja", "zh") { published = it }
        assertEquals("  original target \n", result.textTgt)
        assertEquals("図書", result.textSrc)
        assertEquals(published!!.cueStartMs, result.cueStartMs)
        assertNull(result.processedThroughMs)
        assertNull(f.recover(result))
        assertEquals(listOf(0L to 3_750L), f.reads)
        assertEquals(1, f.asrCalls)
        assertEquals(listOf("図書"), f.mtInputs)
        assertFalse(f.morphologyInitialized())
    }

    @Test fun crossingRecoveryUsesTwoOriginalAsrsExactSourceAndOriginalPts() = runBlocking {
        val f = Fixture()
        val original = f.baseline()
        val recovery = f.recover(original)!!
        assertEquals(listOf(0L to 3_750L, 2_250L to 6_750L), f.reads)
        assertEquals(2, f.asrCalls)
        assertEquals(listOf("図書", "図書館です"), f.mtInputs)
        assertEquals("図書館です", recovery.result.textSrc)
        assertEquals("館です", recovery.nextSource.textSrc)
        assertEquals("图书馆", recovery.result.textTgt)
        assertEquals(2_400L, recovery.result.cueStartMs)
        assertEquals(3_750L, recovery.result.cueEndMs)
        assertEquals(6_000L, recovery.result.processedThroughMs)
        assertEquals("図書", original.textSrc)
        assertEquals("", original.textTgt)
        assertTrue(f.morphologyInitialized())
        assertNull(f.recover(original))
        assertEquals(2, f.mtInputs.size)
    }

    @Test fun whitespaceUnicodeAndPunctuationAreNeverEditedInCombinedSource() = runBlocking {
        val f = Fixture("図 \t", "書館です。😀")
        f.onRecognize = { if (it == 1) AsrRecognition(f.left, emptyList()) else AsrRecognition(f.right, emptyList()) }
        val recovery = f.recover(f.baseline())!!
        assertEquals("図 \t書館です。😀", recovery.result.textSrc)
        assertEquals(f.left + f.right, f.mtInputs.last())
    }

    @Test fun repeatedEmptyMergedMtExhaustsOneAttemptAndKeepsBothSourceCaches() = runBlocking {
        val f = Fixture()
        f.onTranslate = { "" }
        repeat(4) { assertNull(f.recover(f.baseline())) }
        assertEquals(2, f.asrCalls)
        assertEquals(2, f.reads.size)
        assertEquals(1, f.mtInputs.count { it == f.left + f.right })
        assertEquals(4, f.mtInputs.count { it == f.left })
        f.engine.processWindow(3_000, 6_000, "ja", "zh")
        assertEquals(2, f.asrCalls)
        assertEquals(f.right, f.mtInputs.last())
    }

    @Test fun returningToAnEarlierFailedWindowCannotRearmItsMergedAttempt() = runBlocking {
        val f = Fixture(duration = 9_000)
        f.onTranslate = { "" }
        f.onRecognize = {
            AsrRecognition(when (f.reads.last().first) { 0L -> f.left; 2_250L -> f.right; else -> "別。" }, emptyList())
        }
        assertNull(f.recover(f.baseline()))
        val second = f.engine.processWindow(3_000, 6_000, "ja", "zh")
        assertNull(f.engine.recoverEmptyTranslation(3_000, 6_000, 9_000, "ja", "zh", second))
        assertNull(f.recover(f.baseline()))
        assertEquals(1, f.mtInputs.count { it == f.left + f.right })
    }

    @Test fun recoveryAttemptMemoryIsBoundedWithoutEvictingAndRearmingOldRequests() = runBlocking {
        val f = Fixture()
        f.onTranslate = { "" }
        repeat(65) { index ->
            val target = "target-$index"
            val original = f.baseline(target = target)
            assertNull(f.engine.recoverEmptyTranslation(0, 3_000, 6_000, "ja", target, original))
        }
        assertEquals(64, f.mtInputs.count { it == f.left + f.right })
        val first = f.baseline(target = "target-0")
        assertNull(f.engine.recoverEmptyTranslation(0, 3_000, 6_000, "ja", "target-0", first))
        assertEquals(64, f.mtInputs.count { it == f.left + f.right })
        assertEquals(2, f.asrCalls)
    }

    @Test fun noCrossingMorphemeDoesNotAttemptMergedMtOrReadThirdWindow() = runBlocking {
        val f = Fixture("猫。", "犬です")
        repeat(3) { assertNull(f.recover(f.baseline())) }
        assertEquals(2, f.asrCalls)
        assertEquals(listOf(f.left, f.left, f.left), f.mtInputs)
    }

    @Test fun eofAndNoNextAndDisabledAndDifferentLanguageAreNoOps() = runBlocking {
        val eof = Fixture(duration = 3_000)
        assertNull(eof.recover(eof.baseline()))
        assertEquals(1, eof.reads.size)
        assertFalse(eof.morphologyInitialized())
        val disabled = Fixture(enabled = false)
        assertNull(disabled.recover(disabled.baseline()))
        assertFalse(disabled.morphologyInitialized())
        assertEquals("mlkit-translate", disabled.engine.mtModelId)
        val f = Fixture()
        val original = f.baseline()
        assertNull(f.recover(original, 3_000))
        assertNull(f.recover(original, 6_001))
        assertNull(f.engine.recoverEmptyTranslation(0, 3_000, 6_000, "en", "zh", original))
        assertNull(f.engine.recoverEmptyTranslation(0, 3_000, 6_000, "ja", "ja", original))
        assertEquals(1, f.reads.size)
        assertFalse(f.morphologyInitialized())
        assertTrue(f.engine.mtModelId.endsWith("|empty-ja-crossing-v1"))
    }

    @Test fun clippedLastCanonicalWindowCanRecoverWithoutOwningPastEof() = runBlocking {
        val f = Fixture(duration = 4_000)
        val recovery = f.recover(f.baseline(), 4_000)!!
        assertEquals(4_000L, recovery.result.processedThroughMs)
        assertEquals(3_750L, recovery.result.cueEndMs)
        assertEquals(2_250L to 4_750L, f.reads.last())
    }

    @Test fun emptyOrSilentOrUnrecognizedNextNeverLoadsMorphologyOrClaimsCoverage() = runBlocking {
        for (kind in 0..2) {
            val f = Fixture()
            when (kind) {
                0 -> f.onRead = { if (it == 1) shortArrayOf(2_000) else shortArrayOf() }
                1 -> f.onRead = { if (it == 1) shortArrayOf(2_000) else ShortArray(1_000) }
                2 -> f.onRecognize = { if (it == 1) AsrRecognition(f.left, emptyList()) else AsrRecognition("", emptyList()) }
            }
            val original = f.baseline()
            repeat(3) { assertNull(f.recover(original)) }
            assertEquals(2, f.reads.size)
            assertEquals(listOf(f.left), f.mtInputs)
            assertFalse(f.morphologyInitialized())
        }
    }

    @Test fun baselineMtErrorNeverTriggersRecovery() = runBlocking {
        val f = Fixture()
        f.onTranslate = { error("MT failed") }
        val original = f.baseline()
        assertNotNull(original.retryableErrorMessage)
        assertNull(f.recover(original))
        assertEquals(1, f.asrCalls)
        assertFalse(f.morphologyInitialized())
    }

    @Test fun recoveryDecodeAsrAndMtErrorsKeepOriginalAndAreFinite() = runBlocking {
        for (kind in 0..2) {
            val f = Fixture()
            val original = f.baseline()
            when (kind) {
                0 -> f.onRead = { error("decode failed") }
                1 -> f.onRecognize = { error("ASR failed") }
                2 -> f.onTranslate = { error("MT failed") }
            }
            repeat(3) { assertNull(f.recover(original)) }
            assertEquals("図書", original.textSrc)
            assertEquals("", original.textTgt)
            assertEquals(2, f.reads.size)
            assertTrue(f.asrCalls <= 2)
            assertTrue(f.mtInputs.size <= 2)
        }
    }

    @Test fun unobservedOrChangedBaselineCannotTriggerRecovery() = runBlocking {
        val f = Fixture()
        val original = f.baseline()
        assertNull(f.recover(original.copy(textSrc = "forged")))
        f.track = 1
        assertNull(f.recover(original))
        assertEquals(1, f.reads.size)
    }

    @Test fun cancellationPropagatesAndResetAllowsFreshBoundedAttempt() = runBlocking {
        val f = Fixture()
        val original = f.baseline()
        f.onTranslate = { throw CancellationException("cancel recovery") }
        try { f.recover(original); fail("must propagate cancellation") } catch (_: CancellationException) { }
        assertNull(f.recover(original))
        f.engine.resetEmptyTranslationRecovery()
        f.onTranslate = { if (it == f.left + f.right) "图书馆" else "" }
        assertNotNull(f.recover(f.baseline()))
        assertEquals(4, f.asrCalls)
    }

    @Test fun laterNonemptyBaselineAfterFailedRecoveryIsStillExactAndAddsNoWork() = runBlocking {
        val f = Fixture()
        f.onTranslate = { "" }
        assertNull(f.recover(f.baseline()))
        f.onTranslate = { "  unchanged baseline translation \n" }
        val result = f.baseline()
        assertEquals("  unchanged baseline translation \n", result.textTgt)
        assertEquals(f.left, result.textSrc)
        assertNull(result.processedThroughMs)
        assertNull(f.recover(result))
        assertEquals(2, f.asrCalls)
        assertEquals(1, f.mtInputs.count { it == f.left + f.right })
    }

    @Test fun concurrentResetDuringBaselineRejectsLateSourceCallbackAndCacheWrite() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onRecognize = { withContext(NonCancellable) {
            entered.complete(Unit); release.await(); AsrRecognition("stale", emptyList())
        } }
        var callbacks = 0
        val pending = async(Dispatchers.Default) {
            f.engine.processWindowWithProgress(0, 3_000, "ja", "zh") { callbacks++ }
        }
        entered.await()
        withContext(Dispatchers.IO) { f.engine.resetEmptyTranslationRecovery() }
        release.complete(Unit)
        try { pending.await(); fail("stale baseline must cancel") } catch (_: CancellationException) { }
        assertEquals(0, callbacks)
        assertTrue(f.mtInputs.isEmpty())
        f.onRecognize = { AsrRecognition("fresh", emptyList()) }
        assertEquals("fresh", f.baseline().textSrc)
        assertEquals(2, f.asrCalls)
    }

    @Test fun cancellingNonCooperativeMergedMtCannotReturnSuccessOrRearmTheAttempt() = runBlocking {
        val f = Fixture()
        val original = f.baseline()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onTranslate = { withContext(NonCancellable) {
            entered.complete(Unit); release.await(); "late target"
        } }
        val pending = async(Dispatchers.Default) { f.recover(original) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        try { pending.await(); fail("cancelled merged MT must not succeed") } catch (_: CancellationException) { }
        assertNull(f.recover(original))
        assertEquals(2, f.mtInputs.size)
        assertEquals(2, f.asrCalls)
    }

    @Test fun resetRejectsNonCooperativeLateRecoveryAndDoesNotRepopulateSourceCache() = runBlocking {
        val f = Fixture()
        val original = f.baseline()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onRecognize = { withContext(NonCancellable) {
            entered.complete(Unit); release.await(); AsrRecognition(f.right, emptyList())
        } }
        val pending = async(Dispatchers.Default) { f.recover(original) }
        entered.await()
        withContext(Dispatchers.IO) { f.engine.resetEmptyTranslationRecovery() }
        release.complete(Unit)
        try { pending.await(); fail("stale generation must cancel") } catch (_: CancellationException) { }
        assertEquals(listOf(f.left), f.mtInputs)
        f.onRecognize = { AsrRecognition("fresh", emptyList()) }
        assertEquals("fresh", f.baseline().textSrc)
        assertEquals(3, f.asrCalls)
        assertFalse(f.morphologyInitialized())
    }

    private object Models : AsrModelSupport {
        override fun engineChoice() = AsrEngineChoice.SHERPA
        override fun supportedSourceLanguages() = setOf("ja", "en")
        override fun isInstalled(langTag: String) = true
        override suspend fun ensureInstalled(langTag: String, allowMeteredDownloads: Boolean, onProgress: (Float) -> Unit) = File("unused")
        override fun modelDir(langTag: String) = File("unused")
        override fun unsupportedLanguageMessage(langTag: String) = "unsupported"
        override fun incompleteModelMessage(langTag: String) = "incomplete"
        override fun downloadingMessage(langTag: String) = "unused"
        override fun readyMessage(sourceLang: String) = "unused"
        override fun modelLabel(sourceLang: String) = "fixture"
    }
}
