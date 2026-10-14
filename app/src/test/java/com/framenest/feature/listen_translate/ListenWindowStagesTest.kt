package com.framenest.feature.listen_translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenWindowStagesTest {
    private val speech = ListenWindowResult("hello", "", 0L, 3_000L)
    private val timings = { ListenStageTimings() }

    @Test fun preparationOverlapsRecognition_withoutDelayingSource() = runTest {
        var sourceAt = -1L
        var translatedAt = -1L
        val result = processListenWindowStages(
            prepareTranslation = { delay(1_000L) },
            recognizeSource = { start -> start(); delay(800L); speech },
            publishSource = { sourceAt = testScheduler.currentTime },
            translateSource = { delay(100L); translatedAt = testScheduler.currentTime; "你好" },
            timings = timings,
        )
        assertEquals(800L, sourceAt)
        assertEquals(1_100L, translatedAt) // Serialized baseline would be 1,900 ms.
        assertEquals("你好", result.textTgt)
        assertEquals(speech.cueEndMs, result.cueEndMs)
    }

    @Test fun warmPreparationAddsNoArtificialWait() = runTest {
        val result = processListenWindowStages(
            {}, { start -> start(); delay(800L); speech }, {},
            { delay(100L); "你好" }, timings,
        )
        assertEquals(900L, testScheduler.currentTime)
        assertEquals("你好", result.textTgt)
    }

    @Test fun unrecognizedAudioCancelsStartedPreparation() = runTest {
        var closed = false
        val result = processListenWindowStages(
            { try { awaitCancellation() } finally { closed = true } },
            { start -> start(); delay(20L); ListenWindowResult("", "", blankReason = ListenBlankReason.UnrecognizedSpeech) },
            { fail("must not publish") }, { fail("must not translate"); "" }, timings,
        )
        assertTrue(closed)
        assertEquals(ListenBlankReason.UnrecognizedSpeech, result.blankReason)
        assertEquals(20L, testScheduler.currentTime)
    }

    @Test fun cancellationAfterSourcePublicationCannotPublishTranslation() = runTest {
        var sourcePublished = false
        var translated = false
        var closed = false
        val work = launch {
            processListenWindowStages(
                { try { awaitCancellation() } finally { closed = true } },
                { start -> start(); speech }, { sourcePublished = true },
                { translated = true; "你好" }, timings,
            )
        }
        runCurrent()
        assertTrue(sourcePublished)
        work.cancelAndJoin()
        assertTrue(closed)
        assertFalse(translated)
    }

    @Test fun preparationFailure_keepsSourceAndMakesOnlyTranslationRetryable() = runTest {
        var published = false
        val result = processListenWindowStages(
            { error("model unavailable") }, { start -> start(); delay(100L); speech },
            { published = true }, { fail("must not translate"); "" }, timings,
        )
        assertTrue(published)
        assertEquals("hello", result.textSrc)
        assertEquals("", result.textTgt)
        assertTrue(result.retryableErrorMessage!!.contains("model unavailable"))
    }

    @Test fun blankSource_cancelsPreparationWithoutWaitingOrPublishing() = runTest {
        var closed = false
        val blank = ListenWindowResult("", "", blankReason = ListenBlankReason.NearSilence)
        val result = processListenWindowStages(
            { try { awaitCancellation() } finally { closed = true } },
            { delay(20L); blank }, { fail("blank must not publish") },
            { fail("blank must not translate"); "" }, timings,
        )
        assertFalse(closed) // Preparation was never started for confirmed silence.
        assertEquals(20L, testScheduler.currentTime)
        assertEquals(ListenBlankReason.NearSilence, result.blankReason)
    }

    @Test fun recognitionFailure_cancelsPreparationAndPreservesFailure() = runTest {
        var closed = false
        val failure = runCatching {
            processListenWindowStages(
                { try { awaitCancellation() } finally { closed = true } },
                { start -> start(); delay(1L); error("audio failed") }, { fail("must not publish") }, { "" }, timings,
            )
        }.exceptionOrNull()
        assertTrue(closed)
        assertEquals("audio failed", failure?.message)
    }

    @Test fun parentCancellation_cancelsPreparationAndCannotPublishLateSource() = runTest {
        var closed = false
        var published = false
        val work = launch {
            processListenWindowStages(
                { try { awaitCancellation() } finally { closed = true } },
                { start -> start(); awaitCancellation() }, { published = true }, { "" }, timings,
            )
        }
        runCurrent()
        work.cancelAndJoin()
        assertTrue(closed)
        assertFalse(published)
    }

    @Test fun preparationCancellation_isNotDowngradedToRetryableFailure() = runTest {
        var published = false
        val failure = runCatching {
            processListenWindowStages(
                { throw CancellationException("stale window") }, { start -> start(); speech },
                { published = true }, { "" }, timings,
            )
        }.exceptionOrNull()
        assertTrue(published)
        assertTrue(failure is CancellationException)
    }

    @Test fun sourceCommitFailure_cancelsPendingPreparation() = runTest {
        var closed = false
        val failure = runCatching {
            processListenWindowStages(
                { try { awaitCancellation() } finally { closed = true } }, { start -> start(); delay(1L); speech },
                { error("stale lease") }, { fail("must not translate"); "" }, timings,
            )
        }.exceptionOrNull()
        assertTrue(closed)
        assertEquals("stale lease", failure?.message)
    }
}
