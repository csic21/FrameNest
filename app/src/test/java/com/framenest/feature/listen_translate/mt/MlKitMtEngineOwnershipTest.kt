package com.framenest.feature.listen_translate.mt

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlKitMtEngineOwnershipTest {
    @Test
    fun cancelledDownload_closesClientAndLateCompletionCannotCacheIt() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        try {
            val preparation = launch { engine.ensureModel("en", "zh") }
            runCurrent()
            val cancelled = clients.single()
            preparation.cancelAndJoin()
            assertEquals(1, cancelled.closeCalls)

            // ML Kit's Task may finish even though its coroutine was cancelled.
            cancelled.completeDownload()
            val retry = async { engine.ensureModel("en", "zh") }
            runCurrent()
            assertEquals(2, clients.size)
            clients.last().completeDownload()
            retry.await()
            engine.ensureModel("en", "zh")
            assertEquals(2, clients.size)
        } finally {
            engine.close()
        }
        assertTrue(clients.all { it.closeCalls == 1 })
    }

    @Test
    fun failedDownload_closesClientAndAllowsFreshPreparation() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        try {
            val preparation = async { runCatching { engine.ensureModel("en", "zh") } }
            runCurrent()
            val failure = IllegalStateException("download failed")
            clients.single().failDownload(failure)
            val reported = preparation.await().exceptionOrNull()
            // Coroutine stack-trace recovery may copy the exception instance.
            assertEquals(failure::class, reported!!::class)
            assertEquals(failure.message, reported.message)
            assertEquals(1, clients.single().closeCalls)

            val retry = async { engine.ensureModel("en", "zh") }
            runCurrent()
            assertEquals(2, clients.size)
            clients.last().completeDownload()
            retry.await()
        } finally {
            engine.close()
        }
        assertTrue(clients.all { it.closeCalls == 1 })
    }

    @Test
    fun concurrentSamePair_preparesAndRetainsOneClient() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        try {
            val first = async { engine.ensureModel("en", "zh") }
            val second = async { engine.ensureModel("EN", "zh-Hans") }
            runCurrent()
            assertEquals(1, clients.size)
            assertEquals(1, clients.single().downloadCalls)
            clients.single().completeDownload()
            first.await()
            second.await()
            assertEquals(1, clients.size)
            assertEquals(1, clients.single().downloadCalls)
            assertEquals(0, clients.single().closeCalls)
        } finally {
            engine.close()
            engine.close()
        }
        assertEquals(1, clients.single().closeCalls)
    }

    @Test
    fun cancelledWaiter_doesNotCloseAnotherCallersClient() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        try {
            val owner = async { engine.ensureModel("en", "zh") }
            val waiter = launch { engine.ensureModel("en", "zh") }
            runCurrent()
            waiter.cancelAndJoin()
            assertEquals(0, clients.single().closeCalls)
            clients.single().completeDownload()
            owner.await()
            assertEquals(1, clients.size)
        } finally {
            engine.close()
        }
        assertEquals(1, clients.single().closeCalls)
    }

    @Test
    fun cancelledOwner_waiterPreparesFreshClientAndOldCompletionCannotReplaceIt() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        try {
            val owner = launch { engine.ensureModel("en", "zh") }
            val waiter = async { engine.ensureModel("en", "zh") }
            runCurrent()
            owner.cancelAndJoin()
            runCurrent()
            assertEquals(2, clients.size)
            assertEquals(1, clients.first().closeCalls)
            clients.first().completeDownload()
            clients.last().completeDownload()
            waiter.await()
            engine.ensureModel("en", "zh")
            assertEquals(2, clients.size)
            assertEquals(0, clients.last().closeCalls)
        } finally {
            engine.close()
        }
        assertTrue(clients.all { it.closeCalls == 1 })
    }

    @Test
    fun closeDuringDownloads_closesPendingAndCachedClientsAndRejectsLateCompletion() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = engine(clients)
        val cached = async { engine.ensureModel("en", "zh") }
        runCurrent()
        clients.single().completeDownload()
        cached.await()

        val firstPending = async { runCatching { engine.ensureModel("en", "ja") } }
        val secondPending = async { runCatching { engine.ensureModel("en", "de") } }
        val samePairWaiter = async { runCatching { engine.ensureModel("en", "ja") } }
        runCurrent()
        assertEquals(3, clients.size)
        engine.close()
        engine.close()
        assertTrue(clients.all { it.closeCalls == 1 })

        clients.drop(1).forEach { it.completeDownload() }
        assertTrue(firstPending.await().exceptionOrNull() is IllegalStateException)
        assertTrue(secondPending.await().exceptionOrNull() is IllegalStateException)
        assertTrue(samePairWaiter.await().exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { engine.ensureModel("en", "zh") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { engine.ensureModel("en", "fr") }.exceptionOrNull() is IllegalStateException)
        assertEquals(3, clients.size)
        engine.close()
        assertTrue(clients.all { it.closeCalls == 1 })
    }

    @Test
    fun synchronousDownloadFailure_closesClientWithoutReplacingOriginalFailure() = runTest {
        val failure = IllegalStateException("cannot start download")
        val client = FakeTranslator(startFailure = failure, closeFailure = true)
        val engine = MlKitMtEngine(false) { client }
        assertSame(failure, runCatching { engine.ensureModel("en", "zh") }.exceptionOrNull())
        engine.close()
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun failedClientClose_doesNotPreventReleasingOtherPendingClients() = runTest {
        val clients = mutableListOf<FakeTranslator>()
        val engine = MlKitMtEngine(false) {
            FakeTranslator(closeFailure = clients.isEmpty()).also { clients += it }
        }
        val first = launch { engine.ensureModel("en", "zh") }
        val second = launch { engine.ensureModel("en", "ja") }
        runCurrent()
        engine.close()
        first.cancelAndJoin()
        second.cancelAndJoin()
        engine.close()
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.closeCalls == 1 })
    }

    private fun engine(clients: MutableList<FakeTranslator>): MlKitMtEngine =
        MlKitMtEngine(false) { FakeTranslator().also { clients += it } }

    private class FakeTranslator(
        private val startFailure: Exception? = null,
        private val closeFailure: Boolean = false,
    ) : Translator {
        private val download = TaskCompletionSource<Void>()
        var downloadCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun downloadModelIfNeeded(): Task<Void> = startDownload()

        override fun downloadModelIfNeeded(conditions: DownloadConditions): Task<Void> = startDownload()

        private fun startDownload(): Task<Void> {
            check(closeCalls == 0) { "download on closed client" }
            downloadCalls += 1
            startFailure?.let { throw it }
            return download.task
        }

        fun completeDownload() = download.setResult(null)

        fun failDownload(failure: Exception) = download.setException(failure)

        override fun translate(text: String): Task<String> {
            check(closeCalls == 0) { "translation on closed client" }
            return Tasks.forResult("translated: $text")
        }

        override fun close() {
            closeCalls += 1
            if (closeFailure) error("close failed")
        }
    }
}
