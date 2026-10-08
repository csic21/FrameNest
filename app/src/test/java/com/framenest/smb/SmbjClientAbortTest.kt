package com.framenest.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.event.SMBEventBus
import com.hierynomus.smbj.server.ServerList
import com.hierynomus.smbj.session.Session
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No NAS: recording SMBJ fakes plus one real transport with a silent loopback peer. */
class SmbjClientAbortTest {
    @Test(timeout = 5_000)
    fun abort_skipsGracefulSessionClose_andNotifiesWaiters_afterForcedTransportClose() {
        val backend = TestBackend()
        val client = SmbjClient(config = backend.config, clientFactory = { backend }, connectionFactory = { _, _ -> backend.connection })
        client.connect(credentials())
        assertTrue(client.isConnected)
        repeat(3) { client.abort() }
        client.close()
        client.disconnect()
        assertFalse(client.isConnected)
        assertEquals(listOf("force", "notify"), backend.connection.events)
        assertEquals(0, backend.connection.sessionCloseCount.get())
        assertEquals(1, backend.closeCount.get())
    }

    @Test(timeout = 5_000)
    fun abortDuringAuthentication_forcesPublishedConnection_withoutWaitingForAuthentication() {
        val backend = TestBackend(blockAuthentication = true)
        val client = SmbjClient(config = backend.config, clientFactory = { backend }, connectionFactory = { _, _ -> backend.connection })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connect = executor.submit<Boolean> { runCatching { client.connect(credentials()) }.isFailure }
            assertTrue(backend.connection.authenticationEntered.await(1, TimeUnit.SECONDS))
            client.abort()
            assertTrue(connect.get(1, TimeUnit.SECONDS))
            assertFalse(client.isConnected)
            assertEquals(listOf("force", "notify"), backend.connection.events)
        } finally {
            backend.connection.finishAuthentication.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun disconnectDuringConnect_lateOldCompletion_cannotReplaceOrCloseNewConnection() {
        val old = TestBackend(blockConnect = true)
        val next = TestBackend()
        val count = AtomicInteger()
        val client = SmbjClient(config = old.config, clientFactory = { if (count.getAndIncrement() == 0) old else next }, connectionFactory = { _, backend -> (backend as TestBackend).connection })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connect = executor.submit<Boolean> { runCatching { client.connect(credentials()) }.isFailure }
            assertTrue(old.connectEntered.await(1, TimeUnit.SECONDS))
            client.disconnect()
            client.connect(credentials())
            assertTrue(client.isConnected)
            old.finishConnect.countDown()
            assertTrue(connect.get(1, TimeUnit.SECONDS))
            assertTrue(client.isConnected)
            assertEquals(listOf("force", "notify", "force", "notify"), old.connection.events)
            assertTrue(next.connection.events.isEmpty())
            assertEquals(0, next.closeCount.get())
        } finally {
            old.finishConnect.countDown()
            client.abort()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun oldBlockingCleanup_doesNotHoldStateLock_orCloseReconnectedClient() {
        val old = TestBackend(blockCleanup = true)
        val next = TestBackend()
        val count = AtomicInteger()
        val client = SmbjClient(config = old.config, clientFactory = { if (count.getAndIncrement() == 0) old else next }, connectionFactory = { _, backend -> (backend as TestBackend).connection })
        val executor = Executors.newSingleThreadExecutor()
        try {
            client.connect(credentials())
            val disconnect = executor.submit { client.disconnect() }
            assertTrue(old.cleanupEntered.await(1, TimeUnit.SECONDS))
            client.connect(credentials())
            assertTrue(client.isConnected)
            old.finishCleanup.countDown()
            disconnect.get(1, TimeUnit.SECONDS)
            assertTrue(client.isConnected)
            assertTrue(next.connection.events.isEmpty())
            assertEquals(0, next.closeCount.get())
        } finally {
            old.finishCleanup.countDown()
            client.abort()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun abortDuringNegotiation_closesRealLoopbackTransport_andWakesPendingFuture() {
        // A deliberately silent local TCP peer, not a NAS or an SMB service.
        // Exercise the real SMBJ Connection / transport / outstanding future.
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newFixedThreadPool(2)
        val sawNegotiation = CountDownLatch(1)
        val client = SmbjClient()
        val peer = AtomicReference<Socket>()
        try {
            val accepted = executor.submit<Socket> {
                server.accept().also { socket ->
                    peer.set(socket)
                    socket.soTimeout = 2_000
                    check(socket.getInputStream().read() >= 0)
                    sawNegotiation.countDown()
                }
            }
            val connect = executor.submit<Boolean> {
                runCatching {
                    client.connect(
                        SmbCredentials(
                            host = server.inetAddress.hostAddress,
                            port = server.localPort,
                            username = "",
                            password = charArrayOf(),
                        ),
                    )
                }.isFailure
            }
            assertTrue(sawNegotiation.await(2, TimeUnit.SECONDS))
            accepted.get(1, TimeUnit.SECONDS)
            client.abort()
            assertTrue(connect.get(1, TimeUnit.SECONDS))
            assertFalse(client.isConnected)
        } finally {
            client.abort()
            peer.get()?.close()
            server.close()
            executor.shutdownNow()
        }
    }

    private fun credentials() = SmbCredentials(host = "example.invalid", username = "", password = charArrayOf())
}

private class TestBackend(
    val config: SmbConfig = SmbjClient.defaultConfig(),
    private val blockConnect: Boolean = false,
    blockAuthentication: Boolean = false,
    private val blockCleanup: Boolean = false,
) : SMBClient(config) {
    val connection = TestConnection(config, this, blockAuthentication)
    val connectEntered = CountDownLatch(1)
    val finishConnect = CountDownLatch(1)
    val cleanupEntered = CountDownLatch(1)
    val finishCleanup = CountDownLatch(1)
    val closeCount = AtomicInteger()
    fun awaitConnect() {
        connectEntered.countDown()
        if (blockConnect) check(finishConnect.await(3, TimeUnit.SECONDS))
    }
    override fun close() {
        closeCount.incrementAndGet()
        cleanupEntered.countDown()
        if (blockCleanup) check(finishCleanup.await(3, TimeUnit.SECONDS))
    }
}

private class TestConnection(
    config: SmbConfig,
    private val backend: TestBackend,
    private val blockAuthentication: Boolean,
) : Connection(config, backend, SMBEventBus(), ServerList()) {
    val events = mutableListOf<String>()
    val sessionCloseCount = AtomicInteger()
    val authenticationEntered = CountDownLatch(1)
    val finishAuthentication = CountDownLatch(1)
    @Volatile private var connected = true
    private val fakeSession = object : Session(this, config, null, null, null, null, null) {
        override fun close() { sessionCloseCount.incrementAndGet(); error("Graceful close must be skipped") }
    }
    override fun connect(hostname: String, port: Int) { backend.awaitConnect() }
    override fun isConnected() = connected
    override fun authenticate(authContext: AuthenticationContext): Session {
        authenticationEntered.countDown()
        if (blockAuthentication) check(finishAuthentication.await(3, TimeUnit.SECONDS))
        return fakeSession
    }
    override fun close(force: Boolean) {
        check(force) { "Graceful close must be skipped" }
        events.add("force")
        connected = false
    }
    override fun handleError(error: Throwable) {
        check(!connected) { "Waiter notification must follow transport shutdown" }
        events.add("notify")
        finishAuthentication.countDown()
    }
}
