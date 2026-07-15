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
}
