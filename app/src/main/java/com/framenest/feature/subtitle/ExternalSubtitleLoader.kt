package com.framenest.feature.subtitle

import android.content.Context
import android.util.Log
import com.framenest.player.CredentialRedactor
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbTransportOwner
import com.framenest.smb.SmbjClient
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads an SMB sidecar subtitle into app cache as UTF-8 for libVLC [addSlave].
 *
 * Approach (see FN-06 handoff): never put passwords in URLs. Subtitle bytes are
 * read via SMBJ random access and written to a local temp file; libVLC is given
 * a `file://` path only.
 *
 * Encoding policy: prefer strict UTF-8; on failure try common fallbacks and
 * re-encode to UTF-8. If conversion fails, surface a clear message and do not
 * crash playback.
 */
class ExternalSubtitleLoader(
    context: Context,
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    sessionCacheKey: String? = null,
) {
    private val appContext = context.applicationContext
    private val cache = SubtitleSessionCache(File(appContext.cacheDir, "subtitles"), sessionCacheKey)

    private val transportLock = Any()
    private var transports = SmbTransportOwner()
    private var closed = false

    /** Retire blocked loads immediately; a newer selection gets its own transport owner. */
    fun cancelPendingLoads() {
        synchronized(transportLock) {
            transports.retire()
            if (!closed) transports = SmbTransportOwner()
        }
    }

    /** Called at navigation exit before waiting for the canceled session jobs. */
    fun close() {
        synchronized(transportLock) {
            closed = true
            transports.retire()
        }
    }

    data class LoadRequest(
        val host: String,
        val port: Int = 445,
        val username: String,
        val password: CharArray,
        val domain: String = "",
        val share: String,
        val remotePath: String,
        val fileName: String,
    )

    data class LoadResult(
        val localFile: File,
        val encodingNote: String?,
    )

    /**
     * @return success with local UTF-8 file, or failure with a safe message.
     */
    suspend fun loadToLocalFile(request: LoadRequest): Result<LoadResult> {
        val owner = synchronized(transportLock) { transports }
        return withContext(ioDispatcher) {
            val client = clientFactory()
            val credentials = SmbCredentials(
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
                domain = request.domain,
            )
            try {
                owner.register(client)
                client.connect(credentials)
                owner.ensureActive()
                currentCoroutineContext().ensureActive()
                val randomAccess = client.openRandomAccess(request.share, request.remotePath)
                try {
                    val size = randomAccess.size
                    if (size < 0L || size > MAX_SUBTITLE_BYTES) {
                        return@withContext Result.failure(
                            IllegalStateException("Subtitle file too large or unreadable"),
                        )
                    }
                    val bytes = randomAccess.readFullyAt(0L, size.toInt())
                    val decoded = SubtitleEncoding.decode(bytes)
                    owner.ensureActive()
                    currentCoroutineContext().ensureActive()
                    val target = cache.write(request.share, request.remotePath, request.fileName, decoded.text)
                    Result.success(
                        LoadResult(
                            localFile = target,
                            encodingNote = decoded.note,
                        ),
                    )
                } finally {
                    runCatching { randomAccess.close() }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                owner.ensureActive()
                currentCoroutineContext().ensureActive()
                val msg = CredentialRedactor.redact(t.message ?: "Subtitle download failed")
                Log.w(TAG, "load failed: $msg")
                Result.failure(IllegalStateException(msg, t))
            } finally {
                owner.release(client)
            }
        }
    }

    fun clearCache() {
        close()
        cache.clear()
    }

    companion object {
        private const val TAG = "FrameNestSubtitle"
        /** Cap to keep temp cache and memory bounded (16 MiB). */
        const val MAX_SUBTITLE_BYTES: Long = 16L * 1024L * 1024L
    }
}

/** A retiring video can neither delete nor repopulate the next video's temporary subtitles. */
internal class SubtitleSessionCache(root: File, private val sessionKey: String?) {
    init {
        require(sessionKey == null || sessionKey.matches(Regex("[A-Za-z0-9-]{1,80}")))
    }

    private val directory = if (sessionKey == null) root else File(root, sessionKey)
    private var closed = false

    @Synchronized
    fun write(share: String, remotePath: String, fileName: String, text: String): File {
        check(!closed) { "Subtitle session closed" }
        val key = "$share|$remotePath"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "$digest-$safeName")
        directory.mkdirs()
        target.writeText(text, StandardCharsets.UTF_8)
        return target
    }

    @Synchronized
    fun clear() {
        if (sessionKey != null) {
            if (closed) return
            closed = true
            directory.deleteRecursively()
        } else {
            directory.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
