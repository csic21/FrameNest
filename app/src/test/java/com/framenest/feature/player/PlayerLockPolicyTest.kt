package com.framenest.feature.player

import android.content.pm.ActivityInfo
import com.framenest.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLockPolicyTest {

    @Test
    fun consumeBack_unlocksBeforeLeave() {
        assertEquals(
            PlayerLockPolicy.BackAction.Unlock,
            PlayerLockPolicy.consumeBack(controlsLocked = true),
        )
        assertEquals(
            PlayerLockPolicy.BackAction.Leave,
            PlayerLockPolicy.consumeBack(controlsLocked = false),
        )
    }

    @Test
    fun showChrome_hiddenWhenLocked() {
        assertFalse(
            PlayerLockPolicy.showChrome(
                controlsLocked = true,
                chromeVisible = true,
                phase = PlayerState.Phase.Playing,
            ),
        )
        assertFalse(
            PlayerLockPolicy.showChrome(
                controlsLocked = true,
                chromeVisible = true,
                phase = PlayerState.Phase.Paused,
            ),
        )
    }

    @Test
    fun showChrome_whenUnlocked_matchesPhaseRule() {
        assertFalse(
            PlayerLockPolicy.showChrome(
                controlsLocked = false,
                chromeVisible = false,
                phase = PlayerState.Phase.Playing,
            ),
        )
        assertTrue(
            PlayerLockPolicy.showChrome(
                controlsLocked = false,
                chromeVisible = false,
                phase = PlayerState.Phase.Paused,
            ),
        )
        assertTrue(
            PlayerLockPolicy.showChrome(
                controlsLocked = false,
                chromeVisible = true,
                phase = PlayerState.Phase.Playing,
            ),
        )
    }

    @Test
    fun shouldAutoUnlock_onlyOnError() {
        assertTrue(PlayerLockPolicy.shouldAutoUnlock(PlayerState.Phase.Error))
        assertFalse(PlayerLockPolicy.shouldAutoUnlock(PlayerState.Phase.Playing))
        assertFalse(PlayerLockPolicy.shouldAutoUnlock(PlayerState.Phase.Ended))
        assertFalse(PlayerLockPolicy.shouldAutoUnlock(PlayerState.Phase.Paused))
    }

    @Test
    fun orientationRequest_mapsLockedAndFree() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            PlayerLockPolicy.orientationRequest(orientationLocked = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            PlayerLockPolicy.orientationRequest(orientationLocked = false),
        )
    }

    @Test
    fun orientationRequest_fullscreenForcesLandscapeThenPortrait() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            PlayerLockPolicy.orientationRequest(
                orientationLocked = true,
                forceLandscape = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            PlayerLockPolicy.orientationRequest(
                orientationLocked = false,
                forcePortrait = true,
            ),
        )
    }

    @Test
    fun explicitFullscreen_drivesExitStateAndReleasesPortraitRequestAfterRotation() {
        val entered = PlayerLockPolicy.toggleFullscreen(PlayerLockPolicy.FullscreenState())
        assertTrue(entered.explicitLandscape)
        assertFalse(entered.portraitExitPending)

        val exiting = PlayerLockPolicy.toggleFullscreen(entered)
        assertFalse(exiting.explicitLandscape)
        assertTrue(exiting.portraitExitPending)
        assertEquals(
            exiting,
            PlayerLockPolicy.settleFullscreenTransition(exiting, isLandscape = true),
        )
        assertEquals(
            PlayerLockPolicy.FullscreenState(),
            PlayerLockPolicy.settleFullscreenTransition(exiting, isLandscape = false),
        )
    }

    @Test
    fun naturalLandscape_doesNotImplyExplicitFullscreenExit() {
        val state = PlayerLockPolicy.settleFullscreenTransition(
            state = PlayerLockPolicy.FullscreenState(),
            isLandscape = true,
        )

        assertFalse(state.explicitLandscape)
        assertFalse(state.portraitExitPending)
    }
}
