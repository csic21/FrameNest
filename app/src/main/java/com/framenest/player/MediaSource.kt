package com.framenest.player

import android.content.res.AssetFileDescriptor
import android.net.Uri

/**
 * Playback inputs for the player controller.
 *
 * SMB credentials are never part of a URI string; path B uses a seekable FD,
 * path A (optional) uses media options only.
 */
sealed class MediaSource {
    data class LocalFile(val path: String) : MediaSource()

    data class ContentUri(val uri: Uri) : MediaSource()

    /** Android resource under res/raw (or other openable resource id). */
    data class RawResource(val resId: Int) : MediaSource()

    /**
     * Decision 0002 path B: already-opened seekable descriptor backed by
     * [com.framenest.smb.SmbRandomAccess] (or any seekable source).
     *
     * Ownership of [assetFileDescriptor] transfers to the controller; it is
     * closed on re-prepare or [PlayerController.release].
     */
    data class SeekableDescriptor(
        val assetFileDescriptor: AssetFileDescriptor,
        /** Safe label for logs (no host credentials). */
        val debugLabel: String = "seekable",
    ) : MediaSource() {
        override fun toString(): String = "SeekableDescriptor(label=$debugLabel)"
    }

    /**
     * Optional path A: direct libVLC SMB open.
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
 * SMB auth for libVLC options (path A only). Do not put these values into logs,
 * URLs, or toString of higher-level models that may be logged.
 */
data class SmbCredentials(
    val username: String,
    val password: String,
    val domain: String? = null,
) {
    override fun toString(): String =
        "SmbCredentials(username=***, password=***, domain=${domain?.let { "***" }})"
}
