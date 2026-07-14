package com.framenest.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers SMB advertisers via mDNS/NSD (`_smb._tcp.`).
 * Callbacks may arrive on binder threads — caller should hop to main/VM if needed.
 */
class MdnsSmbDiscovery(
    context: Context,
    private val serviceType: String = SERVICE_TYPE_SMB,
) {
    private val appContext = context.applicationContext
    private val nsd: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    fun start(onFound: (DiscoveredHost) -> Unit): Session {
        val manager = nsd
        if (manager == null) {
            return Session.inactive()
        }
        val active = AtomicBoolean(true)
        val resolving = ConcurrentHashMap.newKeySet<String>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active.get()) return
                val key = "${serviceInfo.serviceName}|${serviceInfo.serviceType}"
                if (!resolving.add(key)) return
                resolve(manager, serviceInfo, active, onFound) {
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
                },
            )
        } catch (_: Exception) {
            Session.inactive()
        }
    }

    private fun resolve(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
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
                    val host = resolvedHost(resolved) ?: return
                    val address = host.hostAddress?.takeIf { it.isNotBlank() } ?: return
                    val hostName = host.canonicalHostName
                        .takeIf { it.isNotBlank() && !it.equals(address, ignoreCase = true) }
                    val serviceName = resolved.serviceName.trim().takeIf { it.isNotEmpty() }
                    val port = resolved.port.takeIf { it in 1..65535 }
                        ?: DiscoveredHost.DEFAULT_SMB_PORT
                    onFound(
                        DiscoveredHost(
                            address = address,
                            hostName = hostName,
                            serviceName = serviceName,
                            port = port,
                            sources = setOf(DiscoverySource.MDNS),
                        ),
                    )
                } finally {
                    onDone()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // resolveService(NsdServiceInfo, Executor, ResolveListener) is API 34+.
                manager.resolveService(serviceInfo, appContext.mainExecutor, listener)
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
