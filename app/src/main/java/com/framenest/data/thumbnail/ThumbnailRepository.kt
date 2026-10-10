package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.framenest.core.model.RemoteEntry
import com.framenest.data.server.ServerRepository
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbRandomAccess
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * List thumbnail facade: disk cache + limited-concurrency SMB extract workers.
 *
 * Default concurrency is **3**. Settings may lower it to 1 or 2.
 * Each worker reuses one player. Rows do not get their own.
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

    private data class ScheduledRetry(
        val token: Any,
        val job: Job,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val uiStates = ConcurrentHashMap<String, MutableStateFlow<ThumbnailUiState>>()
    private val emptyUiState: StateFlow<ThumbnailUiState> =
        MutableStateFlow(ThumbnailUiState.None)
    private val backoff = ConcurrentHashMap<String, ThumbnailBackoffState>()
    private val interestCounts = ConcurrentHashMap<String, Int>()
    private val interestedRequests = ConcurrentHashMap<String, ThumbnailRequest>()
    private val workQueue = ThumbnailWorkQueue<QueuedWork>()
    private val scheduledRetries = ConcurrentHashMap<String, ScheduledRetry>()
    private val generation = ThumbnailCacheGeneration()
    private val cacheMutationLock = Any()

    private val workAvailable = Channel<Unit>(Channel.CONFLATED)
    private val workerLock = Any()
    private val workerJobs = mutableListOf<Job>()

    init {
        ensureWorkers()
    }

    /**
     * Observe UI state for [entry]. Emits [ThumbnailUiState.Ready] only when a
     * cached (or just-generated) bitmap is available.
     */
    fun observe(entry: RemoteEntry): StateFlow<ThumbnailUiState> {
        val request = ThumbnailRequest.fromVideoEntry(entry)
            ?: return emptyUiState
        val digest = request.key.digest()
        val flow = uiStates.getOrPut(digest) {
            MutableStateFlow(initialState(request.key))
        }
        pruneUninterestedUiStates(keepDigest = digest)
        return flow
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
            current.value = ThumbnailUiState.Ready(cached, diskCache.cachedDurationMs(request.key))
            return
        }
        showLegacyCover(request)
        val state = backoff[digest] ?: ThumbnailBackoffState()
        if (!state.isEligible(timeSource())) {
            if (state.permanentlyFailed && current.value !is ThumbnailUiState.Ready) {
                current.value = ThumbnailUiState.Failed
            } else if (!state.permanentlyFailed) {
                scheduleRetry(
                    work = QueuedWork(generation.current(), request),
                    state = state,
                )
            }
            return
        }
        if (current.value !is ThumbnailUiState.Loading && current.value !is ThumbnailUiState.Ready) {
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
            workQueue.cancelPending(ThumbnailWorkKey(generation.current(), digest))
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
        workQueue.clear()
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
        workAvailable.close()
        scope.cancel()
    }

    private fun initialState(key: ThumbnailKey): ThumbnailUiState {
        val bitmap = diskCache.getMemoryBitmap(key) ?: return ThumbnailUiState.None
        return ThumbnailUiState.Ready(bitmap, diskCache.cachedDurationMs(key))
    }

    private fun enqueue(request: ThumbnailRequest, workGeneration: Long) {
        if (!generation.isCurrent(workGeneration)) return
        val digest = request.key.digest()
        val key = ThumbnailWorkKey(workGeneration, digest)
        if (!workQueue.offer(key, QueuedWork(workGeneration, request))) return
        val result = workAvailable.trySend(Unit)
        if (result.isFailure) {
            workQueue.cancelPending(key)
            Log.w(TAG, "thumbnail queue send failed")
            return
        }
        ensureWorkers()
    }

    /** Current configured concurrency (1–3). */
    fun configuredConcurrency(): Int =
        concurrencyProvider()
            .coerceIn(UserPreferences.MIN_THUMB_CONCURRENCY, UserPreferences.MAX_THUMB_CONCURRENCY)

    /**
     * Ensure worker count matches [configuredConcurrency] (1–3).
     * Multiple coroutines consume the same channel → true parallel extracts.
     * Safe to call after the user changes Settings.
     */
    fun ensureWorkers() {
        val desired = configuredConcurrency()
        synchronized(workerLock) {
            workerJobs.removeAll { !it.isActive }
            while (workerJobs.size < desired) {
                val job = scope.launch {
                    val session = ThumbnailWorkerSession()
                    try {
                        for (ignored in workAvailable) {
                            val scheduled = workQueue.takeNext() ?: continue
                            signalNextWorkerIfPending()
                            processOne(scheduled.first, scheduled.second, session)
                            signalNextWorkerIfPending()
                        }
                    } finally {
                        session.close()
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

    private fun signalNextWorkerIfPending() {
        if (workQueue.hasPending()) workAvailable.trySend(Unit)
    }

    private suspend fun processOne(
        workKey: ThumbnailWorkKey,
        work: QueuedWork,
        session: ThumbnailWorkerSession,
    ) {
        val request = work.request
        val digest = request.key.digest()
        var requeueAfterCancellation = false
        try {
            if (!generation.isCurrent(work.generation)) return
            coroutineContext.ensureActive()
            if ((interestCounts[digest] ?: 0) <= 0) return
            diskCache.getBitmap(request.key)?.let { bitmap ->
                publish(work, ThumbnailUiState.Ready(bitmap, diskCache.readDurationMs(request.key)))
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

            if (uiStates[digest]?.value !is ThumbnailUiState.Ready) {
                publish(work, ThumbnailUiState.Loading)
            }
            val success = generate(work, session)
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
            workQueue.finish(workKey)
            if (requeueAfterCancellation) enqueue(request, work.generation)
        }
    }

    private suspend fun generate(
        work: QueuedWork,
        session: ThumbnailWorkerSession,
    ): Boolean {
        val request = work.request
        return try {
            coroutineContext.ensureActive()
            val result = session.cover(request) ?: return false
            coroutineContext.ensureActive()
            val accepted = try {
                synchronized(cacheMutationLock) {
                    if (!generation.isCurrent(work.generation)) {
                        false
                    } else {
                        diskCache.put(
                            request.key,
                            result.jpegBytes,
                            result.bitmap,
                            result.durationMs,
                        )
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
            publish(work, ThumbnailUiState.Ready(result.bitmap, result.durationMs))
            Log.d(
                TAG,
                "thumb ok digest=${request.key.digest().take(8)} " +
                    "t=${result.usedTimestampMs}ms dur=${result.durationMs}ms",
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            session.invalidate()
            Log.w(TAG, "thumb generate failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /** One SMB session and one reused player per worker; never shared across workers. */
    private inner class ThumbnailWorkerSession : AutoCloseable {
        private var key: ThumbnailConnectionKey? = null
        private var client: SmbClient? = null
        private var coverGrabber: ThumbnailVlcCover? = null

        /**
         * One direct SMB frame. Null leaves the row on the poster icon.
         * The list does not fall back to a file-prefix download.
         */
        suspend fun cover(request: ThumbnailRequest): ThumbnailFrameExtractor.ExtractResult? {
            val server = serverRepository.getServer(request.key.serverId) ?: return null
            val password = serverRepository.getPassword(server) ?: CharArray(0)
            val job = coroutineContext[Job]
            return try {
                val grabber = coverGrabber ?: ThumbnailVlcCover(appContext).also { coverGrabber = it }
                grabber.frame(
                    host = server.host,
                    port = server.port,
                    share = request.share,
                    path = request.path,
                    username = server.username,
                    password = password,
                    domain = server.domain.orEmpty(),
                    active = { job?.isActive != false },
                )
            } finally {
                password.fill('\u0000')
            }
        }

        suspend fun extractPrefix(request: ThumbnailRequest): ThumbnailFrameExtractor.ExtractResult? {
            val randomAccess = open(request) ?: return null
            return randomAccess.use { raf -> extract(raf) }
        }

        fun extract(randomAccess: SmbRandomAccess): ThumbnailFrameExtractor.ExtractResult? =
            extractor.extract(randomAccess)

        suspend fun open(request: ThumbnailRequest): SmbRandomAccess? {
            val server = serverRepository.getServer(request.key.serverId) ?: return null
            val requestedKey = ThumbnailConnectionKey(
                serverId = server.id,
                host = server.host,
                port = server.port,
                username = server.username,
                domain = server.domain.orEmpty(),
                requireEncryption = server.requireEncryption,
                credentialAlias = server.credentialAlias,
            )
            if (
                ThumbnailSessionReusePolicy.requiresNewSession(
                    current = key,
                    requested = requestedKey,
                    connected = client?.isConnected == true,
                )
            ) {
                invalidate()
                val password = serverRepository.getPassword(server) ?: return null
                val nextClient = clientFactory()
                try {
                    nextClient.connect(
                        SmbCredentials(
                            host = server.host,
                            port = server.port,
                            username = server.username,
                            password = password,
                            domain = server.domain.orEmpty(),
                            requireEncryption = server.requireEncryption,
                        ),
                    )
                    client = nextClient
                    key = requestedKey
                } catch (t: Throwable) {
                    runCatching { nextClient.close() }
                    throw t
                } finally {
                    password.fill('\u0000')
                }
            }
            return client?.openRandomAccess(request.share, request.path)
        }

        fun invalidate() {
            val stale = client
            client = null
            key = null
            runCatching { stale?.close() }
        }

        override fun close() {
            invalidate()
            val grabber = coverGrabber
            coverGrabber = null
            runCatching { grabber?.close() }
        }
    }

    private fun publish(work: QueuedWork, state: ThumbnailUiState) {
        if (!generation.isCurrent(work.generation)) return
        val digest = work.request.key.digest()
        if ((interestCounts[digest] ?: 0) <= 0) return
        val flow = uiStates[digest] ?: return
        // A provisional legacy cover stays up when this attempt fails or is still loading.
        if (flow.value is ThumbnailUiState.Ready && state !is ThumbnailUiState.Ready) return
        flow.value = state
    }

    private fun showLegacyCover(request: ThumbnailRequest) {
        val workGeneration = generation.current()
        val digest = request.key.digest()
        scope.launch {
            if (!generation.isCurrent(workGeneration)) return@launch
            if ((interestCounts[digest] ?: 0) <= 0) return@launch
            val bitmap = try {
                diskCache.readLegacyBitmap(request.key)
            } catch (t: Throwable) {
                Log.d(TAG, "legacy cover skipped: ${t.javaClass.simpleName}")
                null
            } ?: return@launch
            val published = publishLegacy(workGeneration, request, bitmap)
            if (!published && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun publishLegacy(
        workGeneration: Long,
        request: ThumbnailRequest,
        bitmap: Bitmap,
    ): Boolean {
        if (!generation.isCurrent(workGeneration)) return false
        val digest = request.key.digest()
        if ((interestCounts[digest] ?: 0) <= 0) return false
        val flow = uiStates[digest] ?: return false
        if (flow.value is ThumbnailUiState.Ready) return false
        if (diskCache.getMemoryBitmap(request.key) != null || diskCache.has(request.key)) return false
        flow.value = ThumbnailUiState.Ready(bitmap, 0L)
        return true
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

internal data class ThumbnailConnectionKey(
    val serverId: String,
    val host: String,
    val port: Int,
    val username: String,
    val domain: String,
    val credentialAlias: String,
    val requireEncryption: Boolean = true,
)

internal object ThumbnailSessionReusePolicy {
    fun requiresNewSession(
        current: ThumbnailConnectionKey?,
        requested: ThumbnailConnectionKey,
        connected: Boolean,
    ): Boolean = !connected || current != requested
}
