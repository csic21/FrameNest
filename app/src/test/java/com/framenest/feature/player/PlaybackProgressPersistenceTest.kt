package com.framenest.feature.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressPersistenceTest {
    @Test fun finalExitSaveCannotBeOverwrittenBySlowerPeriodicWrite() = runTest {
        val writer = PlaybackProgressPersistence(this)
        val releaseOldWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Long>()
        writer.save { releaseOldWrite.await(); writes += 10_000L }
        writer.save { writes += 25_000L }
        runCurrent()
        assertTrue(writes.isEmpty())
        releaseOldWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(10_000L, 25_000L), writes)
    }

    @Test fun immediateReopenReadsAfterPendingExitSave_includingExplicitZero() = runTest {
        val writer = PlaybackProgressPersistence(this)
        val releaseWrite = CompletableDeferred<Unit>()
        var history = 42_000L
        writer.save { releaseWrite.await(); history = 0L }
        val reopened = async { writer.afterSaves { history } }
        runCurrent()
        assertFalse(reopened.isCompleted)
        releaseWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(0L, reopened.await())
    }
}
