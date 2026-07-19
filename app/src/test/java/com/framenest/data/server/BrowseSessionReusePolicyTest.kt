package com.framenest.data.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSessionReusePolicyTest {
    private val first = BrowseConnectionKey("1", "nas", 445, "u", "", "alias")

    @Test
    fun sameConnectedServer_reusesSession() {
        assertFalse(
            BrowseSessionReusePolicy.requiresNewSession(
                current = first,
                requested = first.copy(),
                connected = true,
            ),
        )
    }

    @Test
    fun disconnectedOrChangedCredentialAlias_reconnects() {
        assertTrue(
            BrowseSessionReusePolicy.requiresNewSession(first, first, connected = false),
        )
        assertTrue(
            BrowseSessionReusePolicy.requiresNewSession(
                current = first,
                requested = first.copy(credentialAlias = "new"),
                connected = true,
            ),
        )
    }
}
