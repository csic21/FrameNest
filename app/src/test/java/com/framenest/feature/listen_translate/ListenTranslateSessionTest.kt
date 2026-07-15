package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.FakeListenTranslateDao
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTranslateSessionTest {

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
}
