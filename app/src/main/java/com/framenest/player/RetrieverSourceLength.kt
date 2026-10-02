package com.framenest.player

import android.media.MediaMetadataRetriever
import java.io.FileDescriptor

/**
 * Byte length passed to [MediaMetadataRetriever.setDataSource].
 *
 * The proxy [android.content.res.AssetFileDescriptor] stays
 * [android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH] (decision 0007).
 * Giving that -1 to the retriever makes it scan the file. Callers that already
 * know the SMB size pass that size only to the retriever.
 */
object RetrieverSourceLength {
    /**
     * @return length in bytes, or null when the caller should open the
     * descriptor without a length. A non-negative descriptor length wins.
     * Otherwise a positive file size is used.
     */
    fun bytes(afdLength: Long, fileSizeBytes: Long): Long? = when {
        afdLength >= 0L -> afdLength
        fileSizeBytes > 0L -> fileSizeBytes
        else -> null
    }
}

/**
 * Bind [fd] using [RetrieverSourceLength.bytes]. A framework that rejects the
 * length falls back to the descriptor alone.
 *
 * @return the length actually passed, or null when the descriptor was opened
 * without a length.
 */
fun MediaMetadataRetriever.setDataSourceKnownLength(
    fd: FileDescriptor,
    offset: Long,
    afdLength: Long,
    fileSizeBytes: Long,
): Long? {
    val length = RetrieverSourceLength.bytes(afdLength, fileSizeBytes) ?: run {
        setDataSource(fd)
        return null
    }
    return try {
        setDataSource(fd, offset, length)
        length
    } catch (rejected: IllegalArgumentException) {
        setDataSource(fd)
        null
    }
}
