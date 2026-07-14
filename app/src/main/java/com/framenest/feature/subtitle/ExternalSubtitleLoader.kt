package com.framenest.feature.subtitle

import android.content.Context
import android.util.Log
import com.framenest.player.CredentialRedactor
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbjClient
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
) {
    private val appContext = context.applicationContext
    private val cacheDir: File =
        File(appContext.cacheDir, "subtitles").also { it.mkdirs() }

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
    suspend fun loadToLocalFile(request: LoadRequest): Result<LoadResult> =
        withContext(ioDispatcher) {
            val client = clientFactory()
            val credentials = SmbCredentials(
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
                domain = request.domain,
            )
            try {
                client.connect(credentials)
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
                    val target = cacheFileFor(request.share, request.remotePath, request.fileName)
                    target.parentFile?.mkdirs()
                    // UTF-8 without BOM — libVLC and SRT/ASS handle this well.
                    target.writeText(decoded.text, StandardCharsets.UTF_8)
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
                val msg = CredentialRedactor.redact(t.message ?: "Subtitle download failed")
                Log.w(TAG, "load failed: $msg")
                Result.failure(IllegalStateException(msg, t))
            } finally {
                runCatching { client.close() }
            }
        }

    fun clearCache() {
        runCatching {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun cacheFileFor(share: String, remotePath: String, fileName: String): File {
        val key = "$share|$remotePath"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(cacheDir, "$digest-$safeName")
    }

    companion object {
        private const val TAG = "FrameNestSubtitle"
        /** Cap to keep temp cache and memory bounded (16 MiB). */
        const val MAX_SUBTITLE_BYTES: Long = 16L * 1024L * 1024L
    }
}
