package com.framenest.data.server

import com.framenest.core.model.RemoteLocation
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbEntry
import com.framenest.smb.SmbError
import com.framenest.smb.SmbException
import com.framenest.smb.SmbFileMetadata
import com.framenest.smb.SmbRandomAccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRepositoryHangTest {
    @Test(timeout = 5_000)
    fun hungListing_timesOutAndRetries_beforeOldCleanupFinishes() = runBlocking {
        val hung = BlockingCleanupSmbClient(blockListing = true, forceAbort = true)
        val ok = BrowseTestSmbClient()
        val created = AtomicInteger()
        val repo = browseTestRepository(timeoutMs = 100) {
            if (created.getAndIncrement() == 0) hung else ok
        }
        try {
            assertListing(repo.load("s1", location).getOrThrow())
            assertTrue(hung.cleanupEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, hung.allowCleanup.count)
            assertEquals(2, created.get())
            assertEquals(1, hung.abortCount.get())
            assertTrue(ok.isConnected)
        } finally {
            hung.allowCleanup.countDown()
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun releaseAndReentry_doNotWaitForBlockingClose_orOldListing() = runBlocking {
        // This client deliberately models a legacy close that waits BEFORE
        // unblocking its listing. An instantaneous fake would miss the bug.
        val hung = BlockingCleanupSmbClient(blockListing = true, forceAbort = false)
        val ok = BrowseTestSmbClient()
        val created = AtomicInteger()
        val repo = browseTestRepository { if (created.getAndIncrement() == 0) hung else ok }
        val request = repo.newRequest()
        val first = async(Dispatchers.IO) { repo.load("s1", location, request) }
        try {
            assertTrue(hung.listingEntered.await(1, TimeUnit.SECONDS))
            first.cancel()
            val caller = Thread.currentThread()
            repo.releaseSession(request)
            assertTrue(hung.cleanupEntered.await(1, TimeUnit.SECONDS))
            assertNotEquals(caller, hung.cleanupThread)
            assertFalse(first.isCompleted)
            assertEquals(1L, hung.allowCleanup.count)
            val next = repo.newRequest()
            assertListing(withTimeout(1_000) { repo.load("s1", location, next).getOrThrow() })
            repeat(3) { repo.releaseSession(request) }
            assertTrue(ok.isConnected)
            hung.allowCleanup.countDown()
            first.join()
            // The old request's eventual catch/finally must not close `ok`.
            assertListing(repo.load("s1", location, next).getOrThrow())
            assertEquals(2, created.get())
            assertEquals(1, hung.closeCount.get())
        } finally {
            hung.allowCleanup.countDown()
            first.cancelAndJoin()
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun cancellingCoroutine_alsoAbortsBlockedIo_withoutExplicitRelease() = runBlocking {
        val hung = BlockingCleanupSmbClient(blockListing = true, forceAbort = true)
        val repo = browseTestRepository { hung }
        val first = async(Dispatchers.IO) { repo.load("s1", location) }
        try {
            assertTrue(hung.listingEntered.await(1, TimeUnit.SECONDS))
            first.cancel()
            assertTrue(hung.cleanupEntered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { first.join() }
            assertEquals(1, hung.abortCount.get())
            assertEquals(1L, hung.allowCleanup.count)
        } finally {
            hung.allowCleanup.countDown()
            first.cancelAndJoin()
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun lateConnect_cannotPublishOrRetry_afterReleaseAndNewLoad() = runBlocking {
        val entered = CountDownLatch(1)
        val finishConnect = CountDownLatch(1)
        val old = object : BrowseTestSmbClient() {
            override fun connect(credentials: SmbCredentials) {
                entered.countDown()
                check(finishConnect.await(3, TimeUnit.SECONDS))
                super.connect(credentials)
            }
        }
        val ok = BrowseTestSmbClient()
        val created = AtomicInteger()
        val repo = browseTestRepository { if (created.getAndIncrement() == 0) old else ok }
        val request = repo.newRequest()
        val first = async(Dispatchers.IO) { repo.load("s1", location, request) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repo.releaseSession(request)
            assertTrue(old.cleanupEntered.await(1, TimeUnit.SECONDS))
            val next = repo.newRequest()
            assertListing(repo.load("s1", location, next).getOrThrow())
            finishConnect.countDown()
            first.join()
            assertTrue(first.isCancelled)
            assertEquals(0, old.listCount.get())
            assertEquals(1, old.closeCount.get())
            assertEquals(2, created.get())
            assertTrue(ok.isConnected)
            assertListing(repo.load("s1", location, next).getOrThrow())
        } finally {
            finishConnect.countDown()
            first.cancelAndJoin()
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun repeatedRefresh_detachesEachGeneration_andCleanupIsIdempotent() = runBlocking {
        val clients = List(4) { BlockingCleanupSmbClient(blockListing = false, forceAbort = true) }
        val created = AtomicInteger()
        val repo = browseTestRepository { clients[created.getAndIncrement()] }
        try {
            repeat(4) { index ->
                val request = repo.newRequest()
                assertListing(repo.load("s1", location, request).getOrThrow())
                repeat(3) { repo.releaseSession(request) }
                assertTrue(clients[index].cleanupEntered.await(1, TimeUnit.SECONDS))
                assertEquals(1, clients[index].abortCount.get())
                assertEquals(1, clients[index].closeCount.get())
                assertEquals(1L, clients[index].allowCleanup.count)
            }
            assertEquals(4, created.get())
        } finally {
            clients.forEach { it.allowCleanup.countDown() }
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun staleScreenRelease_doesNotCloseSessionClaimedByNewScreen() = runBlocking {
        val client = BrowseTestSmbClient()
        val created = AtomicInteger()
        val repo = browseTestRepository { created.incrementAndGet(); client }
        val first = repo.newRequest()
        assertListing(repo.load("s1", location, first).getOrThrow())
        val second = repo.newRequest()
        assertListing(repo.load("s1", location, second).getOrThrow())
        repo.releaseSession(first)
        assertTrue(client.isConnected)
        assertEquals(0, client.closeCount.get())
        repo.releaseSession(second)
        assertTrue(client.cleanupEntered.await(1, TimeUnit.SECONDS))
        repo.releaseSession(second)
        assertEquals(1, client.closeCount.get())
        assertEquals(1, created.get())
    }

    @Test(timeout = 5_000)
    fun releaseDoesNotWaitForBlockedClientStateGetter() = runBlocking {
        val getterEntered = CountDownLatch(1)
        val finishGetter = CountDownLatch(1)
        val blockGetter = AtomicBoolean(false)
        val client = object : BrowseTestSmbClient() {
            override val isConnected: Boolean
                get() {
                    if (blockGetter.get()) {
                        getterEntered.countDown()
                        check(finishGetter.await(3, TimeUnit.SECONDS))
                    }
                    return super.isConnected
                }
        }
        val repo = browseTestRepository { client }
        assertListing(repo.load("s1", location).getOrThrow())
        blockGetter.set(true)
        val request = repo.newRequest()
        val pending = async(Dispatchers.IO) { repo.load("s1", location, request) }
        try {
            assertTrue(getterEntered.await(1, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repo.releaseSession(request)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
            assertEquals(1L, finishGetter.count)
            assertTrue(client.cleanupEntered.await(1, TimeUnit.SECONDS))
        } finally {
            finishGetter.countDown()
            pending.cancelAndJoin()
            repo.releaseSession()
        }
    }

    @Test(timeout = 5_000)
    fun releasedQueuedRequest_neverStartsConnection() = runBlocking {
        val created = AtomicInteger()
        val repo = browseTestRepository { created.incrementAndGet(); BrowseTestSmbClient() }
        val request = repo.newRequest()
        repo.releaseSession(request)
        val stale = async { repo.load("s1", location, request) }
        stale.join()
        assertTrue(stale.isCancelled)
        assertEquals(0, created.get())
        assertListing(repo.load("s1", location).getOrThrow())
        assertEquals(1, created.get())
        repo.releaseSession()
    }

    private fun assertListing(content: BrowseRepository.BrowseContent) {
        assertEquals(listOf("clip.mkv"), (content as BrowseRepository.BrowseContent.Directory).entries.map { it.name })
    }

    companion object {
        private val location = RemoteLocation.shareRoot("media")
    }
}

internal fun browseTestServerRepository() = ServerRepository(
    serverDao = object : ServerDao {
        private val server = SavedServer("s1", "NAS", "example.invalid", username = "", credentialAlias = "test", defaultShare = "media")
        override fun observeAll() = error("unused")
        override suspend fun getAll() = error("unused")
        override suspend fun getById(id: String) = ServerEntity.fromModel(server, 0, 0)
        override suspend fun upsert(entity: ServerEntity) = error("unused")
        override suspend fun update(entity: ServerEntity) = error("unused")
        override suspend fun deleteById(id: String) = error("unused")
    },
    credentialStore = InMemoryCredentialStore().also { it.savePassword("test", charArrayOf()) },
    ioDispatcher = Dispatchers.IO,
)

internal fun browseTestRepository(timeoutMs: Long = 10_000, factory: () -> SmbClient) = BrowseRepository(
    serverRepository = browseTestServerRepository(),
    clientFactory = factory,
    operationTimeoutMs = timeoutMs,
)

internal open class BrowseTestSmbClient : SmbClient {
    private val connected = AtomicBoolean(false)
    val closeCount = AtomicInteger()
    val listCount = AtomicInteger()
    val cleanupEntered = CountDownLatch(1)
    override val isConnected: Boolean get() = connected.get()
    override fun connect(credentials: SmbCredentials) { connected.set(true) }
    override fun listShares(knownShares: List<String>) = knownShares
    override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
        listCount.incrementAndGet()
        return listOf(SmbEntry("clip.mkv", "clip.mkv", false, 10, 0))
    }
    override fun metadata(shareName: String, path: String): SmbFileMetadata = error("unused")
    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess = error("unused")
    override fun disconnect() = close()
    override fun close() {
        closeCount.incrementAndGet()
        connected.set(false)
        cleanupEntered.countDown()
    }
}

internal class BlockingCleanupSmbClient(
    private val blockListing: Boolean,
    private val forceAbort: Boolean,
) : BrowseTestSmbClient() {
    val listingEntered = CountDownLatch(1)
    val allowCleanup = CountDownLatch(1)
    private val listingReleased = CountDownLatch(1)
    val abortCount = AtomicInteger()
    @Volatile var cleanupThread: Thread? = null

    override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
        listingEntered.countDown()
        if (!blockListing) return super.listDirectory(shareName, path)
        check(listingReleased.await(3, TimeUnit.SECONDS))
        throw SmbException(SmbError.Disconnected("Test transport aborted"))
    }

    override fun abort() {
        abortCount.incrementAndGet()
        if (forceAbort) listingReleased.countDown()
        close()
    }

    override fun close() {
        cleanupThread = Thread.currentThread()
        super.close()
        check(allowCleanup.await(3, TimeUnit.SECONDS))
        listingReleased.countDown()
    }
}
