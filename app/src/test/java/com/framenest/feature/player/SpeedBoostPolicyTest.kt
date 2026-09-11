package com.framenest.feature.player

import com.framenest.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedBoostPolicyTest {

    @Test
    fun `eligible only while really playing`() {
        assertTrue(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Playing))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Paused))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Ready))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Preparing))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Ended))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Error))
        assertFalse(SpeedBoostPolicy.isEligiblePhase(PlayerState.Phase.Idle))
    }

    @Test
    fun `hold without drag enters boost after delay`() {
        assertFalse(
            SpeedBoostPolicy.shouldEnterBoost(
                pressDurationMs = SpeedBoostPolicy.ENTER_DELAY_MS - 1,
                movedBeyondSlop = false,
                playing = true,
                controlsLocked = false,
            ),
        )
        assertTrue(
            SpeedBoostPolicy.shouldEnterBoost(
                pressDurationMs = SpeedBoostPolicy.ENTER_DELAY_MS,
                movedBeyondSlop = false,
                playing = true,
                controlsLocked = false,
            ),
        )
    }

    @Test
    fun `drag pause and lock win over boost`() {
        // A classified drag owns the finger.
        assertFalse(
            SpeedBoostPolicy.shouldEnterBoost(
                pressDurationMs = 5_000L,
                movedBeyondSlop = true,
                playing = true,
                controlsLocked = false,
            ),
        )
        // Never boost when not playing or when locked.
        assertFalse(
            SpeedBoostPolicy.shouldEnterBoost(
                pressDurationMs = 5_000L,
                movedBeyondSlop = false,
                playing = false,
                controlsLocked = false,
            ),
        )
        assertFalse(
            SpeedBoostPolicy.shouldEnterBoost(
                pressDurationMs = 5_000L,
                movedBeyondSlop = false,
                playing = true,
                controlsLocked = true,
            ),
        )
    }

    @Test
    fun `boost release suppresses the tap underneath`() {
        assertTrue(SpeedBoostPolicy.shouldSuppressTapAfterBoost(true))
        assertFalse(SpeedBoostPolicy.shouldSuppressTapAfterBoost(false))
    }

    @Test
    fun `restore keeps an explicit mid-boost rate change`() {
        assertEquals(
            1.5f,
            SpeedBoostPolicy.restoreRateOrNull(
                currentRate = SpeedBoostPolicy.BOOST_RATE,
                savedUserRate = 1.5f,
            ),
        )
        // User cycled speed with a second finger mid-boost: keep their choice.
        assertNull(
            SpeedBoostPolicy.restoreRateOrNull(
                currentRate = 1.5f,
                savedUserRate = 1.0f,
            ),
        )
        assertNull(
            SpeedBoostPolicy.restoreRateOrNull(
                currentRate = SpeedBoostPolicy.BOOST_RATE,
                savedUserRate = null,
            ),
        )
    }
}
