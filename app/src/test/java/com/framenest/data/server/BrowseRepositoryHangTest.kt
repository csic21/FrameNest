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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseRepositoryHangTest {

    private val listing = listOf(
        SmbEntry("clip.mkv", "clip.mkv", false, 10, 0),
    )

    @Test(timeout = 5_000)
    fun hungListing_timesOutAndRetriesWithFreshSession() = runBlocking {
        val entered = CountDownLatch(1)
        val hung = BlockingSmbClient(entered)
        val ok = ImmediateSmbClient(listing)
        val created = AtomicInteger(0)
        val repo = repository {
            if (created.getAndIncrement() == 0) hung else ok
        }

        val result = repo.load("s1", RemoteLocation.shareRoot("media")).getOrThrow()
        val names = (result as BrowseRepository.BrowseContent.Directory).entries.map { it.name }

        assertEquals(listOf("clip.mkv"), names)
        assertEquals(2, created.get())
        assertTrue(hung.closed.get())
        assertTrue(entered.await(0, TimeUnit.MILLISECONDS))
    }

    @Test(timeout = 5_000)
    fun releaseSession_unblocksHungListing_nextLoadUsesNewClient() = runBlocking {
        val entered = CountDownLatch(1)
        val hung = BlockingSmbClient(entered)
        val ok = ImmediateSmbClient(listing)
        val created = AtomicInteger(0)
        val repo = repository {
            if (created.getAndIncrement() == 0) hung else ok
        }

        val first = async(Dispatchers.IO) {
            repo.load("s1", RemoteLocation.shareRoot("media"))
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repo.releaseSession()
        val recovered = first.await().getOrThrow()
        assertEquals(
            listOf("clip.mkv"),
            (recovered as BrowseRepository.BrowseContent.Directory).entries.map { it.name },
        )
        assertTrue(hung.closed.get())

        val second = repo.load("s1", RemoteLocation.shareRoot("media")).getOrThrow()
        assertEquals(
            listOf("clip.mkv"),
            (second as BrowseRepository.BrowseContent.Directory).entries.map { it.name },
        )
        // First load retried onto `ok`; the follow-up listing reuses that session.
        assertEquals(2, created.get())
    }

    private fun repository(clientFactory: () -> SmbClient): BrowseRepository {
        val server = SavedServer(
            id = "s1",
            name = "NAS",
            host = "h",
            username = "u",
            credentialAlias = "a",
            defaultShare = "media",
        )
        val dao = object : ServerDao {
            override fun observeAll() = error("unused")
            override suspend fun getAll() = error("unused")
            override suspend fun getById(id: String) = ServerEntity.fromModel(server, 0, 0)
            override suspend fun upsert(entity: ServerEntity) = error("unused")
            override suspend fun update(entity: ServerEntity) = error("unused")
            override suspend fun deleteById(id: String) = error("unused")
        }
        val store = InMemoryCredentialStore().also {
            it.savePassword("a", "pw".toCharArray())
        }
        val serverRepo = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            ioDispatcher = Dispatchers.IO,
        )
        return BrowseRepository(
            serverRepository = serverRepo,
            clientFactory = clientFactory,
            ioDispatcher = Dispatchers.IO,
            operationTimeoutMs = 250L,
            watchdogDispatcher = Dispatchers.IO,
        )
    }
}

private class ImmediateSmbClient(
    private val listing: List<SmbEntry>,
) : SmbClient {
    override val isConnected: Boolean = true
    override fun connect(credentials: SmbCredentials) = Unit
    override fun listShares(knownShares: List<String>) = knownShares
    override fun listDirectory(shareName: String, path: String) = listing
    override fun metadata(shareName: String, path: String): SmbFileMetadata = error("n/a")
    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess = error("n/a")
    override fun disconnect() = Unit
    override fun close() = Unit
}

private class BlockingSmbClient(
    private val entered: CountDownLatch,
) : SmbClient {
    val closed = AtomicBoolean(false)
    private val released = CountDownLatch(1)

    override val isConnected: Boolean
        get() = !closed.get()

    override fun connect(credentials: SmbCredentials) = Unit

    override fun listShares(knownShares: List<String>) = knownShares

    override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
        entered.countDown()
        released.await()
        throw SmbException(SmbError.Disconnected("Client closed"))
    }

    override fun metadata(shareName: String, path: String): SmbFileMetadata = error("n/a")

    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess = error("n/a")

    override fun disconnect() = close()

    override fun close() {
        closed.set(true)
        released.countDown()
    }
}
