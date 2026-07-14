package com.framenest.feature.listen_translate.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import androidx.annotation.RawRes
import com.framenest.player.SmbSeekableMedia
import com.framenest.player.audio.PcmWindowDecoder
import com.framenest.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Provides 16 kHz mono PCM windows for listen-translate ASR (FN-14).
 */
interface ListenAudioSource {
    suspend fun pcmWindow(startMs: Long, endMs: Long): ShortArray
}

class FileListenAudioSource(
    private val path: String,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    override suspend fun pcmWindow(startMs: Long, endMs: Long): ShortArray =
        decoder.decodeFile(path, startMs, endMs)
}

class RawListenAudioSource(
    private val context: Context,
    @RawRes private val resId: Int,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    override suspend fun pcmWindow(startMs: Long, endMs: Long): ShortArray =
        decoder.decodeRaw(context, resId, startMs, endMs)
}

/**
 * Opens a fresh SMB random-access + proxy FD per window (safe with concurrent VLC path B).
 * Credentials never logged.
 */
class SmbListenAudioSource(
    private val context: Context,
    private val client: SmbClient,
    private val share: String,
    private val path: String,
    private val decoder: PcmWindowDecoder = PcmWindowDecoder(),
) : ListenAudioSource {
    private val mutex = Mutex()

    override suspend fun pcmWindow(startMs: Long, endMs: Long): ShortArray =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val ra = client.openRandomAccess(share, path)
                var afd: AssetFileDescriptor? = null
                try {
                    val opened = SmbSeekableMedia.open(
                        context = context,
                        randomAccess = ra,
                        debugLabel = "listen-pcm",
                    )
                    afd = opened.assetFileDescriptor
                    decoder.decodeAfd(afd, startMs, endMs)
                } finally {
                    runCatching { afd?.close() }
                    // SmbSeekableMedia closes RA on AFD release; if open failed, close RA.
                    if (afd == null) {
                        runCatching { ra.close() }
                    }
                }
            }
        }
}

/** Factory helpers used by the player. */
object ListenAudioSources {
    fun forLocalFile(path: String): ListenAudioSource = FileListenAudioSource(path)

    fun forRaw(context: Context, @RawRes resId: Int): ListenAudioSource =
        RawListenAudioSource(context.applicationContext, resId)

    fun forSmb(
        context: Context,
        client: SmbClient,
        share: String,
        path: String,
    ): ListenAudioSource = SmbListenAudioSource(context.applicationContext, client, share, path)
}
