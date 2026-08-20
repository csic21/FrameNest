package com.framenest.data.server

import com.framenest.core.model.MediaExtensions
import com.framenest.core.model.RemoteEntry
import com.framenest.core.model.RemoteLocation
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbEntry
import com.framenest.smb.SmbError
import com.framenest.smb.SmbException
import com.framenest.smb.SmbLog
import com.framenest.smb.SmbPathUtils
import com.framenest.smb.SmbjClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * SMB browse operations for a saved server.
 *
 * Listings run on [ioDispatcher]. Consecutive navigation on the same saved server
 * reuses one serialized SMB session; configuration changes, timeouts, cancellation
 * and SMB errors discard it. [releaseSession] can close the live transport from
 * another thread so a hung listing does not keep [sessionMutex] forever.
 */
class BrowseRepository(
    private val serverRepository: ServerRepository,
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    watchdogDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val sessionMutex = Mutex()
    private val sessionGuard = Any()
    private var sessionKey: BrowseConnectionKey? = null
    private var sessionClient: SmbClient? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + watchdogDispatcher)

    sealed class BrowseContent {
        data class Shares(val entries: List<RemoteEntry>) : BrowseContent()
        data class Directory(val entries: List<RemoteEntry>) : BrowseContent()
    }

    suspend fun load(
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> = withContext(ioDispatcher) {
        sessionMutex.withLock {
            loadSerialized(serverId, location)
        }
    }

    /**
     * Close the current browse transport immediately. Safe to call while [load] is
     * blocked in native/SMB I/O; the waiter then fails and the mutex can be released.
     */
    fun releaseSession() {
        val stale = synchronized(sessionGuard) {
            val client = sessionClient
            sessionClient = null
            sessionKey = null
            client
        } ?: return
        runCatching { stale.close() }
    }

    private fun abortClient(client: SmbClient) {
        synchronized(sessionGuard) {
            if (sessionClient === client) {
                sessionClient = null
                sessionKey = null
            }
        }
        runCatching { client.close() }
    }

    private fun logBrowse(message: String) {
        runCatching { SmbLog.w(message) }
    }

    private suspend fun loadSerialized(
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> {
        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return loadOnce(serverId, location)
            } catch (cancelled: CancellationException) {
                releaseSession()
                throw cancelled
            } catch (t: Throwable) {
                releaseSession()
                if (attempt < MAX_ATTEMPTS - 1 && BrowseSessionRetryPolicy.shouldRetry(t)) {
                    logBrowse("Browse listing failed; retrying with a new session")
                    lastFailure = t
                } else {
                    return Result.failure(t)
                }
            }
        }
        return Result.failure(lastFailure ?: IllegalStateException("Browse failed"))
    }

    private suspend fun loadOnce(
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> {
        val server = serverRepository.getServer(serverId)
            ?: return Result.failure(IllegalArgumentException("Server not found"))
        val requestedKey = BrowseConnectionKey.from(server)
        val client = ensureSession(server, requestedKey)
        coroutineContext.ensureActive()
        val watchdog = watchdogScope.launch {
            delay(operationTimeoutMs)
            logBrowse("Browse SMB operation timed out after ${operationTimeoutMs}ms")
            abortClient(client)
        }
        try {
            return if (location.isShareList) {
                Result.success(BrowseContent.Shares(listShares(client, server)))
            } else {
                val relative = location.normalizedPath
                val raw = client.listDirectory(location.share, relative)
                coroutineContext.ensureActive()
                val filtered = filterAndMap(serverId, location.share, raw)
                Result.success(BrowseContent.Directory(filtered))
            }
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun ensureSession(
        server: SavedServer,
        requestedKey: BrowseConnectionKey,
    ): SmbClient {
        synchronized(sessionGuard) {
            if (
                !BrowseSessionReusePolicy.requiresNewSession(
                    current = sessionKey,
                    requested = requestedKey,
                    connected = sessionClient?.isConnected == true,
                )
            ) {
                return checkNotNull(sessionClient)
            }
        }
        releaseSession()
        val password = serverRepository.getPassword(server)
            ?: error("Missing credentials")
        val nextClient = clientFactory()
        try {
            nextClient.connect(
                SmbCredentials(
                    host = server.host,
                    port = server.port,
                    username = server.username,
                    password = password,
                    domain = server.domain.orEmpty(),
                ),
            )
            synchronized(sessionGuard) {
                sessionClient = nextClient
                sessionKey = requestedKey
            }
            return nextClient
        } catch (t: Throwable) {
            runCatching { nextClient.close() }
            throw t
        } finally {
            password.fill('\u0000')
        }
    }

    private fun listShares(client: SmbClient, server: SavedServer): List<RemoteEntry> {
        val userKnown = buildList {
            server.defaultShare?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val found = try {
            // Merges userKnown with common NAS share names inside SmbjClient.
            client.listShares(userKnown)
        } catch (e: SmbException) {
            throw e
        }
        val names = if (found.isNotEmpty()) {
            found
        } else {
            // Still surface the user-configured default so open can show a real error.
            userKnown
        }
        return names
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { shareName ->
                RemoteEntry(
                    serverId = server.id,
                    share = shareName,
                    path = "",
                    name = shareName,
                    isDirectory = true,
                    isShare = true,
                )
            }
    }

    private fun filterAndMap(
        serverId: String,
        share: String,
        entries: List<SmbEntry>,
    ): List<RemoteEntry> {
        val mapped = entries.mapNotNull { entry ->
            if (entry.isDirectory) {
                RemoteEntry(
                    serverId = serverId,
                    share = share,
                    path = SmbPathUtils.normalizeRelative(entry.path),
                    name = entry.name,
                    isDirectory = true,
                    sizeBytes = null,
                    modifiedTimeMs = entry.lastModifiedEpochMs.takeIf { it > 0 },
                    isShare = false,
                )
            } else if (MediaExtensions.isBrowsableMediaFile(entry.name)) {
                RemoteEntry(
                    serverId = serverId,
                    share = share,
                    path = SmbPathUtils.normalizeRelative(entry.path),
                    name = entry.name,
                    isDirectory = false,
                    sizeBytes = entry.sizeBytes.takeIf { it >= 0 },
                    modifiedTimeMs = entry.lastModifiedEpochMs.takeIf { it > 0 },
                    isShare = false,
                )
            } else {
                null
            }
        }
        return sortRemoteEntries(mapped)
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 12_000L
        const val MAX_ATTEMPTS = 2

        /**
         * Directories (and shares) first, then case-insensitive name.
         * Mirrors [SmbPathUtils.sortEntries] for [RemoteEntry].
         */
        fun sortRemoteEntries(entries: List<RemoteEntry>): List<RemoteEntry> =
            entries.sortedWith(
                compareBy<RemoteEntry> { !it.isDirectory && !it.isShare }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
    }
}

internal data class BrowseConnectionKey(
    val serverId: String,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String,
    val credentialAlias: String,
) {
    companion object {
        fun from(server: SavedServer): BrowseConnectionKey = BrowseConnectionKey(
            serverId = server.id,
            host = server.host,
            port = server.port,
            username = server.username,
            domain = server.domain.orEmpty(),
            credentialAlias = server.credentialAlias,
        )
    }
}

internal object BrowseSessionReusePolicy {
    fun requiresNewSession(
        current: BrowseConnectionKey?,
        requested: BrowseConnectionKey,
        connected: Boolean,
    ): Boolean = !connected || current != requested
}

internal object BrowseSessionRetryPolicy {
    fun shouldRetry(error: Throwable): Boolean {
        if (error is CancellationException) return false
        val smb = error as? SmbException ?: return false
        return smb.error is SmbError.Disconnected || smb.error is SmbError.Network
    }
}
