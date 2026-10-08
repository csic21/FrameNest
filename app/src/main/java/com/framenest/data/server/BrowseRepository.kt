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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listings reuse a serialized session until cancellation, timeout or configuration
 * change. Detaching a generation is synchronous; all transport teardown is queued
 * on [cleanupDispatcher], never on the caller (which may be the main thread).
 */
class BrowseRepository(
    private val serverRepository: ServerRepository,
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val watchdogDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    cleanupDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val sessionGuard = Any()
    private var generation = SessionGeneration()
    private val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)

    // A separate mutex for each generation lets reentry proceed even if old I/O
    // or a third-party client's close is still unwinding on a worker thread.
    internal class SessionGeneration {
        val mutex = Mutex()
        var retired = false
        var session: OwnedSession? = null
        var request: Request? = null
    }

    internal class OwnedSession(val key: BrowseConnectionKey, val client: SmbClient) {
        val cleanupStarted = AtomicBoolean(false)
        var connected = false
    }

    /** Identifies a caller's last load so late screen disposal cannot close its successor. */
    class Request internal constructor(internal val generation: SessionGeneration)

    fun newRequest(): Request = synchronized(sessionGuard) {
        Request(generation).also { generation.request = it }
    }

    sealed class BrowseContent {
        data class Shares(val entries: List<RemoteEntry>) : BrowseContent()
        data class Directory(val entries: List<RemoteEntry>) : BrowseContent()
    }

    suspend fun load(
        serverId: String,
        location: RemoteLocation,
        request: Request = newRequest(),
    ): Result<BrowseContent> = withContext(ioDispatcher) {
        val owner = request.generation
        owner.mutex.withLock {
            ensureCurrent(owner)
            loadSerialized(owner, serverId, location)
        }
    }

    /**
     * Detach immediately, including a client still connecting. A supplied request
     * only releases its own current generation; stale screen cleanup is harmless.
     * The no-argument form explicitly releases the repository's current session.
     */
    fun releaseSession(request: Request? = null) {
        val stale = synchronized(sessionGuard) {
            val owner = generation
            if (request != null && (request.generation !== owner || owner.request !== request)) {
                return
            }
            owner.retired = true
            generation = SessionGeneration()
            owner.session.also { owner.session = null }
        }
        stale?.let(::scheduleAbort)
    }

    private fun abortSession(owner: SessionGeneration, owned: OwnedSession) {
        synchronized(sessionGuard) {
            if (owner.session === owned) owner.session = null
        }
        scheduleAbort(owned)
    }

    private fun scheduleAbort(owned: OwnedSession) {
        if (!owned.cleanupStarted.compareAndSet(false, true)) return
        // This scope outlives the cancelled request / ViewModel. Never launch
        // teardown in viewModelScope or wait for it while holding sessionGuard.
        cleanupScope.launch { runCatching { owned.client.abort() } }
    }

    private fun ensureCurrent(owner: SessionGeneration, owned: OwnedSession? = null) {
        synchronized(sessionGuard) {
            if (owner.retired) throw CancellationException("Browse request released")
            if (owned != null && owner.session !== owned) {
                throw SmbException(SmbError.Disconnected("Browse session expired"))
            }
        }
    }

    private fun logBrowse(message: String) {
        runCatching { SmbLog.w(message) }
    }

    private suspend fun loadSerialized(
        owner: SessionGeneration,
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> {
        var lastFailure: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            ensureCurrent(owner)
            try {
                return loadOnce(owner, serverId, location)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // A released generation must never retry and reconnect itself.
                coroutineContext.ensureActive()
                ensureCurrent(owner)
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
        owner: SessionGeneration,
        serverId: String,
        location: RemoteLocation,
    ): Result<BrowseContent> {
        val server = serverRepository.getServer(serverId)
            ?: return Result.failure(IllegalArgumentException("Server not found"))
        val owned = acquireSession(owner, BrowseConnectionKey.from(server))
        try {
            return coroutineScope {
                val finished = AtomicBoolean(false)
                // Undispatched start installs cancellation observation before any
                // blocking connect/list. Cancellation of this child schedules an
                // abort even while its parent is stuck in synchronous SMB I/O.
                val watchdog = launch(watchdogDispatcher, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        delay(operationTimeoutMs)
                        logBrowse("Browse SMB operation timed out after ${operationTimeoutMs}ms")
                    } finally {
                        if (!finished.get()) abortSession(owner, owned)
                    }
                }
                try {
                    ensureConnected(owner, owned, server)
                    val content = if (location.isShareList) {
                        BrowseContent.Shares(listShares(owned.client, server))
                    } else {
                        val raw = owned.client.listDirectory(location.share, location.normalizedPath)
                        BrowseContent.Directory(filterAndMap(serverId, location.share, raw))
                    }
                    coroutineContext.ensureActive()
                    ensureCurrent(owner, owned)
                    Result.success(content)
                } finally {
                    finished.set(true)
                    watchdog.cancel()
                }
            }
        } catch (t: Throwable) {
            abortSession(owner, owned)
            throw t
        }
    }

    private fun acquireSession(owner: SessionGeneration, requestedKey: BrowseConnectionKey): OwnedSession {
        val current = synchronized(sessionGuard) {
            ensureCurrent(owner)
            owner.session
        }
        // Client state can itself acquire transport locks. Do not call it while
        // holding the short guard used by main-thread releaseSession.
        if (current != null && !BrowseSessionReusePolicy.requiresNewSession(
                current.key, requestedKey, current.connected && current.client.isConnected,
            )
        ) {
            ensureCurrent(owner, current)
            return current
        }
        val next = OwnedSession(requestedKey, clientFactory())
        val stale = try {
            synchronized(sessionGuard) {
                ensureCurrent(owner)
                owner.session.also { owner.session = next }
            }
        } catch (t: Throwable) {
            scheduleAbort(next)
            throw t
        }
        stale?.let(::scheduleAbort)
        return next
    }

    private suspend fun ensureConnected(owner: SessionGeneration, owned: OwnedSession, server: SavedServer) {
        coroutineContext.ensureActive()
        ensureCurrent(owner, owned)
        if (owned.connected && owned.client.isConnected) return
        val password = serverRepository.getPassword(server) ?: error("Missing credentials")
        try {
            coroutineContext.ensureActive()
            ensureCurrent(owner, owned)
            owned.client.connect(
                SmbCredentials(
                    host = server.host,
                    port = server.port,
                    username = server.username,
                    password = password,
                    domain = server.domain.orEmpty(),
                ),
            )
            coroutineContext.ensureActive()
            ensureCurrent(owner, owned)
            owned.connected = true
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
