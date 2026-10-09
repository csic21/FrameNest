package com.framenest.smb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmbTransportOwnerTest {
    @Test fun `retire aborts a registered client during connect list and read`() {
        for (stage in Stage.entries) {
            val worker = Executors.newSingleThreadExecutor()
            val client = BlockingClient(stage)
            val owner = SmbTransportOwner()
            try {
                val operation = worker.submit {
                    owner.register(client)
                    try {
                        client.connect(credentials())
                        owner.ensureActive()
                        if (stage == Stage.LIST) client.listDirectory("media")
                        if (stage == Stage.READ) client.openRandomAccess("media", "a.srt")
                            .readAt(0, ByteArray(1), 0, 1)
                    } finally { owner.release(client) }
                }
                assertTrue("$stage did not enter", client.entered.await(5, TimeUnit.SECONDS))
                val caller = Thread.currentThread()
                owner.retire()
                assertTrue("$stage was not aborted", client.aborted.await(5, TimeUnit.SECONDS))
                assertNotSame(caller, client.abortThread)
                runCatching { operation.get(5, TimeUnit.SECONDS) }
                    .onFailure { assertTrue(it.cause is CancellationException) }
                assertEquals(1, client.abortCount.get())
                assertNull(owner.currentClient())
            } finally {
                client.unblock.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test fun `delayed cleanup and late registration cannot abort replacement generation`() {
        val pending = mutableListOf<() -> Unit>()
        val old = SmbTransportOwner { pending += it }
        val oldClient = BlockingClient()
        old.register(oldClient)
        old.retire()
        old.retire()
        assertEquals(1, pending.size)
        assertEquals(0, oldClient.abortCount.get())
        val fresh = SmbTransportOwner { pending += it }
        val freshClient = BlockingClient()
        fresh.register(freshClient)
        val late = BlockingClient()
        try {
            old.register(late)
            fail("Retired owner accepted late connect")
        } catch (_: CancellationException) { }
        pending.toList().forEach { it() }
        old.release(oldClient)
        assertEquals(1, oldClient.abortCount.get())
        assertEquals(1, late.abortCount.get())
        assertSame(freshClient, fresh.currentClient())
        assertEquals(0, freshClient.abortCount.get())
        fresh.release(freshClient)
    }

    @Test fun `retirement never waits for blocking abort`() {
        val pending = mutableListOf<() -> Unit>()
        val owner = SmbTransportOwner { pending += it }
        val client = BlockingClient()
        owner.register(client)
        owner.retire()
        assertNull(owner.currentClient())
        assertEquals(0, client.abortCount.get())
        assertEquals(1, pending.size)
        pending.single()()
        assertEquals(1, client.abortCount.get())
    }

    @Test fun `interrupt detaches off main and old cleanup cannot abort reconnected client`() {
        val pending = mutableListOf<() -> Unit>()
        val owner = SmbTransportOwner { pending += it }
        val oldClient = BlockingClient()
        owner.register(oldClient)
        owner.interruptClients()
        owner.interruptClients()
        assertNull(owner.currentClient())
        assertEquals(0, oldClient.abortCount.get())
        assertEquals(1, pending.size)
        owner.ensureActive()
        val replacement = BlockingClient()
        owner.register(replacement)
        pending.single()()
        owner.release(oldClient)
        assertEquals(1, oldClient.abortCount.get())
        assertEquals(0, replacement.abortCount.get())
        assertSame(replacement, owner.currentClient())
        owner.release(replacement)
        owner.retire()
        try {
            owner.register(BlockingClient())
            fail("Retired owner accepted a client after interrupt")
        } catch (_: CancellationException) { }
    }

    @Test fun `interrupt releases a blocked read without retiring the session`() {
        val worker = Executors.newSingleThreadExecutor()
        val client = BlockingClient(Stage.READ)
        val owner = SmbTransportOwner()
        try {
            owner.register(client)
            val operation = worker.submit {
                client.openRandomAccess("media", "sample.mp4").readAt(0, ByteArray(1), 0, 1)
            }
            assertTrue(client.entered.await(5, TimeUnit.SECONDS))
            val caller = Thread.currentThread()
            owner.interruptClients()
            assertTrue(client.aborted.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, client.abortThread)
            operation.get(5, TimeUnit.SECONDS)
            owner.ensureActive()
            assertNull(owner.currentClient())
        } finally {
            client.unblock.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun `cancelled reconnect registering after interrupt is released before connect`() {
        val enteredFactory = CountDownLatch(1)
        val leaveFactory = CountDownLatch(1)
        val client = BlockingClient(Stage.CONNECT)
        val owner = SmbTransportOwner()
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            enteredFactory.countDown()
            check(leaveFactory.await(5, TimeUnit.SECONDS))
            try {
                owner.register(client)
                currentCoroutineContext().ensureActive()
                client.connect(credentials())
            } finally {
                owner.release(client)
            }
        }
        try {
            assertTrue(enteredFactory.await(5, TimeUnit.SECONDS))
            job.cancel()
            owner.interruptClients()
            leaveFactory.countDown()
            runBlocking { job.join() }
            assertEquals(1L, client.entered.count)
            assertEquals(1, client.abortCount.get())
            assertNull(owner.currentClient())
        } finally {
            leaveFactory.countDown()
            job.cancel()
        }
    }

    private enum class Stage { CONNECT, LIST, READ }

    private class BlockingClient(private val stage: Stage? = null) : SmbClient {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val abortCount = AtomicInteger()
        @Volatile var abortThread: Thread? = null
        override val isConnected = true
        private fun gate(at: Stage) {
            if (stage != at) return
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS)) { "blocked operation was not aborted" }
        }
        override fun connect(credentials: SmbCredentials) = gate(Stage.CONNECT)
        override fun listShares(knownShares: List<String>) = emptyList<String>()
        override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
            gate(Stage.LIST)
            return emptyList()
        }
        override fun metadata(shareName: String, path: String): SmbFileMetadata = error("unused")
        override fun openRandomAccess(shareName: String, path: String) = object : SmbRandomAccess {
            override val size = 1L
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                gate(Stage.READ)
                return 1
            }
            override fun close() = Unit
        }
        override fun disconnect() = abort()
        override fun close() = abort()
        override fun abort() {
            abortThread = Thread.currentThread()
            abortCount.incrementAndGet()
            unblock.countDown()
            aborted.countDown()
        }
    }

    private fun credentials() = SmbCredentials("example.invalid", username = "", password = charArrayOf())
}
