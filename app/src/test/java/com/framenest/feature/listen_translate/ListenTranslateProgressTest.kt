package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTranslateProgressTest {
    private val identity = PlaybackIdentity("test", "media", "movie.mkv")
    private val languages = ListenLanguagePair("en", "zh")

    @Test
    fun sourceIsVisibleAndDurableBeforeTranslationCompletes() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val session = ListenTranslateSession(repository, phasedEngine(), this, identity)
        session.onPlaybackTick(100L, 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()

        assertEquals("source", session.uiState.value.overlayText)
        assertTrue(session.uiState.value.isProcessing)
        assertEquals("source", repository.listCues(identity, languages).single().textSrc)
        assertTrue(ListenTranslateWindows.needsFill(repository.listCues(identity, languages), 0L, 3_000L))
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("source\n译文", session.uiState.value.overlayText)
        assertFalse(session.uiState.value.isProcessing)
        assertEquals(1, repository.listCues(identity, languages).size)
        session.release()
    }

    @Test
    fun backgroundCancelsTranslationAndReturnResumesWithoutLosingSource() = runTest {
        var calls = 0
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val session = ListenTranslateSession(repository, phasedEngine { calls++ }, this, identity)
        session.onPlaybackTick(100L, 3_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        session.setForeground(false)
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("source", session.uiState.value.overlayText)
        assertEquals("", repository.listCues(identity, languages).single().textTgt)
        assertTrue(session.uiState.value.enabled)
        assertFalse(session.uiState.value.isProcessing)

        session.setForeground(true)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("source\n译文", session.uiState.value.overlayText)
        session.release()
    }

    @Test
    fun enabledWhileBackgroundedDoesNotProcessUntilForeground() = runTest {
        var calls = 0
        val session = ListenTranslateSession(
            ListenTranslateRepository(FakeListenTranslateDao()), phasedEngine { calls++ }, this, identity,
        )
        session.setForeground(false)
        session.setEnabled(true)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(0, calls)
        session.setForeground(true)
        runCurrent()
        assertEquals(1, calls)
        session.release()
    }

    @Test
    fun smallExplicitSeekRejectsNonCooperativeLateSourceAndFinal() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val starts = mutableListOf<Long>()
        var rejectedLateCallback = false
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                starts += startMs
                if (startMs == 0L) {
                    withContext(NonCancellable) {
                        delay(1_000L)
                        try {
                            onSourceRecognized(ListenWindowResult("stale", ""))
                        } catch (_: CancellationException) {
                            rejectedLateCallback = true
                        }
                    }
                    return ListenWindowResult("stale", "过期")
                }
                return ListenWindowResult("current", "当前")
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.onPlaybackTick(0L, 9_000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        session.onSeek(3_000L) // Below the old 6s far-seek threshold.
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(rejectedLateCallback)
        assertEquals(listOf(0L, 3_000L), starts)
        assertEquals("current\n当前", session.uiState.value.overlayText)
        assertEquals(listOf("current"), repository.listCues(identity, languages).map { it.textSrc })
        session.release()
    }

    @Test
    fun releasedSessionNeverRestartsOrAcceptsLateSource() = runTest {
        var calls = 0
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val session = ListenTranslateSession(repository, phasedEngine { calls++ }, this, identity)
        session.setEnabled(true)
        runCurrent()
        session.release()
        session.setForeground(false)
        session.setForeground(true)
        session.setEnabled(true)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("", repository.listCues(identity, languages).single().textTgt)
    }

    @Test
    fun languageChangeRejectsLateCallbackFromPreviousPair() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        var rejected = false
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                if (targetLang == "zh") withContext(NonCancellable) {
                    delay(1_000L)
                    try {
                        onSourceRecognized(ListenWindowResult("old pair", ""))
                    } catch (_: CancellationException) {
                        rejected = true
                    }
                }
                return ListenWindowResult("source", targetLang)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setEnabled(true)
        runCurrent()
        session.setTargetLang("ja")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(rejected)
        assertTrue(repository.listCues(identity, languages).isEmpty())
        assertEquals("source\nja", session.uiState.value.overlayText)
        session.release()
    }

    @Test
    fun successfulWindowsHaveNoFixedIdleAndStayWithinHorizon() = runTest {
        val starts = mutableListOf<Long>()
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                starts += startMs
                delay(100L)
                return ListenWindowResult("", "", blankReason = ListenBlankReason.NearSilence)
            }
        }
        val session = ListenTranslateSession(
            ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity,
            monotonicTimeMs = { testScheduler.currentTime },
        )
        session.onPlaybackTick(0L, 60_000L, playing = true)
        session.setEnabled(true)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals((0L until 30_000L step 3_000L).toList(), starts)
        assertEquals(30_000L, session.uiState.value.coveredUntilMs)
        assertEquals(100.0 / 3_000.0, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(10, starts.size)
        session.onPlaybackTick(0L, 60_000L, playing = true, playbackRate = 2f)
        assertEquals(200.0 / 3_000.0, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        session.release()
    }

    @Test
    fun effectiveBudgetIncludesCacheCommitWork() = runTest {
        var clockMs = 0L
        val repository = ListenTranslateRepository(FakeListenTranslateDao()) {
            clockMs += 700L // Simulated cache commit work, separate from ASR/MT.
            0L
        }
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                clockMs += 100L
                return ListenWindowResult("source", "译文")
            }
        }
        val session = ListenTranslateSession(
            repository, engine, this, identity, monotonicTimeMs = { clockMs },
        )
        session.onPlaybackTick(0L, 3_000L, playing = false, playbackRate = 2f)
        session.setEnabled(true)
        runCurrent()
        // 100ms processing + two 700ms cache updates, consuming a 1500ms 2x budget.
        assertEquals(1.0, session.uiState.value.effectiveRealtimeFactor!!, 0.0001)
        session.release()
    }

    @Test
    fun switchingAudioVariantDuringTranslationKeepsOnlyNewVariant() = runTest {
        var calls = 0
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                val label = "track-${++calls}"
                onSourceRecognized(ListenWindowResult(label, ""))
                delay(1_000L)
                return ListenWindowResult(label, "translated-$label")
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity, contentKey = "file|audio=0")
        session.setEnabled(true)
        runCurrent()
        assertEquals("track-1", session.uiState.value.overlayText)
        session.setContentKey("file|audio=1")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("track-2\ntranslated-track-2", session.uiState.value.overlayText)
        assertEquals(listOf("track-2"), repository.listCues(identity, languages).map { it.textSrc })
        session.release()
    }

    @Test
    fun emptyTranslationIsRetryableWithoutBusyLoopOrFalseCoverage() = runTest {
        var calls = 0
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = object : ProgressEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                calls++
                return ListenWindowResult("source", "", cueStartMs = 500L, cueEndMs = 2_500L)
            }
        }
        val session = ListenTranslateSession(repository, engine, this, identity)
        session.setEnabled(true)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        assertTrue(ListenTranslateWindows.needsFill(repository.listCues(identity, languages), 0L, 3_000L))
        assertTrue(session.uiState.value.errorMessage!!.contains("翻译"))
        session.release()
    }

    @Test
    fun fullWindowEmptyTranslationDoesNotSpin() = runTest {
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"
            override suspend fun processWindow(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            ): ListenWindowResult {
                calls++
                check(calls < 3) { "Same incomplete window immediately retried" }
                return ListenWindowResult("source", "")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity)
        session.setEnabled(true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0L, session.uiState.value.coveredUntilMs)
        session.release()
    }

    @Test
    fun changingAudioVariantClearsPreviousCodecFailureBackoff() = runTest {
        var calls = 0
        val engine = object : ListenTranslateEngine {
            override val asrModelId = "asr-test"
            override val mtModelId = "mt-test"
            override suspend fun processWindow(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            ): ListenWindowResult {
                calls++
                if (calls == 1) throw UnsupportedOperationException("unsupported old audio codec")
                return ListenWindowResult("new track", "新音轨")
            }
        }
        val session = ListenTranslateSession(
            ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity,
            contentKey = "file|audio=0",
        )
        session.setEnabled(true)
        runCurrent()
        assertEquals(1, calls)
        session.setContentKey("file|audio=1")
        runCurrent()
        assertEquals(2, calls)
        assertEquals("new track\n新音轨", session.uiState.value.overlayText)
        session.release()
    }

    private fun phasedEngine(onCall: () -> Unit = {}) = object : ProgressEngine() {
        override suspend fun processWindowWithProgress(
            startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            onSourceRecognized: suspend (ListenWindowResult) -> Unit,
        ): ListenWindowResult {
            onCall()
            onSourceRecognized(ListenWindowResult("source", ""))
            delay(1_000L)
            return ListenWindowResult("source", "译文")
        }
    }

    private abstract class ProgressEngine : ListenTranslateEngine {
        override val asrModelId = "asr-test"
        override val mtModelId = "mt-test"
        override suspend fun processWindow(
            startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
        ): ListenWindowResult = error("The session must consume progress")
    }
}
