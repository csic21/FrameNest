package com.framenest.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRotationPolicyTest {
    @Test
    fun configurationStop_keepsPlaybackRunning() {
        assertFalse(PlayerRotationPolicy.shouldPauseOnStop(isChangingConfigurations = true))
    }

    @Test
    fun backgroundStop_pausesPlayback() {
        assertTrue(PlayerRotationPolicy.shouldPauseOnStop(isChangingConfigurations = false))
    }

    @Test
    fun videoLayout_isRecreatedForDifferentOrMissingHost() {
        val currentActivity = Any()

        assertFalse(
            PlayerRotationPolicy.shouldRecreateVideoLayout(
                existingHost = currentActivity,
                requestedHost = currentActivity,
            ),
        )
        assertTrue(
            PlayerRotationPolicy.shouldRecreateVideoLayout(
                existingHost = currentActivity,
                requestedHost = Any(),
            ),
        )
        assertTrue(
            PlayerRotationPolicy.shouldRecreateVideoLayout(
                existingHost = null,
                requestedHost = currentActivity,
            ),
        )
    }

    @Test
    fun staleContainer_cannotDetachCurrentVideoLayout() {
        val currentContainer = Any()

        assertTrue(
            PlayerRotationPolicy.shouldDetachVideoLayout(
                currentContainer = currentContainer,
                releasingContainer = currentContainer,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldDetachVideoLayout(
                currentContainer = currentContainer,
                releasingContainer = Any(),
            ),
        )
    }
}
