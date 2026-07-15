package com.framenest.data.thumbnail

import android.content.Context
import android.util.Log
import com.framenest.core.model.RemoteEntry
import com.framenest.data.server.ServerRepository
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbjClient
import com.framenest.data.settings.UserPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * List thumbnail facade: disk cache + limited-concurrency SMB extract workers.
 *
 * Default concurrency is **1** (architecture D2). Settings may raise to 2.
 * UI must only display [ThumbnailUiState.Ready] bitmaps; never one player per row.
 */
class ThumbnailRepository(
    context: Context,
    private val serverRepository: ServerRepository,
    private val diskCache: ThumbnailDiskCache = ThumbnailDiskCache.fromContext(context),
    private val extractor: ThumbnailFrameExtractor = ThumbnailFrameExtractor(context.applicationContext),
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val concurrencyProvider: () -> Int = { UserPreferences.DEFAULT_THUMB_CONCURRENCY },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    private data class QueuedWork(
        val generation: Long,
        val request: ThumbnailRequest,
    )

    private data class QueuedWorkKey(
        val generation: Long,
        val digest: String,
    )

    private data class ScheduledRetry(
        val token: Any,
        val job: Job,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val uiStates = ConcurrentHashMap<String, MutableStateFlow<ThumbnailUiState>>()
    private val backoff = ConcurrentHashMap<String, ThumbnailBackoffState>()
    private val interestCounts = ConcurrentHashMap<String, Int>()
    private val interestedRequests = ConcurrentHashMap<String, ThumbnailRequest>()
    private val queuedWork = ConcurrentHashMap.newKeySet<QueuedWorkKey>()
    private val scheduledRetries = ConcurrentHashMap<String, ScheduledRetry>()
    private val generation = ThumbnailCacheGeneration()
    private val cacheMutationLock = Any()

    private val queue = Channel<QueuedWork>(Channel.UNLIMITED)
    private val workerLock = Any()
    private val workerJobs = mutableListOf<Job>()

    init {
        ensureWorkers()
    }

    /**
     * Observe UI state for [entry]. Emits [ThumbnailUiState.Ready] only when a
     * cached (or just-generated) bitmap is available.
     */
    fun observe(entry: RemoteEntry): Flow<ThumbnailUiState> {
        val request = ThumbnailRequest.fromVideoEntry(entry)
            ?: return MutableStateFlow(ThumbnailUiState.None)
        val digest = request.key.digest()
        val flow = uiStates.getOrPut(digest) {
            MutableStateFlow(initialState(request.key))
        }
        pruneUninterestedUiStates(keepDigest = digest)
        return flow.asStateFlow()
    }

    /**
     * Register interest in a row (list item entered composition).
     * Enqueues generation when missing and eligible.
     */
    fun retain(entry: RemoteEntry) {
        val request = ThumbnailRequest.fromVideoEntry(entry) ?: return
        val digest = request.key.digest()
        interestCounts.merge(digest, 1, Int::plus)
        interestedRequests[digest] = request
        val current = uiStates.getOrPut(digest) {
            MutableStateFlow(initialState(request.key))
        }
        pruneUninterestedUiStates(keepDigest = digest)
        // Memory hits are safe on the UI thread. Disk decode stays on an IO worker.
        val cached = diskCache.getMemoryBitmap(request.key)
        if (cached != null) {
            current.value = ThumbnailUiState.Ready(cached)
            return
        }
        val state = backoff[digest] ?: ThumbnailBackoffState()
        if (!state.isEligible(timeSource())) {
            if (state.permanentlyFailed) {
                current.value = ThumbnailUiState.Failed
            } else {
                scheduleRetry(
                    work = QueuedWork(generation.current(), request),
                    state = state,
                )
            }
            return
        }
        if (current.value is ThumbnailUiState.Ready) return
        if (current.value !is ThumbnailUiState.Loading) {
            current.value = ThumbnailUiState.Loading
        }
        enqueue(request, generation.current())
    }

    /**
     * Drop interest when a row leaves composition. Its UI flow is removed so
     * a long list cannot retain one bitmap per item. A running extraction may
     * still populate the bounded memory cache and disk cache.
     */
    fun release(entry: RemoteEntry) {
        val request = ThumbnailRequest.fromVideoEntry(entry) ?: return
        val digest = request.key.digest()
        val remaining = interestCounts.compute(digest) { _, count ->
            val next = (count ?: 0) - 1
            if (next <= 0) null else next
        }
        if (remaining == null) {
            interestedRequests.remove(digest)
            scheduledRetries.remove(digest)?.job?.cancel()
            uiStates.remove(digest)?.value = ThumbnailUiState.None
        }
    }

    /**
     * Clear disk + memory cache and invalidate all queued/in-flight work.
     * An old extraction that returns after this call cannot publish or write
     * back because cache mutation is guarded by its generation token.
     */
    fun clearCache() {
        val nextGeneration = synchronized(cacheMutationLock) {
            val next = generation.advance()
            diskCache.clear()
            next
        }
        scheduledRetries.values.forEach { it.job.cancel() }
        scheduledRetries.clear()
        synchronized(workerLock) {
            workerJobs.forEach { it.cancel() }
            workerJobs.clear()
        }
        while (queue.tryReceive().isSuccess) {
            // Discard queued work from the invalidated generation.
        }
        queuedWork.clear()
        backoff.clear()
        uiStates.values.forEach { flow ->
            flow.value = ThumbnailUiState.None
        }
        uiStates.keys.removeAll { (interestCounts[it] ?: 0) <= 0 }
        ensureWorkers()
        interestedRequests.values.forEach { request ->
            enqueue(request, nextGeneration)
        }
        Log.i(TAG, "thumbnail cache cleared")
    }

    fun approximateCacheSizeBytes(): Long = diskCache.approximateSizeBytes()

    fun close() {
        scheduledRetries.values.forEach { it.job.cancel() }
        scheduledRetries.clear()
        synchronized(workerLock) {
            workerJobs.forEach { it.cancel() }
            workerJobs.clear()
        }
        queue.close()
        scope.cancel()
    }

    private fun initialState(key: ThumbnailKey): ThumbnailUiState {
        val bitmap = diskCache.getMemoryBitmap(key) ?: return ThumbnailUiState.None
        return ThumbnailUiState.Ready(bitmap)
    }

    private fun enqueue(request: ThumbnailRequest, workGeneration: Long) {
        if (!generation.isCurrent(workGeneration)) return
        val digest = request.key.digest()
        val key = QueuedWorkKey(workGeneration, digest)
        if (!queuedWork.add(key)) return
        val result = queue.trySend(QueuedWork(workGeneration, request))
        if (result.isFailure) {
            queuedWork.remove(key)
            Log.w(TAG, "thumbnail queue send failed")
            return
        }
        ensureWorkers()
    }

    /** Current configured concurrency (1–2). */
    fun configuredConcurrency(): Int =
        concurrencyProvider()
            .coerceIn(UserPreferences.MIN_THUMB_CONCURRENCY, UserPreferences.MAX_THUMB_CONCURRENCY)

    /**
     * Ensure worker count matches [configuredConcurrency] (1–2).
     * Multiple coroutines consume the same channel → true parallel extracts.
     * Safe to call after the user changes Settings.
     */
    fun ensureWorkers() {
        val desired = configuredConcurrency()
        synchronized(workerLock) {
            workerJobs.removeAll { !it.isActive }
            while (workerJobs.size < desired) {
                val job = scope.launch {
                    for (work in queue) {
                        processOne(work)
                    }
                }
                workerJobs += job
            }
            // Scale down: cancel idle extras (in-flight work finishes via cancellation cooperative points).
            while (workerJobs.size > desired) {
                workerJobs.removeAt(workerJobs.lastIndex).cancel()
            }
        }
    }

    private suspend fun processOne(work: QueuedWork) {
        val request = work.request
        val digest = request.key.digest()
        val workKey = QueuedWorkKey(work.generation, digest)
        var requeueAfterCancellation = false
        try {
            if (!generation.isCurrent(work.generation)) return
            coroutineContext.ensureActive()
            if ((interestCounts[digest] ?: 0) <= 0) return
            diskCache.getBitmap(request.key)?.let { bitmap ->
                publish(work, ThumbnailUiState.Ready(bitmap))
                return
            }
            val state = backoff[digest] ?: ThumbnailBackoffState()
            if (!state.isEligible(timeSource())) {
                if (state.permanentlyFailed) {
                    publish(work, ThumbnailUiState.Failed)
                } else {
                    scheduleRetry(work, state)
                }
                return
            }

            publish(work, ThumbnailUiState.Loading)
            val success = generate(work)
            if (!generation.isCurrent(work.generation)) return
            if (success) {
                backoff[digest] = ThumbnailBackoff.afterSuccess()
            } else {
                val next = ThumbnailBackoff.afterFailure(state, timeSource())
                backoff[digest] = next
                publish(
                    work,
                    if (next.permanentlyFailed) ThumbnailUiState.Failed else ThumbnailUiState.None,
                )
                if (!next.permanentlyFailed) scheduleRetry(work, next)
            }
        } catch (cancelled: CancellationException) {
            requeueAfterCancellation =
                generation.isCurrent(work.generation) && (interestCounts[digest] ?: 0) > 0
            throw cancelled
        } finally {
            queuedWork.remove(workKey)
            if (requeueAfterCancellation) enqueue(request, work.generation)
        }
    }

    private suspend fun generate(work: QueuedWork): Boolean {
        val request = work.request
        val server = serverRepository.getServer(request.key.serverId) ?: return false
        val password = serverRepository.getPassword(server) ?: return false
        val client = clientFactory()
        val credentials = SmbCredentials(
            host = server.host,
            port = server.port,
            username = server.username,
            password = password,
            domain = server.domain.orEmpty(),
        )
        return try {
            client.connect(credentials)
            coroutineContext.ensureActive()
            client.openRandomAccess(request.share, request.path).use { raf ->
                coroutineContext.ensureActive()
                val label = "thumb://${request.share}/${request.path.trimStart('/')}"
                val result = extractor.extract(raf, debugLabel = label) ?: return false
                coroutineContext.ensureActive()
                val accepted = try {
                    synchronized(cacheMutationLock) {
                        if (!generation.isCurrent(work.generation)) {
                            false
                        } else {
                            diskCache.put(request.key, result.jpegBytes, result.bitmap)
                            true
                        }
                    }
                } catch (t: Throwable) {
                    if (!result.bitmap.isRecycled) result.bitmap.recycle()
                    throw t
                }
                if (!accepted) {
                    if (!result.bitmap.isRecycled) result.bitmap.recycle()
                    return false
                }
                publish(work, ThumbnailUiState.Ready(result.bitmap))
                Log.d(
                    TAG,
                    "thumb ok digest=${request.key.digest().take(8)} " +
                        "t=${result.usedTimestampMs}ms dur=${result.durationMs}ms",
                )
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "thumb generate failed: ${t.javaClass.simpleName}")
            false
        } finally {
            runCatching { client.close() }
            password.fill('\u0000')
        }
    }

    private fun publish(work: QueuedWork, state: ThumbnailUiState) {
        if (!generation.isCurrent(work.generation)) return
        val digest = work.request.key.digest()
        if ((interestCounts[digest] ?: 0) <= 0) return
        uiStates[digest]?.value = state
    }

    private fun scheduleRetry(work: QueuedWork, state: ThumbnailBackoffState) {
        if (state.permanentlyFailed || !generation.isCurrent(work.generation)) return
        val digest = work.request.key.digest()
        if ((interestCounts[digest] ?: 0) <= 0) return
        val delayMs = (state.nextEligibleAtMs - timeSource()).coerceAtLeast(0L)
        val token = Any()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(delayMs)
                if (generation.isCurrent(work.generation) && (interestCounts[digest] ?: 0) > 0) {
                    val latestRequest = interestedRequests[digest] ?: return@launch
                    enqueue(latestRequest, work.generation)
                }
            } finally {
                scheduledRetries.compute(digest) { _, current ->
                    if (current?.token === token) null else current
                }
            }
        }
        val registration = ScheduledRetry(token, job)
        scheduledRetries.put(digest, registration)?.job?.cancel()
        job.start()
    }

    private fun pruneUninterestedUiStates(keepDigest: String) {
        while (uiStates.size > MAX_RETAINED_UI_STATES) {
            val victim = uiStates.keys.firstOrNull { digest ->
                digest != keepDigest && (interestCounts[digest] ?: 0) <= 0
            } ?: return
            uiStates.remove(victim)?.value = ThumbnailUiState.None
        }
    }

    companion object {
        private const val TAG = "FrameNestThumb"
        private const val MAX_RETAINED_UI_STATES = 64
    }
}
