package com.framenest.data.thumbnail

import java.security.MessageDigest

/**
 * Stable identity for a list thumbnail cache entry.
 *
 * Must include server/share/path/size/modifiedTime so content changes invalidate
 * the cache without relying on path alone.
 */
data class ThumbnailKey(
    val serverId: String,
    val share: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
) {
    init {
        require(serverId.isNotBlank()) { "serverId required" }
        require(share.isNotBlank()) { "share required" }
        require(path.isNotBlank()) { "path required" }
        require(sizeBytes >= 0L) { "sizeBytes must be >= 0" }
        require(modifiedTimeMs >= 0L) { "modifiedTimeMs must be >= 0" }
    }

    /** Canonical string used as digest input (order and separators fixed). */
    fun canonicalString(): String =
        listOf(
            serverId.trim(),
            share.trim(),
            normalizePath(path),
            sizeBytes.toString(),
            modifiedTimeMs.toString(),
        ).joinToString(separator = "\u0001")

    /**
     * Stable hex digest for filenames and maps. SHA-256 hex, truncated for
     * path length; full digest remains unique for practical media libraries.
     */
    fun digest(): String = sha256Hex(canonicalString()).take(DIGEST_HEX_CHARS)

    companion object {
        /** 32 hex chars (128 bits) — enough uniqueness for local cache names. */
        const val DIGEST_HEX_CHARS: Int = 32

        fun normalizePath(path: String): String =
            path.trim()
                .replace('\\', '/')
                .trim('/')

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
