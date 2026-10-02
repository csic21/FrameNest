package com.framenest.data.thumbnail

/**
 * How much of a remote video to copy locally before asking
 * [android.media.MediaMetadataRetriever] for one cover frame.
 *
 * The proxy file descriptor fills its kernel buffer on multi-gigabyte files
 * and then returns no frame. A bounded prefix stays on the SMB socket.
 */
object ThumbnailPrefixPlan {
    /**
     * Upper bound copied when the direct-player cover did not produce a frame.
     * Kept small so a miss does not download a long prefix first.
     */
    const val MAX_PREFIX_BYTES: Long = 16L * 1024L * 1024L

    /** At or above this duration, do not seek to the 30–120s body sample. */
    const val LONG_VIDEO_MS: Long = 60_000L

    private val EARLY_TIMESTAMPS_MS: LongArray = longArrayOf(2_000L, 5_000L, 8_000L, 12_000L)

    fun prefixBytes(fileSizeBytes: Long): Long =
        fileSizeBytes.coerceAtLeast(0L).coerceAtMost(MAX_PREFIX_BYTES)

    /**
     * Timestamps that can still land inside [MAX_PREFIX_BYTES] for a high-bitrate
     * remux. Short clips keep [ThumbnailCandidatePolicy].
     */
    fun timestampsMs(durationMs: Long): List<Long> {
        if (durationMs < 0L) return emptyList()
        if (durationMs in 1L until LONG_VIDEO_MS) {
            return ThumbnailCandidatePolicy.candidateTimestampsMs(durationMs)
        }
        val lastUsable = if (durationMs == 0L) Long.MAX_VALUE else durationMs - 1L
        val early = EARLY_TIMESTAMPS_MS.filter { it in 0L..lastUsable }
        return early.ifEmpty { listOf(0L) }
    }

    /**
     * Location of the Matroska/WebM Segment size vint in [header], when [header]
     * starts with the EBML magic. The caller overwrites that vint with 0xFF so a
     * truncated prefix is not treated as an 80 GB file.
     */
    fun mkvSegmentSizeField(header: ByteArray): IntRange? {
        if (header.size < 16 || !isEbml(header)) return null
        val idAt = indexOf(header, SEGMENT_ID, from = EBML_MAGIC.size)
        if (idAt < 0) return null
        val lengthAt = idAt + SEGMENT_ID.size
        if (lengthAt >= header.size) return null
        val width = vintWidth(header[lengthAt].toInt() and 0xFF)
        if (width !in 1..8 || lengthAt + width > header.size) return null
        return lengthAt until (lengthAt + width)
    }

    private fun isEbml(header: ByteArray): Boolean =
        header[0] == EBML_MAGIC[0] &&
            header[1] == EBML_MAGIC[1] &&
            header[2] == EBML_MAGIC[2] &&
            header[3] == EBML_MAGIC[3]

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        val last = data.size - pattern.size
        for (index in from..last) {
            var matches = true
            for (offset in pattern.indices) {
                if (data[index + offset] != pattern[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun vintWidth(first: Int): Int {
        var mask = 0x80
        var width = 1
        while (width < 8 && first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (first and mask == 0) return -1
        return width
    }

    private val EBML_MAGIC: ByteArray = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val SEGMENT_ID: ByteArray = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
}
