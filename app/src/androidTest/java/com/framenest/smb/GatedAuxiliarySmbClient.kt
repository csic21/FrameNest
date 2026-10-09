package com.framenest.smb

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fake auxiliary transport: no sockets, NAS addresses or credentials. */
internal class GatedAuxiliarySmbClient(private val stage: Stage? = null) : SmbClient {
    enum class Stage { CONNECT, LIST, READ }
    val entered = CountDownLatch(1)
    val aborted = CountDownLatch(1)
    val unblock = CountDownLatch(1)
    val connects = AtomicInteger()
    val aborts = AtomicInteger()
    @Volatile var abortThread: Thread? = null
    override val isConnected get() = aborts.get() == 0
    private val content = "1\n00:00:00,000 --> 00:00:01,000\nTest subtitle\n".toByteArray()

    private fun gate(at: Stage) {
        if (at != stage) return
        entered.countDown()
        check(unblock.await(5, TimeUnit.SECONDS)) { "Auxiliary operation not aborted" }
        throw IOException("Controlled retired transport")
    }
    override fun connect(credentials: SmbCredentials) { connects.incrementAndGet(); gate(Stage.CONNECT) }
    override fun listShares(knownShares: List<String>) = emptyList<String>()
    override fun listDirectory(shareName: String, path: String): List<SmbEntry> {
        gate(Stage.LIST)
        return emptyList()
    }
    override fun metadata(shareName: String, path: String): SmbFileMetadata = error("Unused")
    override fun openRandomAccess(shareName: String, path: String) = object : SmbRandomAccess {
        override val size = content.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            gate(Stage.READ)
            val count = minOf(length, content.size - position.toInt())
            if (count <= 0) return -1
            content.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
    override fun disconnect() = abort()
    override fun close() = abort()
    override fun abort() {
        abortThread = Thread.currentThread()
        aborts.incrementAndGet()
        unblock.countDown()
        aborted.countDown()
    }
}
