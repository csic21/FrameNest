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
    fun foreground_repaintsRestingFrameOnly() {
        assertTrue(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Paused,
            ),
        )
        assertTrue(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Ready,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = false,
                phase = PlayerState.Phase.Paused,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Playing,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Preparing,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Ended,
            ),
        )
        assertFalse(
            PlayerRotationPolicy.shouldRepaintOnForeground(
                firstFrameReady = true,
                phase = PlayerState.Phase.Error,
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
