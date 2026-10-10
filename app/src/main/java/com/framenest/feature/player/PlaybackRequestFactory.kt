package com.framenest.feature.player

import com.framenest.R
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackIdentity
import com.framenest.core.model.PlaybackRequest
import com.framenest.core.model.SavedServer
import com.framenest.smb.SmbPathUtils

/**
 * Builds [PlaybackRequest] for navigation and tests.
 *
 * Product path (FN-04 + FN-05): [fromSavedServer] with encrypted password from credential store.
 * Local raw sample remains available for demos / instrumented tests without NAS.
 */
object PlaybackRequestFactory {

    /**
     * Decision 0002 path B: seekable SMB via SMBJ session credentials.
     * Caller must zero [password] when finished if they own the buffer.
     */
    fun fromSavedServer(
        server: SavedServer,
        share: String,
        path: String,
        password: CharArray,
        startPositionMs: Long = 0L,
        displayName: String? = null,
        contentSizeBytes: Long? = null,
        contentModifiedTimeMs: Long? = null,
    ): PlaybackRequest {
        val normalized = SmbPathUtils.normalizeRelative(path)
        val name = displayName?.ifBlank { null }
            ?: normalized.substringAfterLast('/').ifBlank { normalized }
        return PlaybackRequest(
            identity = PlaybackIdentity(
                serverId = server.id,
                share = share,
                path = normalized,
            ),
            displayName = name,
            dataSource = PlaybackDataSource.SeekableSmb(
                host = server.host,
                port = server.port,
                username = server.username,
                // Defensive copy so callers can zero their buffer after building.
                password = password.copyOf(),
                domain = server.domain.orEmpty(),
                            requireEncryption = server.requireEncryption,
                share = share,
                path = normalized,
            ),
            startPositionMs = startPositionMs,
            contentSizeBytes = contentSizeBytes?.takeIf { it >= 0L },
            contentModifiedTimeMs = contentModifiedTimeMs?.takeIf { it >= 0L },
        )
    }

    /** Local debug / instrumented sample. */
    fun localSample(
        serverId: String = "local",
        share: String = "media",
        path: String = "samples/sample_h264.mp4",
        displayName: String = "sample_h264.mp4",
        startPositionMs: Long = 0L,
    ): PlaybackRequest =
        PlaybackRequest(
            identity = PlaybackIdentity(serverId = serverId, share = share, path = path),
            displayName = displayName,
            dataSource = PlaybackDataSource.LocalRawResource(R.raw.sample_h264),
            startPositionMs = startPositionMs,
        )

    /**
     * Explicit SMB seekable request for instrumented / manual tests.
     * Password must not be logged by callers.
     */
    fun seekableSmb(
        serverId: String,
        host: String,
        share: String,
        path: String,
        username: String,
        password: CharArray,
        domain: String = "",
        port: Int = 445,
        displayName: String = path.substringAfterLast('/'),
        startPositionMs: Long = 0L,
    ): PlaybackRequest =
        PlaybackRequest(
            identity = PlaybackIdentity(serverId = serverId, share = share, path = path),
            displayName = displayName,
            dataSource = PlaybackDataSource.SeekableSmb(
                host = host,
                port = port,
                username = username,
                password = password,
                domain = domain,
                share = share,
                path = path,
            ),
            startPositionMs = startPositionMs,
        )
}
