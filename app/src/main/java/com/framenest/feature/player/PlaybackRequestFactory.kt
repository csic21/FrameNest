package com.framenest.feature.player

import com.framenest.R
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackIdentity
import com.framenest.core.model.PlaybackRequest
import com.framenest.navigation.FakeCatalog

/**
 * Builds [PlaybackRequest] for navigation entry points before FN-04 server models exist.
 *
 * Fake catalog entries play the local H.264 sample so the product player UI is
 * exercisable without NAS credentials. History is still keyed by
 * (serverId, share, path) placeholders that FN-04 can replace with real values.
 */
object PlaybackRequestFactory {
    const val PLACEHOLDER_SHARE: String = "media"

    /**
     * Encodes share+path into the single nav `entryId` segment so Recent can
     * reopen the same history identity. Format: `h|{share}|{path with / → |}`.
     */
    fun encodeHistoryEntryId(share: String, path: String): String =
        "h|${share.trim()}|${path.trim().trim('/').replace('/', '|')}"

    fun fromNavArgs(
        serverId: String,
        entryId: String,
        override: PlaybackRequest? = null,
    ): PlaybackRequest {
        if (override != null) return override

        val historyDecoded = decodeHistoryEntryId(entryId)
        if (historyDecoded != null) {
            val (share, path) = historyDecoded
            val display = path.substringAfterLast('/').ifBlank { path }
            return PlaybackRequest(
                identity = PlaybackIdentity(
                    serverId = serverId.ifBlank { "local" },
                    share = share,
                    path = path,
                ),
                displayName = display,
                dataSource = PlaybackDataSource.LocalRawResource(R.raw.sample_h264),
                startPositionMs = 0L,
            )
        }

        val title = FakeCatalog.entryTitle(entryId)
        val path = placeholderPath(entryId, title)
        return PlaybackRequest(
            identity = PlaybackIdentity(
                serverId = serverId.ifBlank { "local" },
                share = PLACEHOLDER_SHARE,
                path = path,
            ),
            displayName = title,
            // Shell / tests: local raw sample until FN-04 supplies SeekableSmb.
            dataSource = PlaybackDataSource.LocalRawResource(R.raw.sample_h264),
            startPositionMs = 0L,
        )
    }

    private fun decodeHistoryEntryId(entryId: String): Pair<String, String>? {
        if (!entryId.startsWith("h|")) return null
        val rest = entryId.removePrefix("h|")
        val sep = rest.indexOf('|')
        if (sep <= 0) return null
        val share = rest.substring(0, sep)
        val path = rest.substring(sep + 1).replace('|', '/')
        if (share.isBlank() || path.isBlank()) return null
        return share to path
    }

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

    private fun placeholderPath(entryId: String, title: String): String =
        "samples/$entryId/${title.replace(' ', '_')}"
}
