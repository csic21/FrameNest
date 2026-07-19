package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTranslateSessionTest {

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
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
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
    fun fastPipeline_prefetchesContiguousThirtySecondCache() = runTest {
        val identity = PlaybackIdentity("server", "media", "movie.mkv")
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
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
}
