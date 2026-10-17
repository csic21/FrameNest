package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCueEntity
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmptyTranslationRecoverySessionTest {
    private val identity = PlaybackIdentity("server", "media", "recovery.mkv")
    private val languages = ListenLanguagePair("ja", "zh")
    private class Engine : EmptyTranslationRecoveryEngine {
        override val asrModelId = "baseline-asr"
        override val mtModelId = "mt|empty-ja-crossing-v1"
        val requested = mutableListOf<Pair<Long, Long>>()
        val granted = mutableListOf<Triple<Long, Long, Long>>()
        var resets = 0
        var publishSource = true
        var baseline: (Long, Long) -> ListenWindowResult = { start, end ->
            if (start == 0L) ListenWindowResult("図書", "", 2_400, 3_000)
            else ListenWindowResult("next", "下一句", start, end)
        }
        var recovery: suspend (ListenWindowResult, Long) -> ListenEmptyTranslationRecovery? = { original, nextEnd ->
            val next = ListenWindowResult("館です", "", 3_150, minOf(3_750L, nextEnd))
            ListenEmptyTranslationRecovery(next, ListenWindowResult(original.textSrc + next.textSrc, "图书馆",
                original.cueStartMs, next.cueEndMs, processedThroughMs = nextEnd))
        }
        override fun supportsEmptyTranslationRecovery(sourceLang: String, targetLang: String) = sourceLang == "ja" && targetLang != sourceLang
        override fun resetEmptyTranslationRecovery() { resets++ }
        override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String) = baseline(startMs, endMs)
        override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
            requested += startMs to endMs
            val result = baseline(startMs, endMs)
            if (publishSource) onSourceRecognized(result.copy(textTgt = ""))
            return result
        }
        override suspend fun recoverEmptyTranslation(startMs: Long, endMs: Long, nextEndMs: Long,
            sourceLang: String, targetLang: String, original: ListenWindowResult): ListenEmptyTranslationRecovery? {
            granted += Triple(startMs, endMs, nextEndMs)
            return recovery(original, nextEndMs)
        }
    }

    @Test fun originalSourceStaysVisibleUntilValidatedSuccessReplacesItAndCommitsExactCoverage() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine()
        val produce = engine.recovery
        engine.recovery = { original, end -> delay(1_000); produce(original, end) }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(2_500, 9_000, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals("図書", session.uiState.value.overlayText)
        assertEquals(1, repo.listCues(identity, languages).single().rev)
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L to 3_000L), engine.requested)
        assertEquals(listOf(Triple(0L, 3_000L, 6_000L)), engine.granted)
        val cues = repo.listCues(identity, languages)
        assertEquals(2, cues.size)
        val speech = cues.single { it.textSrc.isNotBlank() }
        assertEquals("図書館です", speech.textSrc)
        assertEquals(2_400L, speech.startMs)
        assertEquals(3_750L, speech.endMs)
        assertEquals(1, speech.rev)
        assertEquals("図書館です\n图书馆", session.uiState.value.overlayText)
        assertEquals(6_000L, repo.getJob(identity, languages)!!.coveredUntilMs)
        assertFalse(ListenTranslateWindows.needsFill(cues, 0, 3_000))
        assertFalse(ListenTranslateWindows.needsFill(cues, 3_000, 6_000))
        assertTrue(ListenTranslateWindows.needsFill(cues, 6_000, 9_000))
        assertEquals(listOf(ListenCompletedInterval(0, 6_000)), ListenCompletedIntervals.fromCues(cues))
        session.release()
    }

    @Test fun failedRecoveryRetainsOriginalRevisionAndNeverCertifiesEitherWindow() = runTest {
        for (throws in listOf(false, true)) {
            val repo = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = Engine().apply { recovery = { _, _ -> if (throws) error("recovery failed") else null } }
            val session = ListenTranslateSession(repo, engine, this, identity)
            session.setSourceLang("ja"); session.onPlaybackTick(2_500, 6_000, playing = false)
            session.setEnabled(true); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            val cue = repo.listCues(identity, languages).single()
            assertEquals("図書", cue.textSrc); assertEquals("", cue.textTgt); assertEquals(1, cue.rev)
            assertEquals("図書", session.uiState.value.overlayText)
            assertEquals(0L, repo.getJob(identity, languages)!!.coveredUntilMs)
            assertEquals(1, engine.requested.size)
            assertEquals(1, engine.granted.size)
            assertNotNull(session.uiState.value.errorMessage)
            session.release()
        }
    }

    @Test fun nonemptyTranslationKeepsExactResultAndDoesNotInvokeRecovery() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine().apply { baseline = { _, _ -> ListenWindowResult("図書", "  原译文 \n", 2_400, 3_000) } }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(2_500, 6_000, playing = false)
        session.setEnabled(true); runCurrent()
        assertTrue(engine.granted.isEmpty())
        val cue = repo.listCues(identity, languages).single { it.textSrc.isNotBlank() }
        assertEquals("  原译文 \n", cue.textTgt)
        assertEquals(2_400L, cue.startMs); assertEquals(3_000L, cue.endMs)
        assertEquals(3_000L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test fun noNextAtEofAndNoSourceCallbackDoNotRecover() = runTest {
        for ((duration, callback) in listOf(3_000L to true, 6_000L to false)) {
            val engine = Engine().apply { publishSource = callback }
            val repo = ListenTranslateRepository(FakeListenTranslateDao())
            val session = ListenTranslateSession(repo, engine, this, identity)
            session.setSourceLang("ja"); session.onPlaybackTick(2_500, duration, playing = false)
            session.setEnabled(true); runCurrent()
            assertTrue(engine.granted.isEmpty())
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            assertEquals("図書", repo.listCues(identity, languages).single().textSrc)
            session.release()
        }
    }

    @Test fun completedOrTranslatedProvisionalNextCueIsNeverOverwritten() = runTest {
        for (revision in listOf(1, ListenCoverageRev.PROVISIONAL_SOURCE)) {
            val repo = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = Engine()
            repo.ensureJob(identity, languages, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
            repo.upsertCueForExistingJob(identity, languages, 3_500, 4_000, "saved next", "已译下一句", revision)
            val session = ListenTranslateSession(repo, engine, this, identity)
            session.setSourceLang("ja"); session.onPlaybackTick(2_500, 6_000, playing = false)
            session.setEnabled(true); runCurrent()
            assertTrue(engine.granted.isEmpty())
            assertEquals("已译下一句", repo.listCues(identity, languages).single { it.textSrc == "saved next" }.textTgt)
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            session.release()
        }
    }

    @Test fun successfulRecoveryRemovesNextSourceOnlyRevisionOneAndPreservesThirdWindow() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine()
        repo.ensureJob(identity, languages, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
        repo.upsertCueForExistingJob(identity, languages, 3_150, 3_750, "館です", "", 1)
        repo.upsertCueForExistingJob(identity, languages, 6_100, 7_000, "third", "第三句", 1)
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(2_500, 9_000, playing = false)
        session.setEnabled(true); runCurrent()
        val cues = repo.listCues(identity, languages)
        assertEquals("图书馆", ListenTranslateWindows.cueAt(cues, 3_300)!!.textTgt)
        assertFalse(cues.any { it.textSrc == "館です" || it.textSrc == "図書" })
        assertEquals("第三句", cues.single { it.textSrc == "third" }.textTgt)
        assertEquals(6_000L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test fun transactionRechecksTranslationInsertedAfterSessionSnapshot() = runTest {
        val dao = FakeListenTranslateDao()
        var raceAtCommit = false
        val repo = ListenTranslateRepository(dao) {
            if (raceAtCommit) {
                raceAtCommit = false
                // Deterministic concurrent-writer interleaving: the Session already
                // checked its snapshot; the transaction has not read the rows yet.
                dao.cueRows += ListenTranslateCueEntity(99, identity.serverId, identity.share,
                    identity.normalizedPath(), "ja", "zh", 3_150, 3_750, "saved", "已译", 1)
            }
            0L
        }
        val engine = Engine()
        val valid = engine.recovery
        engine.recovery = { original, end -> valid(original, end).also { raceAtCommit = true } }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(2_500, 6_000, playing = false)
        session.setEnabled(true); runCurrent()
        val cues = repo.listCues(identity, languages)
        assertEquals("已译", cues.single { it.textSrc == "saved" }.textTgt)
        assertEquals("図書", cues.single { it.textTgt.isBlank() }.textSrc)
        assertFalse(cues.any { it.textSrc == "図書館です" })
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test fun finalWindowGrantIsClippedToKnownDuration() = runTest {
        val engine = Engine()
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(2_500, 3_500, playing = false)
        session.setEnabled(true); runCurrent()
        assertEquals(listOf(Triple(0L, 3_000L, 3_500L)), engine.granted)
        assertEquals(3_500L, session.uiState.value.coveredUntilMs)
        assertEquals(3_500L, repo.listCues(identity, languages).single { it.textSrc.isNotBlank() }.endMs)
        session.release()
    }

    @Test fun malformedReplacementIsRejectedWithoutWeakeningOrdinarySpanValidation() = runTest {
        for (kind in 0..4) {
            val repo = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = Engine()
            val valid = engine.recovery
            engine.recovery = { original, nextEnd ->
                val r = valid(original, nextEnd)!!
                when (kind) {
                    0 -> r.copy(result = r.result.copy(textSrc = "changed source"))
                    1 -> r.copy(result = r.result.copy(processedThroughMs = 9_000))
                    2 -> r.copy(result = r.result.copy(cueStartMs = 0))
                    3 -> r.copy(nextSource = r.nextSource.copy(cueStartMs = 2_999))
                    else -> r.copy(result = r.result.copy(textTgt = ""))
                }
            }
            val session = ListenTranslateSession(repo, engine, this, identity)
            session.setSourceLang("ja"); session.onPlaybackTick(2_500, 9_000, playing = false)
            session.setEnabled(true); runCurrent()
            assertEquals("図書", repo.listCues(identity, languages).single().textSrc)
            assertEquals(1, repo.listCues(identity, languages).single().rev)
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            assertNotNull(session.uiState.value.errorMessage)
            session.release()
        }
    }

    @Test fun ordinaryEngineCannotExtendSourceCallbackOrFinalOutsideRequest() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine().apply { baseline = { _, _ -> ListenWindowResult("bad", "bad", 0, 6_000, processedThroughMs = 6_000) } }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(0, 9_000, playing = false)
        session.setEnabled(true); runCurrent()
        assertTrue(engine.granted.isEmpty())
        assertTrue(repo.listCues(identity, languages).isEmpty())
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test fun seekRejectsNonCooperativeLateRecoveryAndStartsOnlyNewCanonicalWindow() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine()
        val valid = engine.recovery
        engine.recovery = { original, end -> withContext(NonCancellable) { delay(1_000); valid(original, end) } }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(2_500, 9_000, playing = false)
        session.setEnabled(true); runCurrent()
        val priorResets = engine.resets
        session.onSeek(3_000)
        assertTrue(engine.resets > priorResets)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L to 3_000L, 3_000L to 6_000L), engine.requested)
        val cues = repo.listCues(identity, languages)
        assertFalse(cues.any { it.textSrc == "図書館です" })
        assertEquals("next\n下一句", session.uiState.value.overlayText)
        assertTrue(ListenTranslateWindows.needsFill(cues, 0, 3_000))
        session.release()
    }

    @Test fun disableBackgroundReleaseAndLanguageChangeRejectLateReplacement() = runTest {
        for (action in 0..3) {
            val repo = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = Engine()
            val valid = engine.recovery
            engine.recovery = { original, end -> withContext(NonCancellable) { delay(1_000); valid(original, end) } }
            val session = ListenTranslateSession(repo, engine, this, identity)
            session.setSourceLang("ja"); session.onPlaybackTick(2_500, 6_000, playing = false)
            session.setEnabled(true); runCurrent()
            when (action) {
                0 -> session.setEnabled(false)
                1 -> session.setForeground(false)
                2 -> session.release()
                else -> session.setTargetLang("en")
            }
            advanceTimeBy(1_000); runCurrent()
            assertFalse(repo.listCues(identity, languages).any { it.textSrc == "図書館です" })
            assertEquals(0L, repo.getJob(identity, languages)!!.coveredUntilMs)
            session.release()
        }
    }

    @Test fun exactFullSpanReplacementCommitsCoverageWithoutDuplicateBlankMarker() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = Engine().apply {
            baseline = { _, _ -> ListenWindowResult("図書", "", 0, 3_000) }
            recovery = { original, end ->
                val next = ListenWindowResult("館", "", 3_000, end)
                ListenEmptyTranslationRecovery(next, ListenWindowResult("図書館", "图书馆", 0, end, processedThroughMs = end))
            }
        }
        val session = ListenTranslateSession(repo, engine, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(0, 6_000, playing = false)
        session.setEnabled(true); runCurrent()
        assertEquals(1, repo.listCues(identity, languages).size)
        assertEquals(6_000L, repo.getJob(identity, languages)!!.coveredUntilMs)
        assertEquals(6_000L, session.uiState.value.coveredUntilMs)
        session.release()
    }
}
