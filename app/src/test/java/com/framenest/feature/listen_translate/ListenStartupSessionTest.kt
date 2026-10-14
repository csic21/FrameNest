package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenStartupSessionTest {
    private val identity = PlaybackIdentity("test", "media", "startup.mkv")
    private val langs = ListenLanguagePair("en", "zh")
    private fun engine(waitMs: Long = 0L) = object : ListenTranslateEngine {
        override val asrModelId = "test-asr"
        override val mtModelId = "test-mt"
        override suspend fun processWindow(startMs: Long, endMs: Long, sourceLang: String, targetLang: String): ListenWindowResult {
            delay(waitMs)
            return ListenWindowResult("source", "target", stageTimings = ListenStageTimings(asrMs = waitMs))
        }
    }

    @Test fun preparationStartSurvivesEnableAndMilestonesIncludeItsWait() = runTest {
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine(100L),
            this, identity, monotonicTimeMs = { testScheduler.currentTime })
        val token = session.beginStartupTiming()
        advanceTimeBy(300L)
        session.recordAsrPreparationTiming(token, 200L)
        session.onPlaybackTick(0L, 3000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(200L, session.uiState.value.startupTiming?.asrPreparationMs)
        assertEquals(400L, session.uiState.value.startupTiming?.firstBilingualPublishedMs)
        assertEquals(400L, session.uiState.value.startupTiming?.firstBilingualSelectedMs)
        session.release()
        assertNull(session.uiState.value.startupTiming)
    }

    @Test fun explicitPreparationOfWarmCacheRecordsReadyBeforeSelection() = runTest {
        val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine(),
            this, identity, monotonicTimeMs = { testScheduler.currentTime })
        session.onPlaybackTick(0L, 3000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        advanceTimeBy(100L)
        session.setPreparingSubtitles(true)
        assertEquals(0L, session.uiState.value.startupTiming?.firstBilingualPublishedMs)
        assertEquals(0L, session.uiState.value.startupTiming?.firstBilingualSelectedMs)
        assertNull(session.uiState.value.startupTiming?.firstGeneratedBilingualStages)
        session.release()
    }

    @Test fun distantCacheDoesNotClaimCurrentStartupReady() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = engine(1000L)
        repository.ensureJob(identity, langs, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
        repository.upsertCueForExistingJob(identity, langs, 9000L, 12000L, "source", "target")
        val session = ListenTranslateSession(repository, engine, this, identity,
            monotonicTimeMs = { testScheduler.currentTime })
        session.onPlaybackTick(0L, 12000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertNull(session.uiState.value.startupTiming?.firstBilingualPublishedMs)
        assertNull(session.uiState.value.startupTiming?.firstBilingualSelectedMs)
        session.release()
    }

    @Test fun laterTickEnteringWarmCueRecordsReadinessBeforeSelection() = runTest {
        val repository = ListenTranslateRepository(FakeListenTranslateDao())
        val engine = engine(10000L)
        repository.ensureJob(identity, langs, asrModel = engine.asrModelId, mtModel = engine.mtModelId)
        repository.upsertCueForExistingJob(identity, langs, 1000L, 3000L, "source", "target")
        val session = ListenTranslateSession(repository, engine, this, identity,
            monotonicTimeMs = { testScheduler.currentTime })
        session.onPlaybackTick(0L, 3000L, playing = false)
        session.setEnabled(true)
        runCurrent()
        assertNull(session.uiState.value.startupTiming?.firstBilingualPublishedMs)
        advanceTimeBy(1000L)
        session.onPlaybackTick(1000L, 3000L, playing = false)
        assertEquals(1000L, session.uiState.value.startupTiming?.firstBilingualPublishedMs)
        assertEquals(1000L, session.uiState.value.startupTiming?.firstBilingualSelectedMs)
        session.release()
    }

    @Test fun sourceOnlyAndSameLanguageNeverClaimBilingual() = runTest {
        for (sourceOnly in listOf(true, false)) {
            val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine(),
                this, identity, monotonicTimeMs = { testScheduler.currentTime })
            if (sourceOnly) session.setDisplayMode(ListenDisplayMode.SourceOnly) else session.setTargetLang("en")
            session.onPlaybackTick(0L, 3000L, playing = false)
            session.setEnabled(true)
            runCurrent()
            assertEquals(0L, session.uiState.value.startupTiming?.firstSourceSelectedMs)
            assertNull(session.uiState.value.startupTiming?.firstBilingualPublishedMs)
            assertNull(session.uiState.value.startupTiming?.firstBilingualSelectedMs)
            assertNull(session.uiState.value.startupTiming?.firstGeneratedBilingualStages)
            session.release()
        }
    }

    @Test fun seekBackgroundDisableAndExitDiscardStalePreparationCallbacks() = runTest {
        for (action in listOf<(ListenTranslateSession) -> Unit>(
            { it.onSeek(1000L) }, { it.setForeground(false) },
            { it.setEnabled(false) }, { it.release() },
        )) {
            val session = ListenTranslateSession(ListenTranslateRepository(FakeListenTranslateDao()), engine(1000L),
                this, identity, monotonicTimeMs = { testScheduler.currentTime })
            val token = session.beginStartupTiming()
            session.setEnabled(true)
            runCurrent()
            action(session)
            session.recordAsrPreparationTiming(token, 200L)
            session.recordFirstPcmTiming(token, testScheduler.currentTime)
            assertNull(session.uiState.value.startupTiming)
            session.release()
        }
    }
}
