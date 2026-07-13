package com.framenest.player

import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds `smb://` URIs without embedding credentials.
 *
 * Coordinate with FN-02 samples:
 * - host: NAS hostname or LAN IP (placeholder `nas.local` / `192.168.1.10`)
 * - share: share name (placeholder `media`)
 * - path: relative path inside share (placeholder `samples/movie.mkv`)
 *
 * Credentials must be passed separately via [SmbCredentials] → media options.
 */
object SmbMediaUri {
    private val utf8 = StandardCharsets.UTF_8.name()

    /**
     * @param host host or IP only (no userinfo, no scheme)
     * @param share share name without leading slash
     * @param path path relative to share; may contain `/` segments
     * @param port optional non-default SMB port (default 445 omitted)
     */
    fun build(
        host: String,
        share: String,
        path: String = "",
        port: Int? = null,
    ): Uri = Uri.parse(buildString(host, share, path, port))

    /**
     * Pure string form for unit tests and logging-safe construction.
     * Never includes userinfo.
     */
    fun buildString(
        host: String,
        share: String,
        path: String = "",
        port: Int? = null,
    ): String {
        require(host.isNotBlank()) { "host required" }
        require(!host.contains('@')) { "host must not include credentials" }
        require(share.isNotBlank()) { "share required" }
        require(!share.contains("://")) { "share must be a name, not a URL" }

        val encodedShare = encodeSegment(share.trim().trim('/'))
        val encodedPath = path
            .trim()
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodeSegment(it) }

        val authority = if (port != null && port != 445) {
            "$host:$port"
        } else {
            host
        }

        val pathPart = if (encodedPath.isEmpty()) {
            "/$encodedShare"
        } else {
            "/$encodedShare/$encodedPath"
        }

        return "smb://$authority$pathPart"
    }

    /** Returns true if [uriString] looks like an smb URI that embeds userinfo (unsafe). */
    fun embedsCredentials(uriString: String): Boolean {
        if (!uriString.startsWith("smb://", ignoreCase = true)) return false
        val afterScheme = uriString.substring(6)
        val authority = afterScheme.substringBefore('/')
        return authority.contains('@')
    }

    fun embedsCredentials(uri: Uri): Boolean =
        embedsCredentials(uri.toString())

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, utf8).replace("+", "%20")
}
