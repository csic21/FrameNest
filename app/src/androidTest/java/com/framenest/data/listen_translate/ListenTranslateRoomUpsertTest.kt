package com.framenest.data.listen_translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.server.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListenTranslateRoomUpsertTest {

    @Test
    fun updatingJob_doesNotCascadeDeleteExistingCue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AppDatabase.createInMemory(context)
        try {
            val repository = ListenTranslateRepository(
                dao = database.listenTranslateDao(),
                timeSource = { 123L },
            )
            val identity = PlaybackIdentity("server", "media", "movie.mkv")
            val languages = ListenLanguagePair("en", "zh")

            repository.upsertCue(
                identity = identity,
                languages = languages,
                startMs = 0L,
                endMs = 3_000L,
                textSrc = "hello",
                textTgt = "你好",
            )
            repository.updateProgress(
                identity = identity,
                languages = languages,
                coveredUntilMs = 3_000L,
                status = ListenTranslateJobStatus.Partial,
            )

            val cues = repository.listCues(identity, languages)
            assertEquals(1, cues.size)
            assertEquals("hello", cues.single().textSrc)
        } finally {
            database.close()
        }
    }

    @Test
    fun existingJobFastPath_commitsSpeechCoverageAndProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AppDatabase.createInMemory(context)
        try {
            val repository = ListenTranslateRepository(
                dao = database.listenTranslateDao(),
                timeSource = { 456L },
            )
            val identity = PlaybackIdentity("server", "media", "fast.mkv")
            val languages = ListenLanguagePair("en", "zh")
            repository.ensureJob(
                identity = identity,
                languages = languages,
                contentKey = "content",
                asrModel = "asr",
                mtModel = "mt",
                status = ListenTranslateJobStatus.Partial,
            )

            repository.upsertCueForExistingJob(
                identity = identity,
                languages = languages,
                startMs = 0L,
                endMs = 2_000L,
                textSrc = "hello",
                textTgt = "你好",
                contentKey = "content",
                asrModel = "asr",
                mtModel = "mt",
            )
            repository.completeWindowCueForExistingJob(
                identity = identity,
                languages = languages,
                startMs = 0L,
                endMs = 3_000L,
                textSrc = "",
                textTgt = "",
                rev = 1,
                coveredUntilMs = 3_000L,
                durationMs = 60_000L,
                contentKey = "content",
                asrModel = "asr",
                mtModel = "mt",
            )

            val cues = repository.listCues(identity, languages)
            val job = repository.getJob(identity, languages)
            assertEquals(2, cues.size)
            assertEquals("hello", repository.cueAt(identity, languages, 1_000L)?.textSrc)
            assertEquals("hello", com.framenest.feature.listen_translate.ListenTranslateWindows.cueAt(cues, 1_000L)?.textSrc)
            assertEquals(3_000L, job?.coveredUntilMs)
            assertEquals(60_000L, job?.durationMs)
            assertEquals(ListenTranslateJobStatus.Partial, job?.status)
        } finally {
            database.close()
        }
    }
}
