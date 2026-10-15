package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateDao
import com.framenest.data.listen_translate.ListenTranslateCueEntity
import com.framenest.data.listen_translate.ListenProvisionalCleanup
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenRecordedSessionTest {
    private val identity = PlaybackIdentity("public", "fixture", "recorded.mkv")
    private val languages = ListenLanguagePair("ja", "zh")
    private abstract class Producer : RecordedListenTranslateEngine {
        override val asrModelId = "recorded-source-v1"
        override val mtModelId = "quality-test-v1"
        override fun supportsRecordedPair(sourceLang: String, targetLang: String) = sourceLang == "ja" && targetLang == "zh"
        override fun close() = Unit
        override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("legacy scheduler must not run")
    }
    private fun provisional() = RecordedListenCue(0L, 5_000L, "待確認の原文", "", false, decodedThroughMs = 15_000L)
    private fun finalCue(start: Long = 5_000L, end: Long = 10_000L) = RecordedListenCue(start, end,
        "完整な文です", "完整句子", true, start, end, 24_000L)

    @Test fun readingContinuesPastUnconfirmedGapWithoutClaimingCompletedCoverage() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                when (++calls) {
                    1 -> { onCue(provisional()); return RecordedListenProgress(15_000L, 15_000L, true, true) }
                    2 -> { onCue(finalCue()); return RecordedListenProgress(24_000L, 9_000L, true, false) }
                    else -> return RecordedListenProgress(24_000L, 0L, false, false)
                }
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(0L, 30_000L, true); session.setEnabled(true); runCurrent()
        assertEquals(3, calls)
        assertEquals(24_000L, session.uiState.value.recordedReadThroughMs)
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        assertTrue(session.uiState.value.currentCaptionUnconfirmed)
        assertEquals(listOf(ListenCompletedInterval(5_000L, 10_000L)), ListenCompletedIntervals.fromCues(repository.listCues(identity, languages)))
        assertEquals("待確認の原文", session.uiState.value.activeCue!!.textSrc)
        session.release()
    }

    @Test fun emptyTranslationCannotBecomeFinalCoverage() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                calls++; onCue(finalCue(0L, 5_000L).copy(textTgt = ""))
                return RecordedListenProgress(15_000L, 15_000L, true, false)
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity, monotonicTimeMs = { testScheduler.currentTime })
        session.setSourceLang("ja"); session.onPlaybackTick(0L, 30_000L, true); session.setEnabled(true); runCurrent()
        assertEquals(1, calls)
        assertTrue(repository.listCues(identity, languages).isEmpty())
        advanceTimeBy(500); runCurrent(); assertEquals(1, calls)
        session.release()
    }

    @Test fun backgroundCancellationRejectsLateNativeCallbackAndReadProgress() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                try { awaitCancellation() } catch (_: CancellationException) {
                    return withContext(NonCancellable) {
                        onCue(finalCue(0L, 5_000L))
                        RecordedListenProgress(24_000L, 24_000L, true, false)
                    }
                }
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        session.setSourceLang("ja"); session.onPlaybackTick(0L, 30_000L, true); session.setEnabled(true); runCurrent()
        session.setForeground(false); runCurrent()
        assertTrue(repository.listCues(identity, languages).isEmpty())
        assertEquals(0L, session.uiState.value.recordedReadThroughMs)
        session.release()
    }

    private fun edgeFinalCue(displayEnd: Long = 5_000L, coverageEnd: Long = displayEnd) =
        RecordedListenCue(0L, displayEnd, "完整な文です", "完整句子", true, 0L, coverageEnd, 30_000L)
    private fun start(s: ListenTranslateSession, duration: Long = 30_000L) {
        s.setSourceLang("ja"); s.onPlaybackTick(0L, duration, true); s.setEnabled(true)
    }
    @Test fun fullyCachedShortMediaDoesNotInvokeProducer() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                calls++; return RecordedListenProgress(5_000L, 0L, false, false)
            }
        }
        repo.ensureJob(identity, languages, asrModel = p.asrModelId, mtModel = p.mtModelId)
        repo.upsertCueForExistingJob(identity, languages, 0L, 5_000L, "缓存", "cache")
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s, 5_000L)
            runCurrent()
            assertEquals(0, calls)
        } finally {
            s.release()
        }
    }
    @Test fun noOpTrueProgressDoesNotBusyDrain() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                calls++; return RecordedListenProgress(0L, 0L, calls < 4, false)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            assertEquals(1, calls)
        } finally {
            s.release()
        }
    }
    @Test fun finalCoverageCannotExtendBeyondMediaEnd() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                onCue(edgeFinalCue(5_000L, 10_000L)); return RecordedListenProgress(10_000L, 10_000L, false, false)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s, 5_000L)
            runCurrent()
            assertTrue(repo.listCues(identity, languages).isEmpty())
        } finally {
            s.release()
        }
    }
    @Test fun provisionalReplayCannotOverwriteAcceptedFinalAtIdenticalBounds() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                onCue(edgeFinalCue()); onCue(edgeFinalCue().copy(isFinal = false, textTgt = "", coverageStartMs = null, coverageEndMs = null))
                return RecordedListenProgress(15_000L, 15_000L, false, true)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            assertEquals(listOf(ListenCompletedInterval(0L, 5_000L)), ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)))
        } finally {
            s.release()
        }
    }
    @Test fun speechMarkedSilenceCannotBypassValidMt() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                onCue(edgeFinalCue(4_000L, 5_000L).copy(textTgt = "", confirmedSilence = true))
                return RecordedListenProgress(15_000L, 15_000L, false, false)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            assertTrue(ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)).isEmpty())
        } finally {
            s.release()
        }
    }
    @Test fun failedCoverageCommitCannotCertifyUncommittedTail() = runTest {
        val fake = FakeListenTranslateDao()
        val dao = object : ListenTranslateDao by fake {
            override suspend fun replaceCueAndUpdateProgressForExistingJob(entity: ListenTranslateCueEntity, coveredUntilMs: Long, status: String, durationMs: Long?, updatedAtEpochMs: Long, provisionalCleanup: ListenProvisionalCleanup?): Long? {
                throw IllegalStateException("injected coverage write failure")
            }
        }
        val repo = ListenTranslateRepository(dao)
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                onCue(edgeFinalCue(5_000L, 10_000L)); return RecordedListenProgress(15_000L, 15_000L, false, false)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            assertEquals(listOf(ListenCompletedInterval(0L, 5_000L)), ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)))
            assertEquals(0L, repo.getJob(identity, languages)!!.coveredUntilMs)
        } finally {
            s.release()
        }
    }
    @Test fun finalPrefixRemovesProvisionalButCannotCertifyMissingTail() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                onCue(edgeFinalCue(10_000L).copy(isFinal = false, coverageStartMs = null, coverageEndMs = null))
                onCue(edgeFinalCue(5_000L))
                return RecordedListenProgress(15_000L, 15_000L, false, true)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            assertEquals(listOf(ListenCompletedInterval(0L, 5_000L)), ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)))
            assertEquals(1, repo.listCues(identity, languages).size)
        } finally {
            s.release()
        }
    }

    @Test fun rtfAddsTranslationOnlyWorkWithoutCountingAudioTwice() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var now = 0L
        var calls = 0
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                calls++
                return when (calls) {
                    1 -> { now += 100L; RecordedListenProgress(10_000L, 10_000L, true, true) }
                    2 -> { now += 100L; onCue(edgeFinalCue()); RecordedListenProgress(10_000L, 0L, false, false) }
                    else -> RecordedListenProgress(10_000L, 0L, false, false)
                }
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity, monotonicTimeMs = { now })
        try {
            start(s)
            runCurrent()
            assertEquals(0.02, s.uiState.value.effectiveRealtimeFactor!!, 0.00001)
        } finally {
            s.release()
        }
    }
    @Test fun seekRejectsLateCallbackFromPreviousGeneration() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls > 1) return RecordedListenProgress(0L, 0L, false, false)
                try { awaitCancellation() } catch (_: CancellationException) {
                    return withContext(NonCancellable) { onCue(edgeFinalCue()); RecordedListenProgress(15_000L, 15_000L, false, false) }
                }
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            s.onSeek(20_000L)
            runCurrent()
            assertTrue(repo.listCues(identity, languages).isEmpty())
            assertEquals(0L, s.uiState.value.recordedReadThroughMs)
        } finally {
            s.release()
        }
    }
    @Test fun purgedLeaseCannotRecreateCacheFromLateCallback() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val release = CompletableDeferred<Unit>()
        val p = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                release.await(); onCue(edgeFinalCue()); return RecordedListenProgress(15_000L, 15_000L, false, false)
            }
        }
        val s = ListenTranslateSession(repo, p, this, identity)
        try {
            start(s)
            runCurrent()
            repo.purgeMedia(identity)
            release.complete(Unit)
            runCurrent()
            assertTrue(repo.listCues(identity, languages).isEmpty())
            assertNull(repo.getJob(identity, languages))
            assertEquals(0L, s.uiState.value.recordedReadThroughMs)
        } finally {
            s.release()
        }
    }

    @Test fun provisionalBilingualTextIsVisibleButDoesNotSetFinalStartupMilestones() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val finish = CompletableDeferred<Unit>()
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ): RecordedListenProgress {
                onCue(provisional().copy(textTgt = "待确认译文"))
                finish.await()
                onCue(finalCue(0L, 5_000L))
                return RecordedListenProgress(24_000L, 24_000L, false, false)
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity,
            monotonicTimeMs = { testScheduler.currentTime })
        try {
            start(session)
            runCurrent()
            val provisionalTiming = session.uiState.value.startupTiming!!
            assertNotNull(provisionalTiming.firstSourcePublishedMs)
            assertNotNull(provisionalTiming.firstSourceSelectedMs)
            assertNull(provisionalTiming.firstBilingualPublishedMs)
            assertNull(provisionalTiming.firstBilingualSelectedMs)
            assertNull(provisionalTiming.firstGeneratedBilingualCachePublishMs)
            assertTrue(session.uiState.value.currentCaptionUnconfirmed)
            assertTrue(session.uiState.value.overlayText.contains("待确认译文"))
            finish.complete(Unit)
            runCurrent()
            val finalTiming = session.uiState.value.startupTiming!!
            assertNotNull(finalTiming.firstBilingualPublishedMs)
            assertNotNull(finalTiming.firstBilingualSelectedMs)
            assertNotNull(finalTiming.firstGeneratedBilingualCachePublishMs)
            assertFalse(session.uiState.value.currentCaptionUnconfirmed)
        } finally {
            session.release()
        }
    }

    @Test fun seekInvalidationResetsReadCursorAndUnconfirmedCaptionState() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ): RecordedListenProgress {
                onCue(provisional())
                return RecordedListenProgress(15_000L, 15_000L, false, true)
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        try {
            start(session)
            runCurrent()
            assertEquals(15_000L, session.uiState.value.recordedReadThroughMs)
            assertTrue(session.uiState.value.currentCaptionUnconfirmed)
            session.onSeek(20_000L)
            assertEquals(0L, session.uiState.value.recordedReadThroughMs)
            assertFalse(session.uiState.value.currentCaptionUnconfirmed)
        } finally {
            session.release()
        }
    }

    @Test fun endOfMediaDoesNotInvokeProducer() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ): RecordedListenProgress {
                calls++
                return RecordedListenProgress(5_000L, 0L, false, false)
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        try {
            session.setSourceLang("ja")
            session.onPlaybackTick(5_000L, 5_000L, true)
            session.setEnabled(true)
            runCurrent()
            assertEquals(0, calls)
        } finally {
            session.release()
        }
    }

    @Test fun readProgressBeyondMediaEndIsRejected() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ) = RecordedListenProgress(10_000L, 10_000L, true, false)
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        try {
            start(session, 5_000L)
            runCurrent()
            assertEquals(0L, session.uiState.value.recordedReadThroughMs)
            assertNotNull(session.uiState.value.errorMessage)
        } finally {
            session.release()
        }
    }

    @Test fun adaptationRtfExcludesOnlyReportedPreparationWait() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var now = 0L
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ): RecordedListenProgress {
                now += 200L
                return RecordedListenProgress(10_000L, 10_000L, false, true,
                    timings = ListenStageTimings(translationPreparationWaitMs = 100L))
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity, monotonicTimeMs = { now })
        try {
            start(session)
            runCurrent()
            assertEquals(0.02, session.uiState.value.effectiveRealtimeFactor!!, 0.00001)
            assertEquals(0.01, session.uiState.value.adaptationRealtimeFactor!!, 0.00001)
        } finally {
            session.release()
        }
    }

    @Test fun provisionalReplacementRemovesOldBoundsWithoutDeletingFinalCoverage() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val producer = object : Producer() {
            override suspend fun processAhead(
                request: RecordedListenRequest,
                onCue: suspend (RecordedListenCue) -> Unit,
            ): RecordedListenProgress {
                onCue(provisional())
                onCue(finalCue(2_000L, 3_000L))
                onCue(provisional().copy(displayStartMs = 1_000L, displayEndMs = 6_000L,
                    textSrc = "修正された原文", supersededStartMs = 0L, supersededEndMs = 5_000L))
                return RecordedListenProgress(24_000L, 24_000L, false, true)
            }
        }
        val session = ListenTranslateSession(repository, producer, this, identity)
        try {
            start(session)
            runCurrent()
            val cues = repository.listCues(identity, languages)
            assertEquals(2, cues.size)
            assertFalse(cues.any { it.startMs == 0L && it.endMs == 5_000L })
            assertTrue(cues.any { it.startMs == 1_000L && it.endMs == 6_000L &&
                it.rev == ListenCoverageRev.PROVISIONAL_SOURCE })
            assertEquals(listOf(ListenCompletedInterval(2_000L, 3_000L)),
                ListenCompletedIntervals.fromCues(cues))
        } finally {
            session.release()
        }
    }

    @Test fun temporaryTranslationIsVisibleButNeverPersistedOrCompletedAcrossSessions() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls == 1) onCue(provisional().copy(textTgt = "临时译文"))
                return RecordedListenProgress(15000, if (calls == 1) 15000 else 0, calls == 1, false)
            }
        }
        val first = ListenTranslateSession(repo, producer, this, identity)
        start(first); runCurrent()
        assertEquals("待確認の原文\n临时译文", first.uiState.value.overlayText)
        assertTrue(first.uiState.value.currentCaptionUnconfirmed)
        assertEquals(0L, first.uiState.value.preparedAheadMs)
        assertEquals(0L, first.uiState.value.coveredUntilMs)
        assertTrue(repo.listCues(identity, languages).all { it.textTgt.isEmpty() })
        assertTrue(ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)).isEmpty())
        first.release()
        assertEquals("", first.uiState.value.activeCue!!.textTgt)
        val second = ListenTranslateSession(repo, producer, this, identity)
        try {
            start(second); runCurrent()
            assertEquals("待確認の原文", second.uiState.value.overlayText)
            assertTrue(second.uiState.value.currentCaptionUnconfirmed)
        } finally { second.release() }
    }

    @Test fun temporaryTranslationsAreBoundedAndExplicitSmallSeekClearsThem() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls == 1) repeat(9) { i -> onCue(RecordedListenCue(i * 1000L, (i + 1) * 1000L,
                    "原文$i", "临时$i", false, decodedThroughMs = 15000)) }
                return RecordedListenProgress(15000, if (calls == 1) 15000 else 0, calls == 1, false)
            }
        }
        val s = ListenTranslateSession(repo, producer, this, identity)
        try {
            start(s); runCurrent()
            assertEquals("原文0", s.uiState.value.overlayText)
            s.onPlaybackTick(1000, 30000, true)
            assertEquals("原文1\n临时1", s.uiState.value.overlayText)
            s.onSeek(1000)
            assertEquals("原文1", s.uiState.value.overlayText)
            assertTrue(repo.listCues(identity, languages).all { it.textTgt.isEmpty() })
            assertTrue(ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)).isEmpty())
        } finally { s.release() }
    }

    @Test fun provisionalRevisionReplacesTemporaryTargetAndFinalSupersedesItDurably() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val revise = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                when (++calls) {
                    1 -> onCue(provisional().copy(textTgt = "旧临时"))
                    2 -> { revise.await(); onCue(provisional().copy(displayEndMs = 6000, textSrc = "修正原文",
                        textTgt = "新临时", supersededStartMs = 0, supersededEndMs = 5000)) }
                    3 -> { finish.await(); onCue(finalCue(0, 6000)) }
                }
                return RecordedListenProgress(if (calls == 1) 15000 else 24000,
                    when (calls) { 1 -> 15000; 2 -> 9000; else -> 0 }, calls <= 3, calls < 3)
            }
        }
        val s = ListenTranslateSession(repo, producer, this, identity)
        try {
            start(s); runCurrent()
            assertEquals("待確認の原文\n旧临时", s.uiState.value.overlayText)
            revise.complete(Unit); runCurrent()
            assertEquals("修正原文\n新临时", s.uiState.value.overlayText)
            assertEquals(listOf("修正原文"), repo.listCues(identity, languages).map { it.textSrc })
            assertTrue(repo.listCues(identity, languages).all { it.textTgt.isEmpty() })
            finish.complete(Unit); runCurrent()
            assertEquals("完整な文です\n完整句子", s.uiState.value.overlayText)
            assertFalse(s.uiState.value.currentCaptionUnconfirmed)
            assertEquals(listOf(ListenCompletedInterval(0, 6000)), ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)))
            s.onSeek(1000)
            assertEquals("完整な文です\n完整句子", s.uiState.value.overlayText)
        } finally { s.release() }
    }

    @Test fun lateTemporaryTranslationCannotSurviveGenerationCancellation() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls > 1) awaitCancellation()
                onCue(provisional())
                try { awaitCancellation() } catch (_: CancellationException) {
                    return withContext(NonCancellable) {
                        onCue(provisional().copy(textTgt = "迟到临时译文"))
                        RecordedListenProgress(15000, 15000, true, false)
                    }
                }
            }
        }
        val s = ListenTranslateSession(repo, producer, this, identity)
        try {
            start(s); runCurrent(); s.onSeek(1000); runCurrent()
            assertEquals("", s.uiState.value.activeCue!!.textTgt)
            assertTrue(repo.listCues(identity, languages).all { it.textTgt.isEmpty() })
            assertEquals(0L, s.uiState.value.recordedReadThroughMs)
        } finally { s.release() }
    }

    @Test fun persistedLegacyProvisionalTargetIsNotReusedForRecordedPresentation() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit) =
                RecordedListenProgress(0, 0, false, false)
        }
        repo.ensureJob(identity, languages, asrModel = producer.asrModelId, mtModel = producer.mtModelId)
        repo.upsertCueForExistingJob(identity, languages, 0, 5000, "旧原文", "旧的不确定译文", rev = ListenCoverageRev.PROVISIONAL_SOURCE)
        val s = ListenTranslateSession(repo, producer, this, identity)
        try {
            start(s); runCurrent()
            assertEquals("旧原文", s.uiState.value.overlayText)
            assertTrue(s.uiState.value.currentCaptionUnconfirmed)
            assertEquals(0L, s.uiState.value.preparedAheadMs)
        } finally { s.release() }
    }


    @Test fun replacingEngineRejectsLateOldTargetAndRefreshesModelIdentity() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        val old = object : Producer() {
            override val asrModelId = "old-recorded"
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                try { awaitCancellation() } catch (_: CancellationException) {
                    return withContext(NonCancellable) {
                        onCue(provisional().copy(textTgt = "旧模型迟到译文"))
                        RecordedListenProgress(15000, 15000, true, false)
                    }
                }
            }
        }
        var calls = 0
        val fresh = object : Producer() {
            override val asrModelId = "fresh-recorded"
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls == 1) onCue(provisional().copy(textSrc = "新原文", textTgt = "新临时译文"))
                return RecordedListenProgress(15000, if (calls == 1) 15000 else 0, calls == 1, false)
            }
        }
        val s = ListenTranslateSession(repo, old, this, identity)
        try {
            start(s); runCurrent(); s.setEngine(fresh); runCurrent()
            assertEquals("新原文\n新临时译文", s.uiState.value.overlayText)
            assertEquals("fresh-recorded", repo.getJob(identity, languages)!!.asrModel)
            assertTrue(repo.listCues(identity, languages).all { it.textSrc == "新原文" && it.textTgt.isEmpty() })
            assertTrue(ListenCompletedIntervals.fromCues(repo.listCues(identity, languages)).isEmpty())
        } finally { s.release() }
    }


    @Test fun seekCannotClearThenBeOverwrittenByAlreadySelectedTemporaryTarget() = runTest {
        val repo = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val producer = object : Producer() {
            override suspend fun processAhead(request: RecordedListenRequest, onCue: suspend (RecordedListenCue) -> Unit): RecordedListenProgress {
                if (++calls == 1) onCue(provisional().copy(textTgt = "临时译文"))
                return RecordedListenProgress(15000, if (calls == 1) 15000 else 0, calls == 1, false)
            }
        }
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val selected = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val seekStarted = java.util.concurrent.CountDownLatch(1)
        val seekDone = java.util.concurrent.CountDownLatch(1)
        val s = ListenTranslateSession(repo, producer, this, identity, monotonicTimeMs = {
            if (armed.compareAndSet(true, false)) {
                selected.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            0L
        })
        try {
            start(s); runCurrent()
            assertEquals("临时译文", s.uiState.value.activeCue!!.textTgt)
            // Reset readiness so its clock blocks refresh after selecting the temporary
            // cue but before the final UI update. No production-only test hook is needed.
            s.beginStartupTiming(); armed.set(true)
            val worker = Thread { s.onPlaybackTick(1000, 30000, true) }
            worker.start()
            assertTrue(selected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val seeker = Thread { seekStarted.countDown(); s.onSeek(1000); seekDone.countDown() }
            seeker.start()
            assertTrue(seekStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("clear and UI publication must share an atomic boundary",
                seekDone.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            resume.countDown(); worker.join(5000); seeker.join(5000)
            assertFalse(worker.isAlive); assertFalse(seeker.isAlive)
            assertEquals("", s.uiState.value.activeCue!!.textTgt)
            assertEquals("待確認の原文", s.uiState.value.overlayText)
        } finally { resume.countDown(); s.release() }
    }

}
