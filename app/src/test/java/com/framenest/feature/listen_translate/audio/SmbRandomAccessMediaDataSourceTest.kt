package com.framenest.feature.listen_translate.audio

import com.framenest.smb.SmbRandomAccess
import java.io.IOException
import java.net.SocketException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbRandomAccessMediaDataSourceTest {

    @Test
    fun exposesKnownSizeRandomReadsAndEof() {
        val bytes = byteArrayOf(10, 20, 30, 40, 50)
        val source = SmbRandomAccessMediaDataSource(FakeRandomAccess(bytes))
        val out = ByteArray(3)

        assertEquals(bytes.size.toLong(), source.size)
        assertEquals(3, source.readAt(1L, out, 0, out.size))
        assertArrayEquals(byteArrayOf(20, 30, 40), out)
        assertEquals(-1, source.readAt(bytes.size.toLong(), out, 0, out.size))
    }

    @Test
    fun closeIsIdempotentAndPreventsFurtherReads() {
        val randomAccess = FakeRandomAccess(byteArrayOf(1, 2, 3))
        val source = SmbRandomAccessMediaDataSource(randomAccess)

        source.close()
        source.close()

        assertTrue(randomAccess.closed)
        assertThrows(IOException::class.java) {
            source.readAt(0L, ByteArray(1), 0, 1)
        }
    }

    @Test
    fun nonOwningWrappersRotateWithoutClosingSharedSmbHandle() {
        val randomAccess = FakeRandomAccess(byteArrayOf(1, 2, 3))
        val first = SmbRandomAccessMediaDataSource(randomAccess, closeRandomAccessOnClose = false)
        first.close()
        val second = SmbRandomAccessMediaDataSource(randomAccess, closeRandomAccessOnClose = false)
        val out = ByteArray(1)

        assertTrue(!randomAccess.closed)
        assertEquals(1, second.readAt(1L, out, 0, 1))
        assertArrayEquals(byteArrayOf(2), out)
        second.close()
        assertTrue(!randomAccess.closed)
    }

    @Test
    fun onlyConnectionStyleFailuresRequestReconnect() {
        assertTrue(isRetryableSmbAudioFailure(SocketException("Broken pipe")))
        assertTrue(
            isRetryableSmbAudioFailure(
                IllegalStateException("java.net.SocketException: Broken pipe"),
            ),
        )
        assertTrue(!isRetryableSmbAudioFailure(IllegalStateException("unsupported codec")))
    }

    private class FakeRandomAccess(
        private val bytes: ByteArray,
    ) : SmbRandomAccess {
        override val size: Long = bytes.size.toLong()
        var closed = false

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
