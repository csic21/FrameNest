package com.framenest.smb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One auxiliary-operation generation. Register before connect/list/read so retiring
 * the generation can abort blocked I/O. Retirement only detaches under the lock;
 * transport shutdown always runs on the cleanup dispatcher, never the caller/UI.
 */
internal class SmbTransportOwner(
    private val dispatchAbort: (() -> Unit) -> Unit = { cleanup ->
        cleanupScope.launch { cleanup() }
    },
) {
    private val lock = Any()
    private val clients = LinkedHashSet<SmbClient>()
    private var retired = false

    fun register(client: SmbClient) {
        val accepted = synchronized(lock) { !retired && clients.add(client) }
        if (!accepted) {
            dispatchAbort { runCatching { client.abort() } }
            throw CancellationException("Auxiliary SMB operation retired")
        }
    }

    fun currentClient(): SmbClient? = synchronized(lock) {
        if (retired) null else clients.lastOrNull()
    }

    fun ensureActive() {
        if (synchronized(lock) { retired }) throw CancellationException("Auxiliary SMB operation retired")
    }

    /** Called on an I/O worker after normal completion; does not touch another generation. */
    fun release(client: SmbClient) {
        val owned = synchronized(lock) { clients.remove(client) }
        if (owned) runCatching { client.abort() }
    }

    fun retire() {
        val detached = synchronized(lock) {
            if (retired) return
            retired = true
            clients.toList().also { clients.clear() }
        }
        // Separate cleanup jobs prevent one legacy/blocking abort from delaying others.
        detached.forEach { client -> dispatchAbort { runCatching { client.abort() } } }
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
