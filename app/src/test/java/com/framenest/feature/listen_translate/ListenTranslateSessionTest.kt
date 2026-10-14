package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTranslateSessionTest {

    @Test
    fun explicitPausedPreparationStopsAtContiguousTargetAndNeverStartsPlayback() = runTest {
        val calls = mutableListOf<Long>()
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                calls += startMs
                return ListenWindowResult("source", "target")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "prepare.mkv"))
        session.onPlaybackTick(0L, 60_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L), calls)
        session.setPreparingSubtitles(true)
        runCurrent()
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), calls)
        assertEquals(12_000L, session.uiState.value.preparedAheadMs)
        assertFalse(session.uiState.value.preparingSubtitles)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(4, calls.size)
        session.release()
    }

    @Test
    fun sourceOnlyUsesIndependentCoverageAndModeSwitchRejectsLateTranslation() = runTest {
        val pairs = mutableListOf<String>()
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                pairs += "$sourceLang:$targetLang"
                if (targetLang == "zh") delay(5_000L)
                return ListenWindowResult("source", if (targetLang == sourceLang) "source" else "译文")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "mode.mkv"))
        session.onPlaybackTick(0L, 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        session.setDisplayMode(ListenDisplayMode.SourceOnly)
        runCurrent()
        assertEquals(listOf("en:zh", "en:en"), pairs)
        assertEquals("source", session.uiState.value.overlayText)
        assertEquals(3_000L, session.uiState.value.preparedAheadMs)
        session.setDisplayMode(ListenDisplayMode.Bilingual)
        runCurrent()
        assertEquals(0L, session.uiState.value.preparedAheadMs)
        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals("source\n译文", session.uiState.value.overlayText)
        session.release()
    }

    @Test
    fun seekAndBackgroundCancelExplicitPreparation() = runTest {
        val calls = mutableListOf<Long>()
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                calls += startMs
                delay(100L)
                return ListenWindowResult("source", "target")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "cancel.mkv"))
        session.onPlaybackTick(0L, 60_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        session.setPreparingSubtitles(true)
        runCurrent()
        session.onSeek(30_000L)
        runCurrent()
        assertFalse(session.uiState.value.preparingSubtitles)
        advanceTimeBy(101L)
        runCurrent()
        assertEquals(30_000L, calls.last())
        session.setPreparingSubtitles(true)
        runCurrent()
        session.setForeground(false)
        val count = calls.size
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(count, calls.size)
        assertFalse(session.uiState.value.preparingSubtitles)
        session.release()
    }

    @Test
    fun actualRevOneCoverageNeverHidesSameStartShortSpeech() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val identity = PlaybackIdentity("server", "media", "short.mkv")
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String) =
                ListenWindowResult("short speech", "短句", cueStartMs = 0L, cueEndMs = 1_800L)
        }
        val session = ListenTranslateSession(repository, engine, this, identity, pollIntervalMs = 60_000L)
        session.onPlaybackTick(1_000L, 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals("short speech\n短句", session.uiState.value.overlayText)
        val cues = repository.listCues(identity, session.uiState.value.languages)
        assertEquals(2, cues.size)
        assertTrue(cues.all { it.rev == 1 })
        assertEquals("short speech", ListenTranslateWindows.cueAt(cues.reversed(), 1_000L)?.textSrc)
        session.release()
    }

    @Test
    fun legacyBlankAtCurrentPlayhead_isRecoveredAndCounted() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val languages = ListenLanguagePair("ja", "zh")
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        repository.ensureJob(
            identity = identity,
            languages = languages,
            asrModel = "asr-test",
            mtModel = "mt-test",
        )
        repository.upsertCue(
            identity,
            languages,
            0L,
            3_000L,
            "",
            "",
            rev = ListenCoverageRev.LEGACY_BLANK,
        )
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult {
                calls++
                return ListenWindowResult("こんにちは", "你好")
            }
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 60_000L,
        )

        session.onPlaybackTick(1_000L, 10_000L, playing = false)
        session.setSourceLang("ja")
        session.setEnabled(true)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(1, session.uiState.value.generatedCueCount)
        assertEquals("こんにちは\n你好", session.uiState.value.overlayText)
        session.release()
    }

    @Test
    fun translationFailure_persistsSourceCue_andKeepsWindowRetryable() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val languages = ListenLanguagePair("en", "zh")
        val dao = FakeListenTranslateDao()
        val repository = ListenTranslateRepository(dao)
        val engine = object : ListenTranslateEngine {
            override val asrModelId: String = "asr-test"
            override val mtModelId: String = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ) = ListenWindowResult(
                textSrc = "recognized speech",
                textTgt = "",
                cueStartMs = startMs,
                cueEndMs = endMs,
                retryableErrorMessage = "translation unavailable",
            )
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 60_000L,
        )

        session.onPlaybackTick(positionMs = 0L, durationMs = 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()

        val cues = repository.listCues(identity, languages)
        assertEquals("recognized speech", cues.single().textSrc)
        assertEquals("", cues.single().textTgt)
        assertTrue(ListenTranslateWindows.needsFill(cues, 0L, 3_000L))
        assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
        assertEquals("translation unavailable", session.uiState.value.errorMessage)
        assertFalse(session.uiState.value.isProcessing)
        assertEquals(1, dao.getJobCount)
        assertEquals(1, dao.upsertJobCount)
        assertEquals(1, dao.touchJobCount)
        assertEquals(1, dao.updateJobProgressCount)

        session.release()
    }

    @Test
    fun disablingAfterPreparationFailure_preservesFailureReason() = runTest {
        val session = ListenTranslateSession(
            repository = ListenTranslateRepository(FakeListenTranslateDao()),
            engine = StubListenTranslateEngine(),
            scope = this,
            identity = PlaybackIdentity("server", "media", "movie.mkv"),
        )

        session.setInstallingModels(
            installing = false,
            message = "听译未启动",
            error = "模型下载失败",
        )
        session.setEnabled(false)

        assertEquals("模型下载失败", session.uiState.value.errorMessage)
        session.release()
    }

    @Test
    fun reopeningAfterTransientFailure_clearsBackoffAndRetriesCurrentWindow() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var disconnected = true
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult {
                calls++
                if (disconnected) error("Not connected")
                return ListenWindowResult("recovered", "已恢复")
            }
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 60_000L,
        )

        session.onPlaybackTick(positionMs = 0L, durationMs = 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
        assertEquals(1, calls)

        session.setEnabled(false)
        disconnected = false
        session.setEnabled(true)
        runCurrent()

        assertEquals(2, calls)
        assertEquals(ListenTranslateJobStatus.Partial, session.uiState.value.status)
        assertEquals("recovered\n已恢复", session.uiState.value.overlayText)
        assertEquals(null, session.uiState.value.errorMessage)
        session.release()
    }

    @Test
    fun fastPipeline_prefetchesContiguousThirtySecondCache() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val dao = FakeListenTranslateDao()
        val repository = ListenTranslateRepository(dao)
        val processed = mutableListOf<Pair<Long, Long>>()
        var monotonicMs = 0L
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult {
                processed += startMs to endMs
                monotonicMs += 1_500L // 0.5x realtime for a 3-second window.
                return ListenWindowResult(textSrc = "", textTgt = "")
            }
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 100L,
            monotonicTimeMs = { monotonicMs },
        )

        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        advanceTimeBy(1_200L)
        runCurrent()

        assertEquals((0L until 30_000L step 3_000L).toList(), processed.map { it.first })
        assertEquals(30_000L, session.uiState.value.coveredUntilMs)
        assertEquals(30_000L, session.uiState.value.prefetchLookAheadMs)
        assertEquals(1, dao.listCuesCount)
        assertEquals(1, dao.getJobCount)
        assertEquals(1, dao.upsertJobCount)
        assertEquals(0, dao.touchJobCount)
        assertEquals(10, dao.updateJobProgressCount)
        session.release()
    }

    @Test
    fun buffering_cancelsInFlightLookAhead() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val languages = ListenLanguagePair("en", "zh")
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        repository.ensureJob(
            identity = identity,
            languages = languages,
            asrModel = "asr-test",
            mtModel = "mt-test",
        )
        repository.upsertCue(
            identity,
            languages,
            0L,
            3_000L,
            "",
            "",
            rev = ListenCoverageRev.CONFIRMED_SILENCE,
        )
        var cancelled = false
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult = try {
                delay(10_000L)
                ListenWindowResult("late", "迟到")
            } catch (cause: CancellationException) {
                cancelled = true
                throw cause
            }
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 100L,
        )

        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        session.onPlaybackTick(100L, 60_000L, playing = true, buffering = true)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(0L, session.uiState.value.prefetchLookAheadMs)
        assertFalse(session.uiState.value.isProcessing)
        session.release()
    }

    @Test
    fun farSeek_cancelsOldLookAheadAndTargetsNewPosition() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val languages = ListenLanguagePair("en", "zh")
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        repository.ensureJob(
            identity = identity,
            languages = languages,
            asrModel = "asr-test",
            mtModel = "mt-test",
        )
        repository.upsertCue(
            identity,
            languages,
            0L,
            3_000L,
            "",
            "",
            rev = ListenCoverageRev.CONFIRMED_SILENCE,
        )
        val starts = mutableListOf<Long>()
        var cancellationCount = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"

            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult {
                starts += startMs
                return try {
                    delay(10_000L)
                    ListenWindowResult("late", "迟到")
                } catch (cause: CancellationException) {
                    cancellationCount++
                    throw cause
                }
            }
        }
        val session = ListenTranslateSession(
            repository = repository,
            engine = engine,
            scope = this,
            identity = identity,
            pollIntervalMs = 100L,
        )

        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        session.onPlaybackTick(30_000L, 60_000L, playing = true)
        runCurrent()

        assertEquals(listOf(3_000L, 30_000L), starts)
        assertEquals(1, cancellationCount)
        session.release()
    }
    @Test
    fun coldPreparationDoesNotDisableAheadButFullRtfRemainsVisible() = runTest {
        var now = 0L
        val starts = mutableListOf<Long>()
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                starts += startMs
                if (starts.size > 1) awaitCancellation()
                now += 30_600L
                return ListenWindowResult("s", "t", stageTimings = ListenStageTimings(
                    translationPreparationMs = 30_500L, translationPreparationWaitMs = 30_000L))
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "cold.mkv"), monotonicTimeMs = { now })
        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L, 3_000L), starts)
        assertEquals(30_000L, session.uiState.value.prefetchLookAheadMs)
        assertEquals(10.0, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        assertEquals(0.2, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        session.onPlaybackTick(100L, 60_000L, playing = true, playbackRate = 2f, buffering = true)
        runCurrent()
        assertEquals(0L, session.uiState.value.prefetchLookAheadMs)
        assertEquals(20.0, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        assertEquals(0.4, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        // Seek cancels stale work but retains throughput history, as before this change.
        session.onSeek(30_000L)
        runCurrent()
        assertEquals(0.4, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        session.setEnabled(false)
        session.setEnabled(true)
        runCurrent()
        assertNull(session.uiState.value.effectiveRealtimeFactor)
        assertNull(session.uiState.value.adaptationRealtimeFactor)
        session.release()
    }

    @Test
    fun slowRealWorkStillDisablesAheadAndPausedPreparationRemainsBounded() = runTest {
        var now = 0L
        val starts = mutableListOf<Long>()
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                starts += startMs
                now += 4_500L // Actual PCM/recognition/translation; no model-prepare wait.
                return ListenWindowResult("s", "t")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "slow.mkv"), monotonicTimeMs = { now })
        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(listOf(0L), starts)
        assertEquals(0L, session.uiState.value.prefetchLookAheadMs)
        assertEquals(1.5, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        session.onPlaybackTick(0L, 60_000L, playing = false)
        session.setPreparingSubtitles(true)
        runCurrent()
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), starts)
        assertFalse(session.uiState.value.preparingSubtitles)
        assertEquals(12_000L, session.uiState.value.preparedAheadMs)
        assertEquals(1.5, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        assertEquals(1.5, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        session.release()
    }

    @Test
    fun failureNeverSeedsFullOrAdaptationRtf() = runTest {
        var now = 0L
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                now += 30_000L
                return ListenWindowResult("s", "", retryableErrorMessage = "MT unavailable",
                    stageTimings = ListenStageTimings(translationPreparationWaitMs = 29_000L))
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "failure.mkv"), monotonicTimeMs = { now })
        session.setEnabled(true)
        runCurrent()
        assertEquals(ListenTranslateJobStatus.Failed, session.uiState.value.status)
        assertNull(session.uiState.value.effectiveRealtimeFactor)
        assertNull(session.uiState.value.adaptationRealtimeFactor)
        session.release()
    }

    @Test
    fun firstTranslationAfterSilentWindowStillExcludesItsColdWait() = runTest {
        var now = 0L
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "test"
            override val mtModelId = "mt"
            override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
                calls++
                if (calls == 1) {
                    now += 600L
                    return ListenWindowResult("", "", blankReason = ListenBlankReason.NearSilence)
                }
                if (calls > 2) awaitCancellation()
                now += 30_600L
                return ListenWindowResult("s", "t", stageTimings = ListenStageTimings(translationPreparationWaitMs = 30_000L))
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine,
            this, PlaybackIdentity("s", "m", "silent-intro.mkv"), monotonicTimeMs = { now })
        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        runCurrent()
        assertEquals(3, calls)
        assertEquals(2.65, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        assertEquals(0.2, session.uiState.value.adaptationRealtimeFactor!!, 0.0001)
        assertEquals(30_000L, session.uiState.value.prefetchLookAheadMs)
        session.release()
    }

}
