package com.framenest.feature.listen_translate.audio

import android.content.Context
import android.media.MediaDataSource
import androidx.annotation.RawRes
import com.framenest.player.audio.PcmAudioMath
import com.framenest.player.audio.PcmWindowDecoder
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbRandomAccess
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Provides 16 kHz mono PCM windows for listen-translate ASR (FN-14).
 */
interface ListenAudioSource : Closeable {
    suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray

    override fun close() = Unit
}

class FileListenAudioSource(
    private val path: String,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    override suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray = decoder.decodeFile(path, startMs, endMs, preferredAudioTrackOrdinal)
}

class RawListenAudioSource(
    private val context: Context,
    @RawRes private val resId: Int,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    override suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray = decoder.decodeRaw(
        context,
        resId,
        startMs,
        endMs,
        preferredAudioTrackOrdinal,
    )
}

/**
 * Keeps one read-only SMB random-access handle for the ASR session and replaces it when the
 * dedicated client reconnects. This avoids reopening the NAS file every few seconds.
 * Credentials never logged.
 */
class SmbListenAudioSource(
    private val clientProvider: () -> SmbClient?,
    private val reconnectClient: suspend () -> SmbClient?,
    private val share: String,
    private val path: String,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    private val mutex = Mutex()
    private var openedClient: SmbClient? = null
    private var opened: SmbRandomAccess? = null

    override suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val client = clientProvider() ?: reconnectClient()
                    ?: error("SMB 听译会话未连接")
                try {
                    decodeWindow(client, startMs, endMs, preferredAudioTrackOrdinal)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    closeOpened()
                    if (!isRetryableSmbAudioFailure(t)) throw t
                    val replacement = reconnectClient() ?: throw t
                    try {
                        decodeWindow(
                            replacement,
                            startMs,
                            endMs,
                            preferredAudioTrackOrdinal,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (retryFailure: Throwable) {
                        closeOpened()
                        throw retryFailure
                    }
                }
            }
        }

    private suspend fun decodeWindow(
        client: SmbClient,
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray {
        if (openedClient !== client) closeOpened()
        val randomAccess = opened ?: open(client).also {
            opened = it
            openedClient = client
        }
        // MediaExtractor closes its MediaDataSource when released. Rotate only the
        // lightweight wrapper and retain the underlying SMB file handle for read-ahead.
        val mediaSource = SmbRandomAccessMediaDataSource(
            randomAccess = randomAccess,
            closeRandomAccessOnClose = false,
        )
        return try {
            decoder.decodeMediaDataSource(
                source = mediaSource,
                startMs = startMs,
                endMs = endMs,
                preferredAudioTrackOrdinal = preferredAudioTrackOrdinal,
            )
        } finally {
            runCatching { mediaSource.close() }
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock { closeOpened() }
        }
    }

    private fun open(client: SmbClient): SmbRandomAccess =
        client.openRandomAccess(share, path)

    private fun closeOpened() {
        val source = opened
        opened = null
        openedClient = null
        runCatching { source?.close() }
    }
}

/** Bridges SMBJ random reads directly into MediaExtractor without a proxy FD. */
internal class SmbRandomAccessMediaDataSource(
    private val randomAccess: SmbRandomAccess,
    private val closeRandomAccessOnClose: Boolean = true,
) : MediaDataSource() {
    private val closed = AtomicBoolean(false)
    private val length = randomAccess.size.coerceAtLeast(0L)

    @Synchronized
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) throw IOException("SMB audio source is closed")
        if (position < 0L || size < 0) throw IOException("Invalid media read")
        if (size == 0) return 0
        if (position >= length) return -1
        return randomAccess.readAt(position, buffer, offset, size).let { read ->
            if (read == 0) -1 else read
        }
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true) && closeRandomAccessOnClose) {
            randomAccess.close()
        }
    }
}

/**
 * Small in-memory PCM read-ahead. Sequential ASR windows reuse decoded samples instead of
 * creating a new MediaExtractor/MediaCodec for every three-second bucket.
 */
internal class CachingListenAudioSource(
    private val delegate: ListenAudioSource,
    private val readAheadMs: Long = 6_000L,
) : ListenAudioSource {
    private val mutex = Mutex()
    private var cacheStartMs = 0L
    private var cacheEndMs = 0L
    private var cacheTrackOrdinal: Int? = null
    private var cache = ShortArray(0)

    override suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray = mutex.withLock {
        if (!contains(startMs, endMs, preferredAudioTrackOrdinal)) {
            val decodeEndMs = endMs + readAheadMs
            cache = delegate.pcmWindow(startMs, decodeEndMs, preferredAudioTrackOrdinal)
            cacheStartMs = startMs
            cacheEndMs = startMs + samplesToMs(cache.size)
            cacheTrackOrdinal = preferredAudioTrackOrdinal
        }
        slice(startMs, endMs)
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                cache = ShortArray(0)
                delegate.close()
            }
        }
    }

    private fun contains(startMs: Long, endMs: Long, trackOrdinal: Int?): Boolean =
        cache.isNotEmpty() &&
            cacheTrackOrdinal == trackOrdinal &&
            startMs >= cacheStartMs &&
            endMs <= cacheEndMs

    private fun slice(startMs: Long, endMs: Long): ShortArray {
        if (cache.isEmpty() || endMs <= startMs || startMs >= cacheEndMs) return ShortArray(0)
        val startSample = msToSamples((startMs - cacheStartMs).coerceAtLeast(0L))
            .coerceIn(0, cache.size)
        val endSample = msToSamples((endMs - cacheStartMs).coerceAtLeast(0L))
            .coerceIn(startSample, cache.size)
        return cache.copyOfRange(startSample, endSample)
    }

    private fun msToSamples(ms: Long): Int =
        (ms * PcmAudioMath.TARGET_SAMPLE_RATE_HZ / 1_000L).toInt()

    private fun samplesToMs(samples: Int): Long =
        samples.toLong() * 1_000L / PcmAudioMath.TARGET_SAMPLE_RATE_HZ
}

/** Factory helpers used by the player. */
object ListenAudioSources {
    fun forLocalFile(path: String): ListenAudioSource =
        CachingListenAudioSource(FileListenAudioSource(path))

    fun forRaw(context: Context, @RawRes resId: Int): ListenAudioSource =
        CachingListenAudioSource(RawListenAudioSource(context.applicationContext, resId))

    fun forSmb(
        clientProvider: () -> SmbClient?,
        reconnectClient: suspend () -> SmbClient?,
        share: String,
        path: String,
    ): ListenAudioSource = CachingListenAudioSource(
        SmbListenAudioSource(
            clientProvider,
            reconnectClient,
            share,
            path,
        ),
    )
}

internal fun isRetryableSmbAudioFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { cause ->
        cause is IOException ||
            cause.message?.let { message ->
                message.contains("broken pipe", ignoreCase = true) ||
                    message.contains("connection reset", ignoreCase = true) ||
                    message.contains("socket closed", ignoreCase = true) ||
                    message.contains("disconnected", ignoreCase = true)
            } == true
    }
