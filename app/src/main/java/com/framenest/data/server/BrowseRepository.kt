package com.framenest.data.server

import com.framenest.core.model.MediaExtensions
import com.framenest.core.model.RemoteEntry
import com.framenest.core.model.RemoteLocation
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbEntry
import com.framenest.smb.SmbException
import com.framenest.smb.SmbPathUtils
import com.framenest.smb.SmbjClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * SMB browse operations for a saved server.
 *
 * Listings run on [ioDispatcher]. Consecutive navigation on the same saved server
 * reuses one serialized SMB session; configuration changes and SMB errors discard it.
 */
class BrowseRepository(
    private val serverRepository: ServerRepository,
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val sessionMutex = Mutex()
    private var sessionKey: BrowseConnectionKey? = null
    private var sessionClient: SmbClient? = null

    sealed class BrowseContent {
        data class Shares(val entries: List<RemoteEntry>) : BrowseContent()
        data class Directory(val entries: List<RemoteEntry>) : BrowseContent()
    }

    suspend fun load(
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> = withContext(ioDispatcher) {
        sessionMutex.withLock {
            loadWithSession(serverId, location)
        }
    }

    private suspend fun loadWithSession(
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> {
        val server = serverRepository.getServer(serverId)
            ?: return Result.failure(IllegalArgumentException("Server not found"))
        val requestedKey = BrowseConnectionKey.from(server)
        try {
            val client = ensureSession(server, requestedKey)
            coroutineContext.ensureActive()
            return if (location.isShareList) {
                Result.success(BrowseContent.Shares(listShares(client, server)))
            } else {
                val relative = location.normalizedPath
                val raw = client.listDirectory(location.share, relative)
                coroutineContext.ensureActive()
                val filtered = filterAndMap(serverId, location.share, raw)
                Result.success(BrowseContent.Directory(filtered))
            }
        } catch (e: SmbException) {
            invalidateSession()
            return Result.failure(e)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Result.failure(t)
        }
    }

    private suspend fun ensureSession(
        server: SavedServer,
        requestedKey: BrowseConnectionKey,
    ): SmbClient {
        if (
            !BrowseSessionReusePolicy.requiresNewSession(
                current = sessionKey,
                requested = requestedKey,
                connected = sessionClient?.isConnected == true,
            )
        ) {
            return checkNotNull(sessionClient)
        }
        invalidateSession()
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
            sessionClient = nextClient
            sessionKey = requestedKey
            return nextClient
        } catch (t: Throwable) {
            runCatching { nextClient.close() }
            throw t
        } finally {
            password.fill('\u0000')
        }
    }

    private fun invalidateSession() {
        val stale = sessionClient
        sessionClient = null
        sessionKey = null
        runCatching { stale?.close() }
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
