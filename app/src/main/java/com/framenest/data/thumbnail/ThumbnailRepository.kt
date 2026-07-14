package com.framenest.data.thumbnail

import android.content.Context
import android.util.Log
import com.framenest.core.model.RemoteEntry
import com.framenest.data.server.ServerRepository
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbjClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * List thumbnail facade: disk cache + **single concurrent** SMB extract worker.
 *
 * UI must only display [ThumbnailUiState.Ready] bitmaps from cache/memory; while
 * generating, rows show a placeholder. Never creates a player instance per row.
 */
class ThumbnailRepository(
    context: Context,
    private val serverRepository: ServerRepository,
    private val diskCache: ThumbnailDiskCache = ThumbnailDiskCache.fromContext(context),
    private val extractor: ThumbnailFrameExtractor = ThumbnailFrameExtractor(context.applicationContext),
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val uiStates = ConcurrentHashMap<String, MutableStateFlow<ThumbnailUiState>>()
    private val backoff = ConcurrentHashMap<String, ThumbnailBackoffState>()
    private val interestCounts = ConcurrentHashMap<String, Int>()
    private val queuedDigests = ConcurrentHashMap.newKeySet<String>()

    private val queue = Channel<ThumbnailRequest>(Channel.UNLIMITED)
    private val workerLock = Any()
    @Volatile private var workerJob: Job? = null

    init {
        ensureWorker()
    }

    /**
     * Observe UI state for [entry]. Emits [ThumbnailUiState.Ready] only when a
     * cached (or just-generated) bitmap is available.
     */
    fun observe(entry: RemoteEntry): Flow<ThumbnailUiState> {
        val request = ThumbnailRequest.fromVideoEntry(entry)
            ?: return MutableStateFlow(ThumbnailUiState.None)
        val digest = request.key.digest()
        return uiStates.getOrPut(digest) {
            MutableStateFlow(initialState(request.key))
        }.asStateFlow()
    }

    /**
     * Register interest in a row (list item entered composition).
     * Enqueues generation when missing and eligible.
     */
    fun retain(entry: RemoteEntry) {
        val request = ThumbnailRequest.fromVideoEntry(entry) ?: return
        val digest = request.key.digest()
        interestCounts.merge(digest, 1, Int::plus)
        val current = uiStates.getOrPut(digest) {
            MutableStateFlow(initialState(request.key))
        }
        val cached = diskCache.getBitmap(request.key)
        if (cached != null) {
            current.value = ThumbnailUiState.Ready(cached)
            return
        }
        val state = backoff[digest] ?: ThumbnailBackoffState()
        if (!state.isEligible(timeSource())) {
            if (state.permanentlyFailed) {
                current.value = ThumbnailUiState.Failed
            }
            return
        }
        if (current.value is ThumbnailUiState.Ready) return
        if (current.value !is ThumbnailUiState.Loading) {
            current.value = ThumbnailUiState.Loading
        }
        enqueue(request)
    }

    /**
     * Drop interest when a row leaves composition. In-flight extract may still
     * finish and populate disk cache for the next visit (single worker).
     */
    fun release(entry: RemoteEntry) {
        val request = ThumbnailRequest.fromVideoEntry(entry) ?: return
        val digest = request.key.digest()
        interestCounts.compute(digest) { _, count ->
            val next = (count ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    /** Clear disk + memory cache and backoff bookkeeping (settings hook). */
    fun clearCache() {
        diskCache.clear()
        backoff.clear()
        queuedDigests.clear()
        uiStates.values.forEach { flow ->
            flow.value = ThumbnailUiState.None
        }
        uiStates.clear()
        Log.i(TAG, "thumbnail cache cleared")
    }

    fun approximateCacheSizeBytes(): Long = diskCache.approximateSizeBytes()

    fun close() {
        workerJob?.cancel()
        queue.close()
        scope.cancel()
    }

    private fun initialState(key: ThumbnailKey): ThumbnailUiState {
        val bitmap = diskCache.getBitmap(key) ?: return ThumbnailUiState.None
        return ThumbnailUiState.Ready(bitmap)
    }

    private fun enqueue(request: ThumbnailRequest) {
        val digest = request.key.digest()
        if (!queuedDigests.add(digest)) return
        val result = queue.trySend(request)
        if (result.isFailure) {
            queuedDigests.remove(digest)
            Log.w(TAG, "thumbnail queue send failed")
            return
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        synchronized(workerLock) {
            if (workerJob?.isActive == true) return
            workerJob = scope.launch {
                // Single consumer → at most one extract at a time.
                for (request in queue) {
                    processOne(request)
                }
            }
        }
    }

    private suspend fun processOne(request: ThumbnailRequest) {
        val digest = request.key.digest()
        try {
            coroutineContext.ensureActive()
            diskCache.getBitmap(request.key)?.let { bitmap ->
                publish(digest, ThumbnailUiState.Ready(bitmap))
                return
            }
            val state = backoff[digest] ?: ThumbnailBackoffState()
            if (!state.isEligible(timeSource())) {
                if (state.permanentlyFailed) {
                    publish(digest, ThumbnailUiState.Failed)
                }
                return
            }
            // Skip heavy work if the row scrolled away and we already failed once.
            if ((interestCounts[digest] ?: 0) <= 0 && state.attempts > 0) {
                return
            }

            publish(digest, ThumbnailUiState.Loading)
            val success = generate(request)
            if (success) {
                backoff[digest] = ThumbnailBackoff.afterSuccess()
            } else {
                val next = ThumbnailBackoff.afterFailure(state, timeSource())
                backoff[digest] = next
                publish(
                    digest,
                    if (next.permanentlyFailed) ThumbnailUiState.Failed else ThumbnailUiState.None,
                )
            }
        } finally {
            queuedDigests.remove(digest)
        }
    }

    private suspend fun generate(request: ThumbnailRequest): Boolean {
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
                diskCache.put(request.key, result.jpegBytes, result.bitmap)
                publish(request.key.digest(), ThumbnailUiState.Ready(result.bitmap))
                Log.d(
                    TAG,
                    "thumb ok digest=${request.key.digest().take(8)} " +
                        "t=${result.usedTimestampMs}ms dur=${result.durationMs}ms",
                )
                true
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "thumb generate failed: ${t.javaClass.simpleName}")
            false
        } finally {
            runCatching { client.close() }
            password.fill('\u0000')
        }
    }

    private fun publish(digest: String, state: ThumbnailUiState) {
        val flow = uiStates.getOrPut(digest) { MutableStateFlow(state) }
        flow.value = state
    }

    companion object {
        private const val TAG = "FrameNestThumb"
    }
}
