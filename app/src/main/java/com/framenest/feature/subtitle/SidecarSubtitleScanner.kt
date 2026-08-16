package com.framenest.feature.subtitle

import com.framenest.core.model.MediaExtensions
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbCredentials
import com.framenest.smb.SmbPathUtils
import com.framenest.smb.SmbjClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-recursive scan of the video's parent directory for sidecar subtitles.
 *
 * Opens a short-lived SMB session; credentials are never logged or put into URLs.
 */
class SidecarSubtitleScanner(
    private val clientFactory: () -> SmbClient = { SmbjClient() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    data class ScanRequest(
        val host: String,
        val port: Int = 445,
        val username: String,
        val password: CharArray,
        val domain: String = "",
        val share: String,
        /** Share-relative path of the video file. */
        val videoPath: String,
    )

    suspend fun scan(
        request: ScanRequest,
        preferredLanguages: List<String>,
    ): Result<List<ExternalSubtitleOption>> = withContext(ioDispatcher) {
        if (request.videoPath.substringAfterLast('/').isBlank()) {
            return@withContext Result.success(emptyList())
        }
        val parent = SmbPathUtils.parentOf(request.videoPath)
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
            val entries = client.listDirectory(request.share, parent)
            val names = entries
                .asSequence()
                .filter { !it.isDirectory && !SmbPathUtils.isDotEntry(it.name) }
                .map { it.name }
                .toList()
            Result.success(optionsFromFileNames(request.videoPath, names, preferredLanguages))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Builds sidecar options from an already enumerated parent directory.
     * Player bootstrap uses this path so subtitle matching and sibling navigation
     * share one SMB directory snapshot.
     */
    fun optionsFromFileNames(
        videoPath: String,
        directoryFileNames: List<String>,
        preferredLanguages: List<String>,
    ): List<ExternalSubtitleOption> {
        val videoName = videoPath.substringAfterLast('/').ifBlank { return emptyList() }
        val parent = SmbPathUtils.parentOf(videoPath)
        val subtitleNames = directoryFileNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !SmbPathUtils.isDotEntry(it) }
            .filter { MediaExtensions.isSubtitle(it) }
            .toList()
        return SubtitleMatcher.rankMatches(videoName, subtitleNames, preferredLanguages)
            .map { rankedItem ->
                ExternalSubtitleOption(
                    fileName = rankedItem.fileName,
                    remotePath = SmbPathUtils.join(parent, rankedItem.fileName),
                    extension = rankedItem.extension,
                    languageTags = rankedItem.languageTags,
                )
            }
    }
}
