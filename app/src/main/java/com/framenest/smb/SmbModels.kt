package com.framenest.smb

/**
 * A directory or file entry under a share.
 */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

data class SmbFileMetadata(
    val path: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val isDirectory: Boolean,
)

/**
 * Result of a micro-benchmark against a remote file.
 * Values are wall-clock; never include credentials.
 */
data class SmbBenchmarkResult(
    val host: String,
    val share: String,
    val path: String,
    val fileSizeBytes: Long,
    val firstByteMs: Long,
    val sequentialBytes: Long,
    val sequentialMs: Long,
    val sequentialMbps: Double,
    val randomSeeks: Int,
    val randomSeekTotalMs: Long,
    val randomSeekAvgMs: Double,
    val notes: String = "",
) {
    fun summaryLines(): List<String> = listOf(
        "host=$host share=$share path=$path size=$fileSizeBytes",
        "firstByteMs=$firstByteMs",
        "sequential: bytes=$sequentialBytes ms=$sequentialMs mbps=${"%.2f".format(sequentialMbps)}",
        "randomSeek: n=$randomSeeks totalMs=$randomSeekTotalMs avgMs=${"%.2f".format(randomSeekAvgMs)}",
        notes,
    ).filter { it.isNotBlank() }
}

/**
 * Candidate data paths for player + thumbnail (decision guidance for FN-01/05/07).
 */
enum class SmbPlaybackDataPath {
    /** libVLC (or other) opens smb:// with credentials via options, never embedded in URL. */
    DIRECT_SMB_URL,

    /** App owns SMB session; exposes seekable/random-access reads to the player. */
    SEEKABLE_SMB_DATASOURCE,

    /** Last resort: localhost HTTP Range proxy fed by SMB client. */
    LOCALHOST_HTTP_RANGE_PROXY,
}
