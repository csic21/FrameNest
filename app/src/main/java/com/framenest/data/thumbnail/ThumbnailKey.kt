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
    @Volatile
    private var cachedDigest: String? = null

    init {
        require(serverId.isNotBlank()) { "serverId required" }
        require(share.isNotBlank()) { "share required" }
        require(path.isNotBlank()) { "path required" }
        require(sizeBytes >= 0L) { "sizeBytes must be >= 0" }
        require(modifiedTimeMs >= 0L) { "modifiedTimeMs must be >= 0" }
    }

    /** Canonical string used as digest input (order and separators fixed). */
    fun canonicalString(): String = buildString {
        append(serverId.trim())
        append(CANONICAL_SEPARATOR)
        append(share.trim())
        append(CANONICAL_SEPARATOR)
        append(normalizePath(path))
        append(CANONICAL_SEPARATOR)
        append(sizeBytes)
        append(CANONICAL_SEPARATOR)
        append(modifiedTimeMs)
    }

    /**
     * Stable hex digest for filenames and maps. SHA-256 hex, truncated for
     * path length; full digest remains unique for practical media libraries.
     */
    fun digest(): String {
        cachedDigest?.let { return it }
        return encodeHex(
            bytes = sha256Bytes(canonicalString()),
            maxChars = DIGEST_HEX_CHARS,
        ).also { cachedDigest = it }
    }

    companion object {
        /** 32 hex chars (128 bits) — enough uniqueness for local cache names. */
        const val DIGEST_HEX_CHARS: Int = 32

        fun normalizePath(path: String): String =
            path.trim()
                .replace('\\', '/')
                .trim('/')

        private const val CANONICAL_SEPARATOR: Char = '\u0001'
        private const val HEX_DIGITS: String = "0123456789abcdef"

        fun sha256Hex(input: String): String =
            encodeHex(sha256Bytes(input), maxChars = Int.MAX_VALUE)

        private fun sha256Bytes(input: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))

        private fun encodeHex(bytes: ByteArray, maxChars: Int): String {
            val charCount = minOf(bytes.size * 2, maxChars.coerceAtLeast(0))
            val chars = CharArray(charCount)
            for (index in 0 until charCount) {
                val value = bytes[index / 2].toInt() and 0xff
                val nibble = if (index % 2 == 0) value ushr 4 else value and 0x0f
                chars[index] = HEX_DIGITS[nibble]
            }
            return String(chars)
        }
    }
}
