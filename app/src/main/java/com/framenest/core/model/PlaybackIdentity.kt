package com.framenest.core.model

/**
 * Stable identity for a remote (or placeholder) media item.
 *
 * History uniqueness key is exactly ([serverId], [share], [path]).
 * Temporary contract until FN-04 lands SavedServer / RemoteEntry types —
 * field names and semantics are chosen to merge cleanly.
 *
 * @param serverId Stable server id (FN-04 Room row id / UUID string).
 * @param share SMB share name (not a full UNC).
 * @param path Share-relative path using `/` separators (no leading slash required).
 */
data class PlaybackIdentity(
    val serverId: String,
    val share: String,
    val path: String,
) {
    init {
        require(serverId.isNotBlank()) { "serverId required" }
        require(share.isNotBlank()) { "share required" }
        require(path.isNotBlank()) { "path required" }
    }

    /** Normalized path form for storage/comparison. */
    fun normalizedPath(): String =
        path.trim()
            .replace('\\', '/')
            .trim('/')
}

/**
 * Explicit playback request used by the product player and tests.
 *
 * Credentials are never part of identity or toString; they are only carried
 * when the caller already resolved them (tests / future FN-04 credential store).
 */
data class PlaybackRequest(
    val identity: PlaybackIdentity,
    val displayName: String,
    /**
     * How to open media. Product code prefers [PlaybackDataSource.SeekableSmb]
     * (decision 0002 path B). Local raw/content sources are for demos and tests.
     */
    val dataSource: PlaybackDataSource,
    val startPositionMs: Long = 0L,
    /**
     * Size and modification time already known from a directory listing.
     * Scrub preview uses them as the cache key so the first drag does not
     * open a separate SMB connection just to stat the file.
     */
    val contentSizeBytes: Long? = null,
    val contentModifiedTimeMs: Long? = null,
) {
    override fun toString(): String =
        "PlaybackRequest(identity=$identity, displayName=$displayName, " +
            "dataSource=${dataSource::class.simpleName}, startPositionMs=$startPositionMs)"
}

/**
 * Source resolution for FN-05. Maps to [com.framenest.player.MediaSource] inside the player layer.
 */
sealed class PlaybackDataSource {
    /** Local debug / shell demo (no network). */
    data class LocalRawResource(val resId: Int) : PlaybackDataSource()

    data class LocalFile(val path: String) : PlaybackDataSource()

    /**
     * Decision 0002 path B: open via SMBJ [com.framenest.smb.SmbClient.openRandomAccess]
     * and feed libVLC through a seekable proxy file descriptor.
     *
     * [host]/[port]/[username]/[password]/[domain] are session params for [com.framenest.smb.SmbCredentials].
     * Password must never be logged or put into URLs.
     */
    data class SeekableSmb(
        val host: String,
        val port: Int = 445,
        val username: String,
        val password: CharArray,
        val domain: String = "",
        val share: String,
        val path: String,
    ) : PlaybackDataSource() {
        override fun toString(): String =
            "SeekableSmb(host=$host, port=$port, user=***, share=$share, path=$path)"
    }

    /**
     * Optional path A: libVLC direct `smb://` with credentials as media options only.
     * Prefer [SeekableSmb] unless an integration path explicitly opts into A.
     */
    data class DirectSmbUrl(
        val host: String,
        val share: String,
        val path: String,
        val port: Int? = null,
        val username: String = "",
        val password: String = "",
        val domain: String? = null,
    ) : PlaybackDataSource() {
        override fun toString(): String =
            "DirectSmbUrl(host=$host, share=$share, path=$path, user=***)"
    }
}
