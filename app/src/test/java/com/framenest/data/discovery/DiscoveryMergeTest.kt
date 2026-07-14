package com.framenest.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryMergeTest {

    @Test
    fun upsert_dedupesByAddress_mergesSourcesAndNames() {
        val mdns = DiscoveredHost(
            address = "192.168.1.10",
            serviceName = "FamilyNAS",
            sources = setOf(DiscoverySource.MDNS),
        )
        val port = DiscoveredHost(
            address = "192.168.1.10",
            sources = setOf(DiscoverySource.PORT_PROBE),
        )
        val merged = DiscoveryMerge.upsert(listOf(mdns), port)
        assertEquals(1, merged.size)
        assertEquals("FamilyNAS", merged[0].displayTitle)
        assertEquals(
            setOf(DiscoverySource.MDNS, DiscoverySource.PORT_PROBE),
            merged[0].sources,
        )
    }

    @Test
    fun upsert_keepsDistinctHosts() {
        val a = DiscoveredHost(address = "192.168.1.10", sources = setOf(DiscoverySource.MDNS))
        val b = DiscoveredHost(address = "192.168.1.11", sources = setOf(DiscoverySource.PORT_PROBE))
        val merged = DiscoveryMerge.upsertAll(emptyList(), listOf(a, b))
        assertEquals(2, merged.size)
    }

    @Test
    fun displayTitle_prefersServiceName() {
        val host = DiscoveredHost(
            address = "10.0.0.2",
            hostName = "nas.local",
            serviceName = "Media",
        )
        assertEquals("Media", host.displayTitle)
        assertEquals("10.0.0.2", host.connectHost)
    }
}

class SubnetHostsTest {

    @Test
    fun hostsToProbe_slash24_excludesSelf_andSkipsNetworkBroadcast() {
        // 192.168.1.10 /24
        val self = SubnetHosts.parseIpv4("192.168.1.10")!!
        val hosts = SubnetHosts.hostsToProbe(self, prefixLength = 24)
        assertEquals(253, hosts.size)
        assertTrue(hosts.none { it == self })
        assertTrue(hosts.none { SubnetHosts.ipv4ToString(it) == "192.168.1.0" })
        assertTrue(hosts.none { SubnetHosts.ipv4ToString(it) == "192.168.1.255" })
    }

    @Test
    fun hostsToProbe_widePrefix_cappedToSlash24() {
        val self = SubnetHosts.parseIpv4("10.0.0.5")!!
        val hosts = SubnetHosts.hostsToProbe(self, prefixLength = 16, maxHosts = 254)
        assertTrue(hosts.size <= 254)
        // All hosts stay in 10.0.0.x when capped to /24 around self.
        assertTrue(hosts.all { SubnetHosts.ipv4ToString(it).startsWith("10.0.0.") })
    }

    @Test
    fun ipv4_roundTrip() {
        val raw = "192.168.100.7"
        val n = SubnetHosts.parseIpv4(raw)!!
        assertEquals(raw, SubnetHosts.ipv4ToString(n))
    }
}
