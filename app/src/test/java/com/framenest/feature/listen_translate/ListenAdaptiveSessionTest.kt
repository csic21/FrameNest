package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenAdaptiveSessionTest {
    private val identity = PlaybackIdentity("server", "media", "japanese.mkv")
    private val languages = ListenLanguagePair("ja", "zh")

    private abstract class AdaptiveEngine : ListenTranslateEngine {
        override val asrModelId = "asr-test|sentence-v1"
        override val mtModelId = "mt-test"
        override val adaptiveMaxSpanMs = 12_000L
    }

    @Test fun adjacentJapaneseUnitsResumeAtActualEndsAndRtfUsesConsumedAudio() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val requested = mutableListOf<Pair<Long, Long>>()
        var now = 0L
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                val actualEnd = when (startMs) { 0L -> 4_200L; 4_200L -> 7_800L; else -> awaitCancellation() }
                now += (actualEnd - startMs) / 2L
                val text = if (startMs == 0L) "今日は天気がいいので、散歩に行きましょう。" else "でも、午後から雨が降るかもしれません。"
                return ListenWindowResult(text, "完整句子的翻译", processedThroughMs = actualEnd)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity, monotonicTimeMs = { now })
        session.setSourceLang("ja")
        session.onPlaybackTick(0L, 12_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 12_000L, 4_200L to 12_000L, 7_800L to 12_000L), requested)
        assertEquals(7_800L, session.uiState.value.coveredUntilMs)
        assertEquals(7_800L, session.uiState.value.preparedAheadMs)
        assertEquals(0.5, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        assertEquals(0.5, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        assertEquals(listOf(ListenCompletedInterval(0L, 7_800L)),
            ListenCompletedIntervals.fromCues(repository.listCues(identity, languages)))
        assertEquals(7_800L, repository.getJob(identity, languages)!!.coveredUntilMs)
        session.release()
    }

    @Test fun oneMillisecondGapIsProcessedWithoutOverwritingFutureCachedSpeech() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("", "", blankReason = ListenBlankReason.NearSilence, processedThroughMs = endMs)
            }
        }
        repository.ensureJob(identity, languages, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
        repository.upsertCueForExistingJob(identity, languages, 0L, 4_200L, "first", "第一句")
        repository.upsertCueForExistingJob(identity, languages, 4_201L, 12_000L, "future", "后一句")
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(0L, 12_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(4_200L to 4_201L), requested)
        assertEquals(12_000L, session.uiState.value.preparedAheadMs)
        assertEquals("future", repository.listCues(identity, languages).last().textSrc)
        session.release()
    }

    @Test fun uncachedCurrentGapStopsAtFutureBoundaryAndPausedSessionStopsAfterCurrentUnit() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("current", "当前", processedThroughMs = if (startMs == 0L) 5_000L else endMs)
            }
        }
        repository.ensureJob(identity, languages, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
        repository.upsertCueForExistingJob(identity, languages, 6_250L, 9_000L, "future", "未来")
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(3_000L, 9_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 6_250L), requested)
        assertEquals(5_000L, session.uiState.value.coveredUntilMs)
        assertEquals(2_000L, session.uiState.value.preparedAheadMs)
        session.onSeek(5_500L)
        runCurrent()
        assertEquals(listOf(0L to 6_250L, 5_000L to 6_250L), requested)
        assertEquals(3_500L, session.uiState.value.preparedAheadMs)
        session.release()
    }

    @Test fun sourceFirstFailureNeverCompletesBilingualOrSourceOnlyCoverage() = runTest {
        for (sourceOnly in listOf(false, true)) {
            val repository = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = object : AdaptiveEngine() {
                override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("progress required")
                override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                    onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
                    val source = ListenWindowResult("日本語の文章です。", "", processedThroughMs = 4_200L)
                    onSourceRecognized(source)
                    delay(100L)
                    return source.copy(retryableErrorMessage = "stage failed")
                }
            }
            val session = ListenTranslateSession(repository, engine, this, identity)
            session.setSourceLang("ja")
            if (sourceOnly) session.setDisplayMode(ListenDisplayMode.SourceOnly)
            session.onPlaybackTick(0L, 12_000L, playing = true)
            session.setEnabled(true)
            runCurrent()
            assertEquals("日本語の文章です。", session.uiState.value.overlayText)
            assertEquals(0L, session.uiState.value.preparedAheadMs)
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            advanceTimeBy(100L)
            runCurrent()
            val cues = repository.listCues(identity, session.uiState.value.languages)
            assertEquals(4_200L, cues.single().endMs)
            assertEquals(ListenCoverageRev.PROVISIONAL_SOURCE, cues.single().rev)
            assertTrue(ListenCompletedIntervals.fromCues(cues, requireTranslation = !sourceOnly).isEmpty())
            assertEquals(0L, session.uiState.value.preparedAheadMs)
            assertEquals(0L, repository.getJob(identity, session.uiState.value.languages)!!.coveredUntilMs)
            assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
            assertNull(session.uiState.value.adaptationRealtimeFactor)
            session.release()
        }
    }

    @Test fun successfulSourceOnlyFinalPromotesProvisionalSpanWithoutTranslation() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("progress required")
            override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
                val source = ListenWindowResult("原文", "", processedThroughMs = 4_200L)
                onSourceRecognized(source)
                delay(100L)
                return source
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.setDisplayMode(ListenDisplayMode.SourceOnly)
        session.onPlaybackTick(0L, 12_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(4_200L, session.uiState.value.preparedAheadMs)
        assertEquals(4_200L, session.uiState.value.coveredUntilMs)
        assertEquals(1, repository.listCues(identity, session.uiState.value.languages).single().rev)
        assertEquals("source-only-v1", repository.getJob(identity, session.uiState.value.languages)!!.mtModel)
        session.release()
    }

    @Test fun invalidConsumedEndsCannotPublishOrAdvanceProgress() = runTest {
        for (invalidEnd in listOf(-1L, 0L, 12_001L, Long.MAX_VALUE)) {
            val repository = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = object : AdaptiveEngine() {
                override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String) =
                    ListenWindowResult("source", "target", processedThroughMs = invalidEnd)
            }
            val session = ListenTranslateSession(repository, engine, this, identity)
            session.setSourceLang("ja")
            session.onPlaybackTick(0L, 12_000L, playing = true)
            session.setEnabled(true)
            runCurrent()
            assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
            assertTrue(repository.listCues(identity, languages).isEmpty())
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            assertNull(session.uiState.value.effectiveRealtimeFactor)
            session.release()
        }
    }

    @Test fun finalCannotChangePreviouslyPublishedSourceSpan() = runTest {
        for (changeCueBounds in listOf(false, true)) {
            val repository = ListenTranslateRepository(FakeListenTranslateDao())
            val engine = object : AdaptiveEngine() {
                override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("progress required")
                override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                    onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
                    val source = ListenWindowResult("source", "", processedThroughMs = 4_200L)
                    onSourceRecognized(source)
                    return if (changeCueBounds) source.copy(textTgt = "target", cueStartMs = 1L)
                        else source.copy(textTgt = "target", processedThroughMs = 7_800L)
                }
            }
            val session = ListenTranslateSession(repository, engine, this, identity)
            session.setSourceLang("ja")
            session.onPlaybackTick(0L, 12_000L, playing = false)
            session.setEnabled(true)
            runCurrent()
            assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
            val cue = repository.listCues(identity, languages).single()
            assertEquals(4_200L, cue.endMs)
            assertEquals("", cue.textTgt)
            assertTrue(ListenCompletedIntervals.fromCues(listOf(cue)).isEmpty())
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            session.release()
        }
    }

    @Test fun knownEofClipsRequestsAndNoWorkStartsAtOrBeyondIt() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("", "", blankReason = ListenBlankReason.NearSilence, processedThroughMs = endMs)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(6_000L, 7_800L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 7_800L), requested)
        assertEquals(1_800L, session.uiState.value.preparedAheadMs)
        session.onSeek(7_800L)
        runCurrent()
        assertEquals(1, requested.size)
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        session.onSeek(9_000L)
        runCurrent()
        assertEquals(1, requested.size)
        session.release()
    }

    @Test fun emptyDecodeNeverCertifiesReadAheadAsCompletedAudio() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String) =
                ListenWindowResult("", "", blankReason = ListenBlankReason.EmptyPcm)
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.setEnabled(true)
        runCurrent()
        assertEquals(ListenBlankReason.EmptyPcm, session.uiState.value.lastBlankReason)
        assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
        assertTrue(repository.listCues(identity, languages).isEmpty())
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        session.release()
    }

    @Test fun staleSourceAndFinalCallbacksAfterSeekBackgroundDisableAndExitCannotPublish() = runTest {
        for (action in listOf<(ListenTranslateSession) -> Unit>(
            { it.onSeek(6_000L) }, { it.setForeground(false) }, { it.setEnabled(false) }, { it.release() },
        )) {
            val repository = ListenTranslateRepository(FakeListenTranslateDao())
            var attempts = 0
            val engine = object : AdaptiveEngine() {
                override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("progress required")
                override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                    onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
                    attempts++
                    if (attempts > 1) awaitCancellation()
                    try { awaitCancellation() } catch (_: CancellationException) {
                        return withContext(NonCancellable) {
                            val source = ListenWindowResult("stale", "", processedThroughMs = 4_200L)
                            try { onSourceRecognized(source) } catch (_: CancellationException) { }
                            source.copy(textTgt = "stale translation")
                        }
                    }
                }
            }
            val session = ListenTranslateSession(repository, engine, this, identity)
            session.setSourceLang("ja")
            session.onPlaybackTick(0L, 12_000L, playing = false)
            session.setEnabled(true)
            runCurrent()
            action(session)
            runCurrent()
            assertTrue(repository.listCues(identity, languages).isEmpty())
            assertEquals(0L, session.uiState.value.coveredUntilMs)
            assertNull(session.uiState.value.effectiveRealtimeFactor)
            session.release()
        }
    }

    @Test fun actualCoverageMarkerStopsAtConsumedEndEvenWhenCueIsNarrower() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String) =
                ListenWindowResult("source", "target", cueStartMs = 500L, cueEndMs = 4_000L, processedThroughMs = 4_200L)
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(0L, 12_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        val cues = repository.listCues(identity, languages)
        assertEquals(2, cues.size)
        assertEquals(4_200L, cues.single { it.textSrc.isBlank() }.endMs)
        assertEquals(4_000L, cues.single { it.textSrc.isNotBlank() }.endMs)
        assertEquals(4_200L, session.uiState.value.preparedAheadMs)
        session.release()
    }

    @Test fun activationAt3500StartsAtZeroAndFarSeekRetainsOnlyBoundedLookback() = runTest {
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("このイラストを見てください。", "请看这幅插画。",
                    processedThroughMs = if (startMs == 0L) 4_200L else 125_800L)
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(3_500L, 180_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 12_000L), requested)
        assertEquals("このイラストを見てください。\n请看这幅插画。", session.uiState.value.overlayText)
        assertEquals(700L, session.uiState.value.preparedAheadMs)
        session.onSeek(125_000L)
        runCurrent()
        assertEquals(listOf(0L to 12_000L, 114_000L to 126_000L), requested)
        assertEquals(800L, session.uiState.value.preparedAheadMs)
        session.release()
    }

    @Test fun lookbackUnitsBeforePlayheadAreNeverSelectedAsCurrentSubtitle() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult = error("progress required")
            override suspend fun processWindowWithProgress(startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit): ListenWindowResult {
                requested += startMs to endMs
                val source = if (startMs == 0L) ListenWindowResult("past unit", "", processedThroughMs = 3_500L)
                    else ListenWindowResult("current unit", "", processedThroughMs = 4_200L)
                onSourceRecognized(source)
                if (startMs == 0L) delay(100L)
                return source.copy(textTgt = if (startMs == 0L) "过去" else "当前")
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(3_500L, 20_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals("", session.uiState.value.overlayText)
        assertNull(session.uiState.value.activeCue)
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf(0L to 12_000L, 3_500L to 15_500L), requested)
        assertEquals("current unit\n当前", session.uiState.value.overlayText)
        assertEquals(700L, session.uiState.value.preparedAheadMs)
        session.release()
    }

    @Test fun cacheClearRevokesInFlightAdaptiveCommitWithoutRecreatingJob() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                delay(100L)
                return ListenWindowResult("stale", "stale translation", processedThroughMs = 4_200L)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(0L, 12_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        repository.purgeAll()
        advanceTimeBy(100L)
        runCurrent()
        assertNull(repository.getJob(identity, languages))
        assertTrue(repository.listCues(identity, languages).isEmpty())
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        assertFalse(session.uiState.value.isProcessing)
        session.release()
    }

    @Test fun segmentationPolicyIdentityInvalidatesOldFixedWindowCache() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        repository.ensureJob(identity, languages, asrModel = "asr-test", mtModel = "mt-test")
        repository.upsertCueForExistingJob(identity, languages, 0L, 3_000L, "old fragment", "旧片段")
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("new complete unit", "完整句", processedThroughMs = 4_200L)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.onPlaybackTick(0L, 12_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 12_000L), requested)
        assertEquals("new complete unit", repository.listCues(identity, languages).single().textSrc)
        assertEquals(engine.asrModelId, repository.getJob(identity, languages)!!.asrModel)
        session.release()
    }

    @Test fun shorterResultWithoutAdaptiveOptInFailsInsteadOfBusyLoopingFixedWindow() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "legacy"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                calls++
                return ListenWindowResult("source", "target", processedThroughMs = 2_000L)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setSourceLang("ja")
        session.setEnabled(true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
        assertTrue(repository.listCues(identity, languages).isEmpty())
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test fun languageGateKeepsOtherLanguagesOnLegacyThreeSecondWindows() = runTest {
        val requested = mutableListOf<Pair<Long, Long>>()
        val engine = object : AdaptiveEngine() {
            override fun adaptiveMaxSpanMsFor(sourceLang: String): Long? = if (sourceLang == "ja") adaptiveMaxSpanMs else null
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                requested += startMs to endMs
                return ListenWindowResult("source", "target")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity)
        session.onPlaybackTick(0L, 12_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L to 3_000L), requested)
        session.setSourceLang("ja")
        runCurrent()
        assertEquals(listOf(0L to 3_000L, 0L to 12_000L), requested)
        session.release()
    }
}
