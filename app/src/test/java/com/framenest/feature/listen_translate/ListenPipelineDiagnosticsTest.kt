package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.listen_translate.ListenLanguagePair
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
class ListenPipelineDiagnosticsTest {
    private val identity = PlaybackIdentity("test", "media", "movie.mkv")

    @Test
    fun stageTimerKeepsMissingStagesNullAndMeasuresFailures() = runTest {
        var now = 10L
        val timer = ListenStageTimer { now }
        timer.measure(ListenPipelineStage.PcmRead) { now += 23L }
        assertEquals(23L, timer.timings.pcmReadMs)
        assertNull(timer.timings.asrMs)
        try {
            timer.measure(ListenPipelineStage.Asr) {
                now += 47L
                throw IllegalStateException("failed")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(47L, timer.timings.asrMs)
        assertNull(timer.timings.translationMs)
    }

    @Test
    fun elapsedAndMediaLatenessNeverBecomeNegative() {
        assertEquals(0L, elapsedListenTimeMs(100L, 90L))
        assertEquals(0L, listenCueLatenessMs(1_000L, 3_000L))
        assertEquals(500L, listenCueLatenessMs(1_500L, 1_000L))
    }

    @Test
    fun sourceAndTranslationHaveSeparateReadyTimesAndPlayerLateness() = runTest {
        val session = ListenTranslateSession(
            ListenTranslateRepository(FakeListenTranslateDao()), phasedEngine(), this, identity,
            monotonicTimeMs = { testScheduler.currentTime },
        )
        session.onPlaybackTick(500L, 3_000L, playing = false, playbackRate = 2f)
        session.setEnabled(true)
        advanceTimeBy(100L)
        runCurrent()
        val source = session.uiState.value.diagnostics!!
        assertEquals(100L, source.sourceReadyMs)
        assertEquals(500L, source.sourceLatenessMs)
        assertNull(source.translationReadyMs)
        assertNull(source.stages.translationMs)
        assertEquals(500L, source.windowBacklogMs)
        assertEquals(2f, source.playbackRate)
        assertFalse(source.completed)
        session.onPlaybackTick(1_500L, 3_000L, playing = false, playbackRate = 2f)
        advanceTimeBy(1_000L)
        runCurrent()
        val final = session.uiState.value.diagnostics!!
        assertEquals(100L, final.sourceReadyMs)
        assertEquals(1_100L, final.translationReadyMs)
        assertEquals(1_500L, final.translationLatenessMs)
        assertEquals(1_100L, final.totalMs)
        assertEquals(1_000L, final.stages.translationMs)
        assertTrue(final.completed)
        session.release()
        assertNull(session.uiState.value.diagnostics)
    }

    @Test
    fun generationChangeClearsDiagnosticsAndRejectsLateMeasurements() = runTest {
        val engine = object : DiagnosticEngine() {
            override suspend fun processWindowWithProgress(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
                onSourceRecognized: suspend (ListenWindowResult) -> Unit,
            ): ListenWindowResult {
                withContext(NonCancellable) {
                    delay(100L)
                    try {
                        onSourceRecognized(ListenWindowResult("stale", "", stageTimings = ListenStageTimings(asrMs = 99L)))
                    } catch (_: CancellationException) { }
                }
                return ListenWindowResult("stale", "stale")
            }
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity)
        session.setEnabled(true)
        runCurrent()
        assertNotNull(session.uiState.value.diagnostics)
        session.setForeground(false)
        assertNull(session.uiState.value.diagnostics)
        advanceTimeBy(100L)
        runCurrent()
        assertNull(session.uiState.value.diagnostics)
        session.release()
    }

    @Test
    fun failedStagePublishesTimingWithoutPretendingTranslationCompleted() = runTest {
        val engine = object : DiagnosticEngine() {
            override suspend fun processWindow(
                startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            ): ListenWindowResult = throw ListenPipelineException(
                IllegalStateException("decode failed"), ListenStageTimings(pcmReadMs = 400L),
            )
        }
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine, this, identity)
        session.setEnabled(true)
        runCurrent()
        val sample = session.uiState.value.diagnostics!!
        assertEquals(400L, sample.stages.pcmReadMs)
        assertNull(sample.translationReadyMs)
        assertFalse(sample.completed)
        assertTrue(sample.failed)
        assertTrue(formatListenDiagnostics(sample).contains("失败阶段耗时"))
        session.release()
    }

    @Test
    fun experimentIsDefaultOffAndCannotChangeDuringWorkOrPreparation() = runTest {
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), phasedEngine(), this, identity)
        assertFalse(session.uiState.value.experimentalSilenceGate)
        session.setInstallingModels(true)
        session.setExperimentalSilenceGate(true)
        assertFalse(session.uiState.value.experimentalSilenceGate)
        session.setInstallingModels(false)
        session.setExperimentalSilenceGate(true)
        assertTrue(session.uiState.value.experimentalSilenceGate)
        session.setEnabled(true)
        runCurrent()
        session.setExperimentalSilenceGate(false)
        assertTrue(session.uiState.value.experimentalSilenceGate)
        session.release()
    }

    @Test
    fun newBaselineInvalidatesLegacyRmsSilenceIdentity() {
        assertEquals("sensevoice-v1|digital-zero-v2", listenAsrCacheModelId("sensevoice-v1", false))
        assertEquals("sensevoice-v1|silence-gate-v1", listenAsrCacheModelId("sensevoice-v1", true))
    }

    @Test
    fun strategyChangesInvalidateOldCoverageInBothDirections() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val langs = ListenLanguagePair("en", "zh")
        for (experimental in listOf(false, true, false)) {
            val model = listenAsrCacheModelId("sensevoice", experimental)
            repository.ensureJob(identity, langs, contentKey = "file", asrModel = model, mtModel = "mt")
            assertTrue(repository.listCues(identity, langs).isEmpty())
            repository.upsertCueForExistingJob(identity, langs, 0L, 3_000L, "", "", contentKey = "file", asrModel = model, mtModel = "mt")
            assertEquals(1, repository.listCues(identity, langs).size)
        }
    }

    @Test
    fun formattedDiagnosticsUseNoCueOrPathFields() {
        val text = formatListenDiagnostics(ListenPipelineDiagnostics())
        assertTrue(text.contains("ASR —"))
        assertTrue(text.contains("媒体时间"))
        assertFalse(text.contains("NaN"))
    }

    private fun phasedEngine(): ListenTranslateEngine = object : DiagnosticEngine() {
        override suspend fun processWindowWithProgress(
            startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
            onSourceRecognized: suspend (ListenWindowResult) -> Unit,
        ): ListenWindowResult {
            delay(100L)
            val source = ListenWindowResult("source", "", stageTimings = ListenStageTimings(asrMs = 100L))
            onSourceRecognized(source)
            delay(1_000L)
            return source.copy(textTgt = "translated", stageTimings = source.stageTimings!!.copy(translationMs = 1_000L))
        }
    }

    private abstract class DiagnosticEngine : ListenTranslateEngine {
        override val asrModelId = "diagnostic-test"
        override val mtModelId = "diagnostic-test"
        override suspend fun processWindow(
            startMs: Long, endMs: Long, sourceLang: String, targetLang: String,
        ): ListenWindowResult = error("override for test")
    }
}
