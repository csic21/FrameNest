package com.framenest.data.discovery

/**
 * IPv4 host enumeration for optional SMB port probe.
 * Caps wide prefixes to a /24 around [address] so we never scan thousands of IPs.
 */
object SubnetHosts {
    const val MAX_PROBE_HOSTS: Int = 254

    /**
     * @param address IPv4 as 32-bit (e.g. 192.168.1.10 → 0xC0A8010A)
     * @param prefixLength CIDR prefix (0–32)
     * @return host addresses **excluding** [address], at most [MAX_PROBE_HOSTS]
     */
    fun hostsToProbe(
        address: Int,
        prefixLength: Int,
        maxHosts: Int = MAX_PROBE_HOSTS,
    ): List<Int> {
        val prefix = prefixLength.coerceIn(0, 32)
        val effectivePrefix = if (prefix < 24) 24 else prefix
        val mask = if (effectivePrefix == 0) {
            0
        } else {
            (-1) shl (32 - effectivePrefix)
        }
        val network = address and mask
        val hostBits = 32 - effectivePrefix
        if (hostBits <= 0) return emptyList()
        val size = 1 shl hostBits
        // Skip network / broadcast for typical Ethernet-sized subnets.
        val firstHost = if (hostBits >= 2) 1 else 0
        val lastHost = if (hostBits >= 2) size - 2 else size - 1
        if (lastHost < firstHost) return emptyList()

        val out = ArrayList<Int>(minOf(maxHosts, lastHost - firstHost + 1))
        var i = firstHost
        while (i <= lastHost && out.size < maxHosts) {
            val host = network + i
            if (host != address) {
                out.add(host)
            }
            i++
        }
        return out
    }

    fun ipv4ToString(address: Int): String {
        return buildString(15) {
            append((address ushr 24) and 0xFF)
            append('.')
            append((address ushr 16) and 0xFF)
            append('.')
            append((address ushr 8) and 0xFF)
            append('.')
            append(address and 0xFF)
        }
    }

    fun parseIpv4(text: String): Int? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }
}
