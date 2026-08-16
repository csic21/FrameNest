package com.framenest.data.discovery

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalIpv4(
    val address: Int,
    val prefixLength: Int,
    val addressString: String,
)

/**
 * Site-local IPv4 interfaces suitable for LAN SMB discovery (no loopback / link-local only).
 */
object LocalIpv4Finder {
    fun findAll(): List<LocalIpv4> {
        val out = ArrayList<LocalIpv4>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (nif in interfaces) {
            // Interfaces can disappear between enumeration and property reads during
            // Wi-Fi/VPN changes. Isolate one stale interface instead of aborting discovery.
            val addresses = try {
                if (!nif.isUp || nif.isLoopback) continue
                nif.interfaceAddresses.toList()
            } catch (_: Exception) {
                continue
            }
            for (addr in addresses) {
                val local = try {
                    val inet = addr.address as? Inet4Address ?: continue
                    if (inet.isLoopbackAddress) continue
                    if (inet.isLinkLocalAddress) continue
                    if (!inet.isSiteLocalAddress) continue
                    val host = inet.hostAddress ?: continue
                    val asInt = SubnetHosts.parseIpv4(host) ?: continue
                    val prefix = addr.networkPrefixLength.toInt().coerceIn(0, 32)
                    LocalIpv4(
                        address = asInt,
                        prefixLength = prefix,
                        addressString = host,
                    )
                } catch (_: Exception) {
                    null
                }
                if (local != null) out.add(local)
            }
        }
        return out.distinctBy { it.addressString }
    }
}
