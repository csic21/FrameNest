package com.framenest.data.server

import com.framenest.core.diagnostics.DiagnosticLog
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbException
import com.framenest.smb.SmbErrorMapper
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
        try {
            serverDao.upsert(ServerEntity.fromModel(model, createdAtMs = now, updatedAtMs = now))
        } catch (t: Throwable) {
            // Do not leave an orphaned credential when Room rejects the insert.
            runCatching { credentialStore.deletePassword(alias) }
                .exceptionOrNull()
                ?.let(t::addSuppressed)
            throw t
        }
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
        val passwordToSave = newPassword?.takeIf { it.isNotEmpty() }
        val changesPassword = passwordToSave != null
        val previousPassword = if (changesPassword) {
            credentialStore.getPassword(existing.credentialAlias)
        } else {
            null
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
        try {
            if (changesPassword) {
                credentialStore.savePassword(existing.credentialAlias, passwordToSave)
            }
            serverDao.upsert(
                ServerEntity.fromModel(
                    model,
                    createdAtMs = existing.createdAtMs,
                    updatedAtMs = timeSource(),
                ),
            )
            model
        } catch (t: Throwable) {
            if (changesPassword) {
                runCatching {
                    if (previousPassword == null) {
                        credentialStore.deletePassword(existing.credentialAlias)
                    } else {
                        credentialStore.savePassword(existing.credentialAlias, previousPassword)
                    }
                }.exceptionOrNull()?.let(t::addSuppressed)
            }
            throw t
        } finally {
            previousPassword?.fill('\u0000')
        }
    }

    suspend fun deleteServer(id: String) = withContext(ioDispatcher) {
        val existing = serverDao.getById(id) ?: return@withContext
        serverDao.deleteById(id)
        try {
            credentialStore.deletePassword(existing.credentialAlias)
        } catch (t: Throwable) {
            // Keep Room and credential storage pointing at the same logical server.
            runCatching { serverDao.upsert(existing) }
                .exceptionOrNull()
                ?.let(t::addSuppressed)
            throw t
        }
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
            DiagnosticLog.info("SmbTest", "connect ok host=${host.trim()} port=$port")
            Result.success(Unit)
        } catch (e: SmbException) {
            DiagnosticLog.warn(
                "SmbTest",
                "connect failed category=${e.error.javaClass.simpleName} " +
                    SmbErrorMapper.safeMessage(e),
            )
            Result.failure(e)
        } catch (t: Throwable) {
            DiagnosticLog.warn("SmbTest", "connect failed: ${SmbErrorMapper.safeMessage(t)}")
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
