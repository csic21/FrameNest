package com.framenest.data.discovery

/**
 * A LAN candidate for SMB (host fill-in only — never includes credentials).
 */
data class DiscoveredHost(
    /** Preferred connection target (IPv4/IPv6 literal when known). */
    val address: String,
    val hostName: String? = null,
    val serviceName: String? = null,
    val port: Int = DEFAULT_SMB_PORT,
    val sources: Set<DiscoverySource> = emptySet(),
) {
    val displayTitle: String
        get() = serviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: hostName?.trim()?.takeIf { it.isNotEmpty() }
            ?: address

    /** Host field for the add-server form. */
    val connectHost: String get() = address.trim()

    fun mergeKey(): String = address.trim().lowercase()

    companion object {
        const val DEFAULT_SMB_PORT: Int = 445
    }
}

enum class DiscoverySource {
    MDNS,
    PORT_PROBE,
}
