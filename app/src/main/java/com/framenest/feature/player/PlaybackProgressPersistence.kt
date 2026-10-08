package com.framenest.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Orders snapshots across destination instances, including an immediate reopen of the same file. */
internal class PlaybackProgressPersistence(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()

    fun save(write: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // Acquire/queue the lock at the call site before dispatching. A final save
        // must never overtake or cancel a still-running periodic/background save.
        mutex.withLock { withContext(scope.coroutineContext.minusKey(Job)) { write() } }
    }

    suspend fun <T> afterSaves(read: suspend () -> T): T = mutex.withLock { read() }

    companion object {
        val Shared = PlaybackProgressPersistence()
    }
}
