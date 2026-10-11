package com.framenest.feature.listen_translate.audio

import com.framenest.smb.SmbRandomAccess
import java.io.IOException

/**
 * Read-through block cache for the listen-translate SMB audio reader (FN-69).
 *
 * MediaExtractor reads container indexes and samples with many small `readAt` calls. Over SMB each
 * call is a blocking round trip: one 10.5 s decode of a two-hour MP4 issued about 2,300 reads, and
 * every window re-parsed the index. Aligned blocks turn those reads into a few large ones. The cache
 * belongs to the listen session, so the index and recently read media stay resident across decodes.
 */
internal class BlockCachingRandomAccess(
    private val delegate: SmbRandomAccess,
    private val blockSize: Int = BLOCK_SIZE_BYTES,
    private val maxBlocks: Int = MAX_BLOCKS,
) : SmbRandomAccess {
    private class Block(val data: ByteArray, val length: Int)

    private val blocks = LinkedHashMap<Long, Block>(maxBlocks * 2, 0.75f, true)
    private var closed = false
    private var remoteReads = 0
    private var remoteBytes = 0L

    override val size: Long = delegate.size

    init {
        require(blockSize > 0 && maxBlocks > 0) { "blockSize and maxBlocks must be positive" }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("SMB audio read cache is closed")
        if (position < 0L) throw IOException("Negative position")
        if (offset < 0 || length < 0 || offset + length > buffer.size) {
            throw IOException("Bad buffer range")
        }
        if (length == 0) return 0
        if (position >= size) return -1
        val end = minOf(position + length, size)
        var cursor = position
        var copied = 0
        while (cursor < end) {
            val index = cursor / blockSize
            val block = blockAt(index)
            val inBlock = (cursor - index * blockSize).toInt()
            val count = minOf(block.length - inBlock, (end - cursor).toInt())
            if (count <= 0) break
            System.arraycopy(block.data, inBlock, buffer, offset + copied, count)
            copied += count
            cursor += count
        }
        return if (copied == 0) -1 else copied
    }

    /** Remote reads and bytes since the previous call. The counters reset on every call. */
    @Synchronized
    fun takeRemoteReadStats(): ListenRemoteReadStats {
        val stats = ListenRemoteReadStats(reads = remoteReads, bytes = remoteBytes)
        remoteReads = 0
        remoteBytes = 0L
        return stats
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        blocks.clear()
        delegate.close()
    }

    private fun blockAt(index: Long): Block {
        blocks[index]?.let { return it }
        val start = index * blockSize
        val length = minOf(blockSize.toLong(), size - start).toInt()
        val data = if (blocks.size >= maxBlocks) {
            // Recycle the least recently used array instead of allocating a block per miss.
            blocks.remove(blocks.keys.first())!!.data
        } else {
            ByteArray(blockSize)
        }
        var filled = 0
        while (filled < length) {
            val count = delegate.readAt(start + filled, data, filled, length - filled)
            remoteReads++
            if (count <= 0) throw IOException("SMB read ended early at offset ${start + filled}")
            remoteBytes += count
            filled += count
        }
        return Block(data, length).also { blocks[index] = it }
    }

    companion object {
        /** One remote read per block. MediaExtractor's small reads are served from memory after that. */
        const val BLOCK_SIZE_BYTES: Int = 1 shl 20

        /** 16 MiB keeps an MP4 index and recent media together; 8 MiB re-fetched the index in testing. */
        const val MAX_BLOCKS: Int = 16
    }
}

data class ListenRemoteReadStats(val reads: Int, val bytes: Long)
