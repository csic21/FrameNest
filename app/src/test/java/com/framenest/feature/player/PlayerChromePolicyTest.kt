package com.framenest.feature.player

import com.framenest.player.PlayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChromePolicyTest {

    @Test
    fun autoHide_onlyWhilePlayingWithoutModalUi() {
        assertTrue(
            PlayerChromePolicy.shouldAutoHide(
                phase = PlayerState.Phase.Playing,
                chromeVisible = true,
                controlsLocked = false,
                panelOpen = false,
            ),
        )
        assertFalse(
            PlayerChromePolicy.shouldAutoHide(
                phase = PlayerState.Phase.Paused,
                chromeVisible = true,
                controlsLocked = false,
                panelOpen = false,
            ),
        )
        assertFalse(
            PlayerChromePolicy.shouldAutoHide(
                phase = PlayerState.Phase.Playing,
                chromeVisible = true,
                controlsLocked = false,
                panelOpen = true,
            ),
        )
        assertFalse(
            PlayerChromePolicy.shouldAutoHide(
                phase = PlayerState.Phase.Playing,
                chromeVisible = true,
                controlsLocked = true,
                panelOpen = false,
            ),
        )
        assertFalse(
            PlayerChromePolicy.shouldAutoHide(
                phase = PlayerState.Phase.Playing,
                chromeVisible = true,
                controlsLocked = false,
                panelOpen = false,
                userSeeking = true,
            ),
        )
    }

    @Test
    fun compactActions_coverNarrowWidthAndLargeText() {
        assertTrue(PlayerControlLayoutPolicy.useTwoActionRows(widthDp = 360f, fontScale = 1f))
        assertTrue(PlayerControlLayoutPolicy.useTwoActionRows(widthDp = 600f, fontScale = 1.3f))
        assertFalse(PlayerControlLayoutPolicy.useTwoActionRows(widthDp = 600f, fontScale = 1f))
    }

    @Test
    fun errorNeverEnablesBottomPlayAsASecondRetryEntry() {
        assertFalse(
            PlayerActionPolicy.playEnabled(
                phase = PlayerState.Phase.Error,
                canPlay = true,
                canPause = true,
            ),
        )
        assertTrue(
            PlayerActionPolicy.playEnabled(
                phase = PlayerState.Phase.Paused,
                canPlay = true,
                canPause = false,
            ),
        )
    }
}
