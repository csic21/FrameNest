package com.framenest.feature.listen_translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadNetworkPolicyTest {

    @Test
    fun default_allowsWifiOrEthernetButNotMeteredOrOffline() {
        assertTrue(
            ModelDownloadNetworkPolicy.isAllowed(
                allowMetered = false,
                connected = true,
                wifiOrEthernet = true,
            ),
        )
        assertFalse(
            ModelDownloadNetworkPolicy.isAllowed(
                allowMetered = false,
                connected = true,
                wifiOrEthernet = false,
            ),
        )
        assertFalse(
            ModelDownloadNetworkPolicy.isAllowed(
                allowMetered = false,
                connected = false,
                wifiOrEthernet = false,
            ),
        )
    }

    @Test
    fun explicitOptIn_allowsConnectedMeteredNetwork() {
        assertTrue(
            ModelDownloadNetworkPolicy.isAllowed(
                allowMetered = true,
                connected = true,
                wifiOrEthernet = false,
            ),
        )
    }
}
