package com.framenest.feature.listen_translate.audio

import com.framenest.smb.SmbRandomAccess
import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BlockCachingRandomAccessTest {

    @Test
    fun randomReadsMatchTheDelegateAcrossBlockBoundaries() {
        val bytes = Random(7).nextBytes(1_000)
        val cache = BlockCachingRandomAccess(CountingRandomAccess(bytes), blockSize = 64, maxBlocks = 4)
        val random = Random(11)

        repeat(500) {
            val position = random.nextInt(bytes.size).toLong()
            val length = random.nextInt(1, 200)
            val out = ByteArray(length)
            val read = cache.readAt(position, out, 0, length)
            val expected = minOf(length, bytes.size - position.toInt())

            assertEquals(expected, read)
            assertArrayEquals(
                bytes.copyOfRange(position.toInt(), position.toInt() + expected),
                out.copyOf(read),
            )
        }
    }

    @Test
    fun smallSequentialReadsCostOneRemoteReadPerBlock() {
        val bytes = ByteArray(640) { it.toByte() }
        val delegate = CountingRandomAccess(bytes)
        val cache = BlockCachingRandomAccess(delegate, blockSize = 64, maxBlocks = 16)
        val out = ByteArray(10)

        for (position in 0 until bytes.size step 10) {
            assertEquals(10, cache.readAt(position.toLong(), out, 0, out.size))
        }

        assertEquals(10, delegate.reads)
        assertEquals(640L, delegate.readBytes)
    }

    @Test
    fun evictsTheLeastRecentlyUsedBlockFirst() {
        val delegate = CountingRandomAccess(ByteArray(256) { it.toByte() })
        val cache = BlockCachingRandomAccess(delegate, blockSize = 64, maxBlocks = 2)
        val out = ByteArray(1)

        cache.readAt(0L, out, 0, 1)
        cache.readAt(64L, out, 0, 1)
        cache.readAt(0L, out, 0, 1)
        cache.readAt(128L, out, 0, 1)
        assertEquals(3, delegate.reads)

        cache.readAt(0L, out, 0, 1)
        assertEquals(3, delegate.reads)

        cache.readAt(64L, out, 0, 1)
        assertEquals(4, delegate.reads)
    }

    @Test
    fun shortRemoteReadsAreLoopedUntilTheBlockIsFull() {
        val bytes = ByteArray(100) { (it * 3).toByte() }
        val delegate = CountingRandomAccess(bytes, maxChunk = 3)
        val cache = BlockCachingRandomAccess(delegate, blockSize = 64, maxBlocks = 2)
        val out = ByteArray(64)

        assertEquals(64, cache.readAt(0L, out, 0, out.size))

        assertArrayEquals(bytes.copyOfRange(0, 64), out)
        assertEquals(22, delegate.reads)
    }

    @Test
    fun lastBlockIsShortAndEndOfFileReturnsMinusOne() {
        val bytes = ByteArray(100) { it.toByte() }
        val delegate = CountingRandomAccess(bytes)
        val cache = BlockCachingRandomAccess(delegate, blockSize = 64, maxBlocks = 2)
        val out = ByteArray(32)

        assertEquals(10, cache.readAt(90L, out, 0, out.size))

        assertArrayEquals(bytes.copyOfRange(90, 100), out.copyOf(10))
        assertEquals(1, delegate.reads)
        assertEquals(36L, delegate.readBytes)
        assertEquals(-1, cache.readAt(100L, out, 0, out.size))
    }

    @Test
    fun failsWhenTheRemoteReadEndsBeforeTheBlock() {
        val cache = BlockCachingRandomAccess(
            CountingRandomAccess(ByteArray(100), stopAt = 40),
            blockSize = 64,
            maxBlocks = 2,
        )

        assertThrows(IOException::class.java) {
            cache.readAt(0L, ByteArray(10), 0, 10)
        }
    }

    @Test
    fun closeReleasesTheDelegateOnceAndRejectsLaterReads() {
        val delegate = CountingRandomAccess(byteArrayOf(1, 2, 3))
        val cache = BlockCachingRandomAccess(delegate, blockSize = 8, maxBlocks = 2)

        cache.close()
        cache.close()

        assertEquals(1, delegate.closeCount)
        assertThrows(IOException::class.java) {
            cache.readAt(0L, ByteArray(1), 0, 1)
        }
    }

    @Test
    fun takeRemoteReadStatsReportsDeltasAndResets() {
        val cache = BlockCachingRandomAccess(
            CountingRandomAccess(ByteArray(200)),
            blockSize = 64,
            maxBlocks = 4,
        )
        val out = ByteArray(100)

        cache.readAt(0L, out, 0, out.size)
        assertEquals(ListenRemoteReadStats(reads = 2, bytes = 128L), cache.takeRemoteReadStats())

        cache.readAt(10L, out, 0, 20)
        assertEquals(ListenRemoteReadStats(reads = 0, bytes = 0L), cache.takeRemoteReadStats())
    }

    private class CountingRandomAccess(
        private val bytes: ByteArray,
        private val maxChunk: Int = Int.MAX_VALUE,
        private val stopAt: Int? = null,
    ) : SmbRandomAccess {
        override val size: Long = bytes.size.toLong()
        var reads = 0
        var readBytes = 0L
        var closeCount = 0

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            val end = minOf(stopAt ?: bytes.size, bytes.size)
            if (position >= end) return if (stopAt != null) 0 else -1
            val count = minOf(length, maxChunk, end - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            readBytes += count
            return count
        }

        override fun close() {
            closeCount++
        }
    }
}
