package com.framenest.player

import android.net.Uri

/**
 * Playback inputs for the FN-01 spike and the minimal UI boundary.
 *
 * SMB credentials are never part of the URI string; they travel only via
 * [SmbCredentials] applied as libVLC media options.
 */
sealed class MediaSource {
    data class LocalFile(val path: String) : MediaSource()

    data class ContentUri(val uri: Uri) : MediaSource()

    /** Android resource under res/raw (or other openable resource id). */
    data class RawResource(val resId: Int) : MediaSource()

    /**
     * Direct libVLC SMB open.
     *
     * [uri] must be built with [SmbMediaUri.build] (no userinfo).
     * [credentials] optional; applied only as media options, never logged.
     */
    data class Smb(
        val uri: Uri,
        val credentials: SmbCredentials? = null,
    ) : MediaSource()
}

/**
 * SMB auth for libVLC options. Do not put these values into logs, URLs, or
 * toString of higher-level models that may be logged.
 */
data class SmbCredentials(
    val username: String,
    val password: String,
    val domain: String? = null,
) {
    override fun toString(): String =
        "SmbCredentials(username=***, password=***, domain=${domain?.let { "***" }})"
}
