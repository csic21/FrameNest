package com.framenest.data.server

import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbEntry
import com.framenest.smb.SmbError
import com.framenest.smb.SmbException
import com.framenest.smb.SmbFileMetadata
import com.framenest.smb.SmbRandomAccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository unit tests with an in-memory DAO stand-in and fake SMB client.
 * Avoids Android Room / Keystore on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun addUpdateDelete_persistsMetadataAndCredentialsSeparately() = runTest(dispatcher) {
        val dao = FakeServerDao()
        val store = InMemoryCredentialStore()
        val repo = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            clientFactory = { FakeSmbClient() },
            ioDispatcher = dispatcher,
            timeSource = { 1000L },
        )

        val saved = repo.addServer(
            name = "Home",
            host = "192.168.1.10",
            port = 445,
            username = "user",
            domain = null,
            defaultShare = "media",
            password = "pw1".toCharArray(),
        )
        assertTrue(store.hasPassword(saved.credentialAlias))
        assertEquals("pw1", String(store.getPassword(saved.credentialAlias)!!))
        assertFalse(dao.getById(saved.id)!!.toString().contains("pw1"))

        repo.updateServer(
            id = saved.id,
            name = "Home2",
            host = "192.168.1.11",
            port = 445,
            username = "user",
            defaultShare = "media",
            newPassword = "pw2".toCharArray(),
        )
        assertEquals("Home2", repo.getServer(saved.id)!!.name)
        assertEquals("pw2", String(store.getPassword(saved.credentialAlias)!!))

        repo.deleteServer(saved.id)
        assertEquals(null, repo.getServer(saved.id))
        assertFalse(store.hasPassword(saved.credentialAlias))
    }

    @Test
    fun testConnection_successAndAuthFailure() = runTest(dispatcher) {
        val dao = FakeServerDao()
        val store = InMemoryCredentialStore()
        val repoOk = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            clientFactory = { FakeSmbClient(authOk = true) },
            ioDispatcher = dispatcher,
        )
        assertTrue(
            repoOk.testConnection(
                host = "h",
                port = 445,
                username = "u",
                domain = null,
                password = "x".toCharArray(),
            ).isSuccess,
        )

        val repoFail = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            clientFactory = { FakeSmbClient(authOk = false) },
            ioDispatcher = dispatcher,
        )
        val result = repoFail.testConnection(
            host = "h",
            port = 445,
            username = "u",
            domain = null,
            password = "bad".toCharArray(),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SmbException)
        assertTrue((result.exceptionOrNull() as SmbException).error is SmbError.Auth)
    }

    @Test
    fun observeServers_emitsSortedByName() = runTest(dispatcher) {
        val dao = FakeServerDao()
        val store = InMemoryCredentialStore()
        val repo = ServerRepository(
            serverDao = dao,
            credentialStore = store,
            ioDispatcher = dispatcher,
            timeSource = { 1L },
        )
        repo.addServer("Beta", "b", 445, "u", password = "p".toCharArray())
        repo.addServer("Alpha", "a", 445, "u", password = "p".toCharArray())
        val names = repo.observeServers().first().map { it.name }
        assertEquals(listOf("Alpha", "Beta"), names)
    }
}

private class FakeServerDao : ServerDao {
    private val map = ConcurrentHashMap<String, ServerEntity>()

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<ServerEntity>> =
        kotlinx.coroutines.flow.flow {
            emit(getAll())
        }

    override suspend fun getAll(): List<ServerEntity> =
        map.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    override suspend fun getById(id: String): ServerEntity? = map[id]

    override suspend fun upsert(entity: ServerEntity) {
        map[entity.id] = entity
    }

    override suspend fun update(entity: ServerEntity) {
        map[entity.id] = entity
    }

    override suspend fun deleteById(id: String) {
        map.remove(id)
    }
}

private class FakeSmbClient(
    private val authOk: Boolean = true,
) : SmbClient {
    private var connected = false
    override val isConnected: Boolean get() = connected

    override fun connect(credentials: SmbCredentials) {
        if (!authOk) throw SmbException(SmbError.Auth())
        connected = true
    }

    override fun listShares(knownShares: List<String>): List<String> = knownShares

    override fun listDirectory(shareName: String, path: String): List<SmbEntry> = emptyList()

    override fun metadata(shareName: String, path: String): SmbFileMetadata =
        error("unused")

    override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess =
        error("unused")

    override fun disconnect() {
        connected = false
    }

    override fun close() {
        disconnect()
    }
}
