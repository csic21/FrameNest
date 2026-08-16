package com.framenest.data.discovery

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Lightweight TCP connect probe for SMB (default port 445).
 * Does not authenticate or speak SMB — open port only.
 */
class SmbPortProber(
    private val connectTimeoutMs: Int = 350,
    private val concurrency: Int = 32,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun probeOpen(
        host: String,
        port: Int = DiscoveredHost.DEFAULT_SMB_PORT,
    ): Boolean = withContext(ioDispatcher) { connectOpen(host, port) }

    /**
     * Probes many hosts with a fixed-size I/O worker set. At most [concurrency] socket connects
     * and coroutine jobs exist regardless of subnet size.
     */
    suspend fun scan(
        hostAddresses: List<Int>,
        port: Int = DiscoveredHost.DEFAULT_SMB_PORT,
        onFound: suspend (DiscoveredHost) -> Unit,
    ) = withContext(ioDispatcher) {
        scanAddressesBounded(
            addresses = hostAddresses,
            concurrency = concurrency,
            probe = { address ->
                connectOpen(SubnetHosts.ipv4ToString(address), port)
            },
            onFound = { address ->
                onFound(
                    DiscoveredHost(
                        address = SubnetHosts.ipv4ToString(address),
                        port = port,
                        sources = setOf(DiscoverySource.PORT_PROBE),
                    ),
                )
            },
        )
    }

    private fun connectOpen(host: String, port: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
}

internal fun probeWorkerCount(addressCount: Int, concurrency: Int): Int =
    minOf(addressCount.coerceAtLeast(0), concurrency.coerceAtLeast(1))

internal suspend fun scanAddressesBounded(
    addresses: List<Int>,
    concurrency: Int,
    probe: suspend (Int) -> Boolean,
    onFound: suspend (Int) -> Unit,
) = coroutineScope {
    val nextIndex = AtomicInteger(0)
    repeat(probeWorkerCount(addresses.size, concurrency)) {
        launch {
            while (true) {
                coroutineContext.ensureActive()
                val index = nextIndex.getAndIncrement()
                if (index >= addresses.size) break
                val address = addresses[index]
                if (probe(address)) {
                    // Socket.connect is blocking on Android. Re-check after it returns so a
                    // cancelled scan never publishes a stale result.
                    coroutineContext.ensureActive()
                    onFound(address)
                }
            }
        }
    }
}
