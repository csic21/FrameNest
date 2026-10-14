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
}
