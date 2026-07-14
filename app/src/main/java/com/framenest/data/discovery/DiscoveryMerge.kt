package com.framenest.data.discovery

/**
 * Pure merge/dedupe for discovery results (same IP → one row).
 * Prefers non-blank names and unions [DiscoveredHost.sources].
 */
object DiscoveryMerge {
    fun upsert(existing: List<DiscoveredHost>, incoming: DiscoveredHost): List<DiscoveredHost> {
        val key = incoming.mergeKey()
        if (key.isEmpty()) return existing
        val index = existing.indexOfFirst { it.mergeKey() == key }
        if (index < 0) {
            return (existing + incoming).sortedWith(DISCOVERY_ORDER)
        }
        val prev = existing[index]
        val merged = prev.copy(
            address = prev.address.ifBlank { incoming.address },
            hostName = preferText(prev.hostName, incoming.hostName),
            serviceName = preferText(prev.serviceName, incoming.serviceName),
            port = when {
                prev.port != DiscoveredHost.DEFAULT_SMB_PORT -> prev.port
                else -> incoming.port
            },
            sources = prev.sources + incoming.sources,
        )
        return existing.toMutableList().also { it[index] = merged }
            .sortedWith(DISCOVERY_ORDER)
    }

    fun upsertAll(
        existing: List<DiscoveredHost>,
        incoming: Iterable<DiscoveredHost>,
    ): List<DiscoveredHost> {
        var acc = existing
        for (item in incoming) {
            acc = upsert(acc, item)
        }
        return acc
    }

    private fun preferText(a: String?, b: String?): String? {
        val left = a?.trim()?.takeIf { it.isNotEmpty() }
        val right = b?.trim()?.takeIf { it.isNotEmpty() }
        return left ?: right
    }

    private val DISCOVERY_ORDER = compareBy<DiscoveredHost>(
        { it.displayTitle.lowercase() },
        { it.address },
    )
}
