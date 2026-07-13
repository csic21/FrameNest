package com.framenest.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates client-wrapper contracts with an in-memory fake (no real NAS).
 */
class FakeSmbClientTest {

    @Test
    fun listDirectory_usesPathUtilsSorting() {
        val client = FakeSmbClient(
            files = mapOf(
                "media" to listOf(
                    SmbEntry("z.mkv", "z.mkv", false, 10, 0),
                    SmbEntry("A", "A", true, 0, 0),
                    SmbEntry("b.mkv", "b.mkv", false, 5, 0),
                ),
            ),
        )
        client.connect(SmbCredentials("h", username = "u", password = CharArray(0)))
        val names = client.listDirectory("media").map { it.name }
        assertEquals(listOf("A", "b.mkv", "z.mkv"), names)
    }

    @Test
    fun randomAccess_readsAcrossOffsets() {
        val payload = ByteArray(1024) { i -> (i % 251).toByte() }
        val client = FakeSmbClient(
            files = mapOf("media" to emptyList()),
            fileContents = mapOf("media/video.mp4" to payload),
        )
        client.connect(SmbCredentials("h", username = "u", password = CharArray(0)))
        client.openRandomAccess("media", "video.mp4").use { raf ->
            assertEquals(1024, raf.size)
            val mid = raf.readFullyAt(500, 4)
            assertEquals(payload.sliceArray(500 until 504).toList(), mid.toList())
        }
    }

    @Test
    fun disconnect_marksNotConnected() {
        val client = FakeSmbClient()
        client.connect(SmbCredentials("h", username = "u", password = CharArray(0)))
        assertTrue(client.isConnected)
        client.disconnect()
        assertTrue(!client.isConnected)
    }

    private class FakeSmbClient(
        private val files: Map<String, List<SmbEntry>> = emptyMap(),
        private val fileContents: Map<String, ByteArray> = emptyMap(),
    ) : SmbClient {
        private var connected = false

        override val isConnected: Boolean get() = connected

        override fun connect(credentials: SmbCredentials) {
            connected = true
        }

        override fun listShares(knownShares: List<String>): List<String> =
            knownShares.filter { files.containsKey(it) }

        override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
            check(connected)
            return SmbPathUtils.sortEntries(files[shareName].orEmpty())
        }

        override fun metadata(shareName: String, path: String): SmbFileMetadata {
            check(connected)
            val key = "$shareName/${SmbPathUtils.normalizeRelative(path)}"
            val bytes = fileContents[key]
                ?: throw SmbException(SmbError.NotFound("missing"))
            return SmbFileMetadata(path, bytes.size.toLong(), 0L, false)
        }

        override fun openRandomAccess(shareName: String, path: String): SmbRandomAccess {
            check(connected)
            val key = "$shareName/${SmbPathUtils.normalizeRelative(path)}"
            val bytes = fileContents[key]
                ?: throw SmbException(SmbError.NotFound("missing"))
            return object : SmbRandomAccess {
                override val size: Long = bytes.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(length, (bytes.size - position).toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun close() = Unit
            }
        }

        override fun disconnect() {
            connected = false
        }

        override fun close() {
            disconnect()
        }
    }
}
