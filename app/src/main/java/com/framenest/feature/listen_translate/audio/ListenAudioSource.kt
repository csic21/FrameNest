package com.framenest.feature.listen_translate.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import androidx.annotation.RawRes
import com.framenest.player.SmbSeekableMedia
import com.framenest.player.audio.PcmAudioMath
import com.framenest.player.audio.PcmWindowDecoder
import com.framenest.smb.SmbClient
import java.io.Closeable
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
 * Keeps one read-only SMB proxy descriptor for the ASR session and replaces it when the
 * player's SMB client reconnects. This avoids reopening the NAS file every few seconds.
 * Credentials never logged.
 */
class SmbListenAudioSource(
    private val context: Context,
    private val clientProvider: () -> SmbClient?,
    private val share: String,
    private val path: String,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    private val mutex = Mutex()
    private var openedClient: SmbClient? = null
    private var opened: SmbSeekableMedia.SeekableOpenResult? = null

    override suspend fun pcmWindow(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
    ): ShortArray =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val client = clientProvider() ?: error("SMB 听译会话未连接")
                if (openedClient !== client) {
                    closeOpened()
                }
                try {
                    val current = opened ?: open(client).also {
                        opened = it
                        openedClient = client
                    }
                    decoder.decodeAfd(
                        current.assetFileDescriptor,
                        startMs,
                        endMs,
                        preferredAudioTrackOrdinal,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    closeOpened()
                    throw t
                }
            }
        }

    override fun close() {
        runBlocking {
            mutex.withLock { closeOpened() }
        }
    }

    private fun open(client: SmbClient): SmbSeekableMedia.SeekableOpenResult {
        val randomAccess = client.openRandomAccess(share, path)
        return try {
            SmbSeekableMedia.open(
                context = context,
                randomAccess = randomAccess,
                debugLabel = "listen-pcm",
            )
        } catch (t: Throwable) {
            runCatching { randomAccess.close() }
            throw t
        }
    }

    private fun closeOpened() {
        val afd: AssetFileDescriptor? = opened?.assetFileDescriptor
        opened = null
        openedClient = null
        runCatching { afd?.close() }
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
        context: Context,
        clientProvider: () -> SmbClient?,
        share: String,
        path: String,
    ): ListenAudioSource = CachingListenAudioSource(
        SmbListenAudioSource(
            context.applicationContext,
            clientProvider,
            share,
            path,
        ),
    )
}
