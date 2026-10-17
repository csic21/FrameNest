package com.framenest.data.listen_translate

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.server.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun recoveryCue(start: Long, end: Long, source: String, target: String, rev: Int = 1) =
        ListenTranslateCueEntity(serverId = "server", share = "media", path = "recovery.mkv",
            sourceLang = "ja", targetLang = "zh", startMs = start, endMs = end,
            textSrc = source, textTgt = target, rev = rev)

    private suspend fun withRecoveryDatabase(block: suspend (AppDatabase, ListenTranslateRepository) -> Unit) {
        val database = AppDatabase.createInMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val repository = ListenTranslateRepository(database.listenTranslateDao(), timeSource = { 123L })
            repository.ensureJob(PlaybackIdentity("server", "media", "recovery.mkv"), ListenLanguagePair("ja", "zh"))
            val dao = database.listenTranslateDao()
            dao.replaceCueForExistingJob(recoveryCue(2_400, 3_000, "図書", ""), 123)
            dao.replaceCueForExistingJob(recoveryCue(3_150, 3_750, "館です", ""), 123)
            block(database, repository)
        } finally {
            database.close()
        }
    }

    private suspend fun recover(database: AppDatabase) = database.listenTranslateDao().replaceEmptyTranslationRecovery(
        speech = recoveryCue(2_400, 3_750, "図書館です", "图书馆"),
        coverage = recoveryCue(0, 6_000, "", ""),
        seamMs = 3_000, durationMs = 9_000, updatedAtEpochMs = 456,
    )

    @Test
    fun emptyRecovery_removesBothRevisionOneSourcesAndPreservesThirdWindow() = runBlocking {
        withRecoveryDatabase { database, repository ->
            database.listenTranslateDao().replaceCueForExistingJob(recoveryCue(6_000, 9_000, "third", "第三句"), 123)
            assertNotNull(recover(database))
            val identity = PlaybackIdentity("server", "media", "recovery.mkv")
            val languages = ListenLanguagePair("ja", "zh")
            val cues = repository.listCues(identity, languages)
            assertEquals(3, cues.size)
            assertEquals("图书馆", repository.cueAt(identity, languages, 3_300)?.textTgt)
            assertEquals("第三句", repository.cueAt(identity, languages, 7_000)?.textTgt)
            assertTrue(cues.none { it.textSrc.isNotBlank() && it.textTgt.isBlank() })
            assertEquals(6_000L, repository.getJob(identity, languages)?.coveredUntilMs)
        }
    }

    @Test
    fun emptyRecovery_rechecksLateTranslatedOrCompletedNextWindowInsideTransaction() = runBlocking {
        for (kind in 0..2) {
            withRecoveryDatabase { database, repository ->
                val identity = PlaybackIdentity("server", "media", "recovery.mkv")
                val languages = ListenLanguagePair("ja", "zh")
                // The caller observed an eligible source-only pair before a competing writer.
                assertTrue(repository.listCues(identity, languages).all { it.textTgt.isBlank() })
                val inserted = CompletableDeferred<Unit>()
                val writer = launch {
                    val next = when (kind) {
                        0 -> recoveryCue(3_150, 3_750, "saved next", "已译")
                        1 -> recoveryCue(3_150, 3_750, "saved next", "已译", LISTEN_PROVISIONAL_SOURCE_REV)
                        else -> recoveryCue(3_000, 6_000, "", "")
                    }
                    database.listenTranslateDao().replaceCueForExistingJob(next, 234)
                    inserted.complete(Unit)
                }
                inserted.await()
                writer.join()
                val before = repository.listCues(identity, languages)
                assertNull(recover(database))
                assertEquals(before, repository.listCues(identity, languages))
                assertEquals(0L, repository.getJob(identity, languages)?.coveredUntilMs)
            }
        }
    }

    @Test
    fun emptyRecovery_sqlFailureRollsBackSourceCleanupSpeechCoverageAndProgress() = runBlocking {
        withRecoveryDatabase { database, repository ->
            val identity = PlaybackIdentity("server", "media", "recovery.mkv")
            val languages = ListenLanguagePair("ja", "zh")
            val before = repository.listCues(identity, languages)
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER reject_recovery_coverage BEFORE INSERT ON listen_translate_cue
                WHEN NEW.start_ms = 0 AND NEW.end_ms = 6000 AND NEW.text_src = ''
                BEGIN SELECT RAISE(ABORT, 'intentional recovery coverage failure'); END
            """.trimIndent())
            try {
                recover(database)
                fail("coverage insertion must fail")
            } catch (failure: android.database.sqlite.SQLiteException) {
                assertTrue(failure.message.orEmpty().contains("intentional recovery coverage failure"))
            }
            assertEquals(before, repository.listCues(identity, languages))
            assertEquals(0L, repository.getJob(identity, languages)?.coveredUntilMs)
        }
    }

    @Test
    fun emptyRecovery_cancellationBeforeTransactionCommitRollsBackWholePair() = runBlocking {
        withRecoveryDatabase { database, repository ->
            val identity = PlaybackIdentity("server", "media", "recovery.mkv")
            val languages = ListenLanguagePair("ja", "zh")
            val before = repository.listCues(identity, languages)
            try {
                database.withTransaction {
                    assertNotNull(recover(database))
                    throw CancellationException("cancel before outer transaction commits")
                }
                fail("cancellation must escape the transaction")
            } catch (_: CancellationException) {
                // Room must not commit the nested recovery operation independently.
            }
            assertEquals(before, repository.listCues(identity, languages))
            assertEquals(0L, repository.getJob(identity, languages)?.coveredUntilMs)
        }
    }

}
