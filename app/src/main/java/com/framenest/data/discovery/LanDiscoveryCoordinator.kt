package com.framenest.data.discovery

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates mDNS (always) + optional subnet 445 probe.
 * Results are [DiscoveryEvent.Found] only; UI owns merge into a list.
 */
class LanDiscoveryCoordinator(
    context: Context,
    private val mdnsFactory: (Context) -> MdnsSmbDiscovery = { MdnsSmbDiscovery(it) },
    private val portProber: SmbPortProber = SmbPortProber(),
    private val localIpv4Finder: () -> List<LocalIpv4> = { LocalIpv4Finder.findAll() },
) {
    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<DiscoveryEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DiscoveryEvent> = _events.asSharedFlow()

    private var mdnsSession: MdnsSmbDiscovery.Session? = null
    private var portJob: Job? = null

    fun startMdns(scope: CoroutineScope) {
        stopMdns()
        val session = mdnsFactory(appContext).start { host ->
            scope.launch {
                _events.emit(DiscoveryEvent.Found(host))
            }
        }
        mdnsSession = session
        scope.launch {
            _events.emit(DiscoveryEvent.Phase(DiscoveryPhase.MDNS_RUNNING))
        }
    }

    fun stopMdns() {
        mdnsSession?.stop()
        mdnsSession = null
    }

    fun startPortScan(scope: CoroutineScope) {
        stopPortScan()
        portJob = scope.launch {
            _events.emit(DiscoveryEvent.Phase(DiscoveryPhase.PORT_SCAN_RUNNING))
            val locals = withContext(Dispatchers.IO) { localIpv4Finder() }
            if (locals.isEmpty()) {
                _events.emit(DiscoveryEvent.Message("无法获取本机局域网地址，已跳过端口探测"))
                _events.emit(DiscoveryEvent.Phase(DiscoveryPhase.PORT_SCAN_DONE))
                return@launch
            }
            val hosts = LinkedHashSet<Int>()
            for (local in locals) {
                hosts.addAll(
                    SubnetHosts.hostsToProbe(
                        address = local.address,
                        prefixLength = local.prefixLength,
                    ),
                )
            }
            _events.emit(
                DiscoveryEvent.Message(
                    "正在探测 ${hosts.size} 个地址的 SMB 端口…",
                ),
            )
            portProber.scan(hosts.toList()) { found ->
                if (isActive) {
                    _events.emit(DiscoveryEvent.Found(found))
                }
            }
            if (isActive) {
                _events.emit(DiscoveryEvent.Phase(DiscoveryPhase.PORT_SCAN_DONE))
            }
        }
    }

    fun stopPortScan() {
        portJob?.cancel()
        portJob = null
    }

    fun stopAll() {
        stopMdns()
        stopPortScan()
    }
}

sealed class DiscoveryEvent {
    data class Found(val host: DiscoveredHost) : DiscoveryEvent()
    data class Phase(val phase: DiscoveryPhase) : DiscoveryEvent()
    data class Message(val text: String) : DiscoveryEvent()
}

enum class DiscoveryPhase {
    MDNS_RUNNING,
    PORT_SCAN_RUNNING,
    PORT_SCAN_DONE,
}
