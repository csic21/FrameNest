package com.framenest.smb

import java.io.Closeable
import java.io.IOException

/**
 * Seekable/random-access read surface for player and thumbnail extractors.
 *
 * Implementations must not put credentials into any URL or log line.
 */
interface SmbRandomAccess : Closeable {
    val size: Long

    /**
     * Read up to [length] bytes starting at absolute [position].
     * @return number of bytes read, or -1 at EOF
     */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Convenience: read fully into a new array (bounded).
     */
    @Throws(IOException::class)
    fun readFullyAt(position: Long, length: Int): ByteArray {
        require(length >= 0) { "length < 0" }
        require(position >= 0) { "position < 0" }
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = readAt(position + done, out, done, length - done)
            if (n < 0) break
            done += n
        }
        return if (done == length) out else out.copyOf(done)
    }
}
