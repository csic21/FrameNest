package com.framenest.data.server

import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbException
import com.framenest.smb.SmbjClient
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * CRUD + credential binding + connection test for saved SMB servers.
 */
class ServerRepository(
    private val serverDao: ServerDao,
    private val credentialStore: CredentialStore,
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    fun observeServers(): Flow<List<SavedServer>> =
        serverDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun getServer(id: String): SavedServer? = withContext(ioDispatcher) {
        serverDao.getById(id)?.toModel()
    }

    suspend fun getPassword(server: SavedServer): CharArray? = withContext(ioDispatcher) {
        credentialStore.getPassword(server.credentialAlias)
    }

    /**
     * Insert a new server. [password] is required.
     * @return the new server id
     */
    suspend fun addServer(
        name: String,
        host: String,
        port: Int = SavedServer.DEFAULT_PORT,
        username: String,
        domain: String? = null,
        defaultShare: String? = null,
        password: CharArray,
    ): SavedServer = withContext(ioDispatcher) {
        val now = timeSource()
        val id = UUID.randomUUID().toString()
        val alias = credentialStore.createAlias()
        credentialStore.savePassword(alias, password)
        val model = SavedServer(
            id = id,
            name = name.trim(),
            host = host.trim(),
            port = port,
            username = username.trim(),
            domain = domain?.trim()?.ifEmpty { null },
            credentialAlias = alias,
            defaultShare = defaultShare?.trim()?.ifEmpty { null },
        )
        serverDao.upsert(ServerEntity.fromModel(model, createdAtMs = now, updatedAtMs = now))
        model
    }

    /**
     * Update metadata. When [newPassword] is null or empty, the existing password is kept.
     */
    suspend fun updateServer(
        id: String,
        name: String,
        host: String,
        port: Int,
        username: String,
        domain: String? = null,
        defaultShare: String? = null,
        newPassword: CharArray? = null,
    ): SavedServer = withContext(ioDispatcher) {
        val existing = serverDao.getById(id)
            ?: throw IllegalArgumentException("Server not found: $id")
        if (newPassword != null && newPassword.isNotEmpty()) {
            credentialStore.savePassword(existing.credentialAlias, newPassword)
        }
        val model = SavedServer(
            id = id,
            name = name.trim(),
            host = host.trim(),
            port = port,
            username = username.trim(),
            domain = domain?.trim()?.ifEmpty { null },
            credentialAlias = existing.credentialAlias,
            defaultShare = defaultShare?.trim()?.ifEmpty { null },
        )
        serverDao.upsert(
            ServerEntity.fromModel(
                model,
                createdAtMs = existing.createdAtMs,
                updatedAtMs = timeSource(),
            ),
        )
        model
    }

    suspend fun deleteServer(id: String) = withContext(ioDispatcher) {
        val existing = serverDao.getById(id) ?: return@withContext
        credentialStore.deletePassword(existing.credentialAlias)
        serverDao.deleteById(id)
    }

    /**
     * Test SMB connect with either an existing saved server or draft form values.
     * Never logs the password.
     */
    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        domain: String?,
        password: CharArray,
    ): Result<Unit> = withContext(ioDispatcher) {
        val client = clientFactory()
        val credentials = SmbCredentials(
            host = host.trim(),
            port = port,
            username = username.trim(),
            password = password,
            domain = domain?.trim().orEmpty(),
        )
        try {
            client.connect(credentials)
            Result.success(Unit)
        } catch (e: SmbException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Test connection for a saved server, optionally overriding the password
     * (for edit forms when the user typed a new password before saving).
     */
    suspend fun testSavedServer(
        serverId: String,
        passwordOverride: CharArray? = null,
    ): Result<Unit> = withContext(ioDispatcher) {
        val server = serverDao.getById(serverId)?.toModel()
            ?: return@withContext Result.failure(IllegalArgumentException("Server not found"))
        val password = when {
            passwordOverride != null && passwordOverride.isNotEmpty() -> passwordOverride
            else -> credentialStore.getPassword(server.credentialAlias)
                ?: return@withContext Result.failure(
                    IllegalStateException("No stored password for server"),
                )
        }
        testConnection(
            host = server.host,
            port = server.port,
            username = server.username,
            domain = server.domain,
            password = password,
        )
    }
}
