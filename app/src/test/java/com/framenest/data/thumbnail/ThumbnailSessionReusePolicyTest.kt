package com.framenest.data.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSessionReusePolicyTest {
    private val first = ThumbnailConnectionKey("1", "nas", 445, "u", "", "alias")

    @Test
    fun connectedSameConfiguration_reusesWorkerSession() {
        assertFalse(
            ThumbnailSessionReusePolicy.requiresNewSession(
                current = first,
                requested = first.copy(),
                connected = true,
            ),
        )
    }

    @Test
    fun disconnectedOrChangedConfiguration_reconnects() {
        assertTrue(
            ThumbnailSessionReusePolicy.requiresNewSession(
                current = first,
                requested = first,
                connected = false,
            ),
        )
        assertTrue(
            ThumbnailSessionReusePolicy.requiresNewSession(
                current = first,
                requested = first.copy(host = "other"),
                connected = true,
            ),
        )
    }
}
