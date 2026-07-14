package com.framenest.data.discovery

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Lightweight TCP connect probe for SMB (default port 445).
 * Does not authenticate or speak SMB — open port only.
 */
class SmbPortProber(
    private val connectTimeoutMs: Int = 350,
    private val concurrency: Int = 32,
) {
    suspend fun probeOpen(
        host: String,
        port: Int = DiscoveredHost.DEFAULT_SMB_PORT,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Probes many hosts; invokes [onFound] on the caller context for each open port.
     */
    suspend fun scan(
        hostAddresses: List<Int>,
        port: Int = DiscoveredHost.DEFAULT_SMB_PORT,
        onFound: suspend (DiscoveredHost) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        hostAddresses.map { addr ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    coroutineContext.ensureActive()
                    val host = SubnetHosts.ipv4ToString(addr)
                    if (!isActive) return@withPermit
                    val open = try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (open && isActive) {
                        onFound(
                            DiscoveredHost(
                                address = host,
                                port = port,
                                sources = setOf(DiscoverySource.PORT_PROBE),
                            ),
                        )
                    }
                }
            }
        }.awaitAll()
    }
}
