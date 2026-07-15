package com.framenest.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers SMB advertisers via mDNS/NSD (`_smb._tcp.`).
 * Callbacks may arrive on binder/background threads — caller should hop to main/VM if needed.
 */
class MdnsSmbDiscovery(
    context: Context,
    private val serviceType: String = SERVICE_TYPE_SMB,
) {
    private val appContext = context.applicationContext
    private val nsd: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    fun start(
        onFound: (DiscoveredHost) -> Unit,
        onStartFailed: (errorCode: Int?) -> Unit = {},
    ): Session {
        val manager = nsd
        if (manager == null) {
            onStartFailed(null)
            return Session.inactive()
        }
        // Executor lifetime matches this discovery session, avoiding a leaked
        // thread for every time the discovery dialog is opened.
        val resolveExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mdns-smb-resolve").apply { isDaemon = true }
        }
        val active = AtomicBoolean(true)
        val resolving = ConcurrentHashMap.newKeySet<String>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                if (active.compareAndSet(true, false)) {
                    runCatching { manager.stopServiceDiscovery(this) }
                    resolveExecutor.shutdownNow()
                    onStartFailed(errorCode)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                active.set(false)
                resolveExecutor.shutdownNow()
            }
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) {
                active.set(false)
                resolveExecutor.shutdownNow()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active.get()) return
                val key = "${serviceInfo.serviceName}|${serviceInfo.serviceType}"
                if (!resolving.add(key)) return
                resolve(manager, serviceInfo, resolveExecutor, active, onFound) {
                    resolving.remove(key)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        return try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Session(
                active = active,
                stop = {
                    if (active.compareAndSet(true, false)) {
                        runCatching { manager.stopServiceDiscovery(discoveryListener) }
                    }
                    resolveExecutor.shutdownNow()
                },
            )
        } catch (_: Exception) {
            active.set(false)
            resolveExecutor.shutdownNow()
            onStartFailed(null)
            Session.inactive()
        }
    }

    private fun resolve(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        resolveExecutor: java.util.concurrent.Executor,
        active: AtomicBoolean,
        onFound: (DiscoveredHost) -> Unit,
        onDone: () -> Unit,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                onDone()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                    if (!active.get()) return
                    // Never call InetAddress.getCanonicalHostName()/getHostName() here:
                    // reverse DNS is network I/O and can throw NetworkOnMainThreadException
                    // when the callback is delivered on the main thread (common on older APIs).
                    val host = toDiscoveredHost(resolved) ?: return
                    onFound(host)
                } catch (_: Exception) {
                    // One bad service advertisement must not crash the app.
                } finally {
                    onDone()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                manager.resolveService(serviceInfo, resolveExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.resolveService(serviceInfo, listener)
            }
        } catch (_: Exception) {
            onDone()
        }
    }

    class Session(
        private val active: AtomicBoolean,
        private val stop: () -> Unit,
    ) {
        fun stop() = stop.invoke()
        fun isActive(): Boolean = active.get()

        companion object {
            fun inactive(): Session = Session(AtomicBoolean(false)) {}
        }
    }

    /**
     * Maps a resolved NSD service to a [DiscoveredHost] without reverse DNS.
     * Prefer mDNS service name for display; IP for connection.
     */
    internal fun toDiscoveredHost(info: NsdServiceInfo): DiscoveredHost? {
        val inet = resolvedHost(info) ?: return null
        // hostAddress is a local string conversion; does not perform network I/O.
        val address = inet.hostAddress?.takeIf { it.isNotBlank() } ?: return null
        val serviceName = info.serviceName?.trim()?.takeIf { it.isNotEmpty() }
        val port = info.port.takeIf { it in 1..65535 }
            ?: DiscoveredHost.DEFAULT_SMB_PORT
        return DiscoveredHost(
            address = address,
            hostName = null,
            serviceName = serviceName,
            port = port,
            sources = setOf(DiscoverySource.MDNS),
        )
    }

    private fun resolvedHost(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val list = info.hostAddresses
            if (!list.isNullOrEmpty()) return list.first()
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    companion object {
        const val SERVICE_TYPE_SMB: String = "_smb._tcp."
    }
}
