package com.framenest.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.framenest.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerViewportTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun changingOrHidingChrome_keepsSurfaceBoundsStable() {
        val chromeVisible = mutableStateOf(true)
        val tallBottomChrome = mutableStateOf(false)

        composeRule.setContent {
            PlayerViewport(
                modifier = Modifier.size(width = 360.dp, height = 640.dp),
                surface = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                },
                topChrome = if (chromeVisible.value) {
                    {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                        )
                    }
                } else {
                    null
                },
                bottomChrome = if (chromeVisible.value) {
                    {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (tallBottomChrome.value) 260.dp else 120.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }

        val initialBounds = surfaceBounds()

        tallBottomChrome.value = true
        composeRule.waitForIdle()
        assertEquals(initialBounds, surfaceBounds())

        chromeVisible.value = false
        composeRule.waitForIdle()
        assertEquals(initialBounds, surfaceBounds())
    }

    @Test
    fun enteringBuffering_keepsControlsBoundsStable() {
        val buffering = mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                PlayerControls(
                    state = PlayerState(
                        phase = PlayerState.Phase.Playing,
                        positionMs = 30_000L,
                        durationMs = 120_000L,
                        isSeekable = true,
                        firstFrameReady = true,
                        isBuffering = buffering.value,
                        bufferPercent = if (buffering.value) 42f else 100f,
                    ),
                    siblingNav = SiblingNavUiState(),
                    autoNextArmed = false,
                    onPlay = {},
                    onPause = {},
                    onSeek = {},
                    onCycleVideoScale = {},
                    onCyclePlaybackRate = {},
                    onLockControls = {},
                    orientationLocked = false,
                    onToggleOrientationLock = {},
                    onPrevious = null,
                    onNext = null,
                    overlay = true,
                    onUserInteraction = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        val initialBounds = controlsBounds()

        buffering.value = true
        composeRule.waitForIdle()
        assertEquals(initialBounds, controlsBounds())
    }

    private fun surfaceBounds() = composeRule
        .onNodeWithTag(PLAYER_VIEWPORT_SURFACE_HOST_TAG)
        .getUnclippedBoundsInRoot()

    private fun controlsBounds() = composeRule
        .onNodeWithTag("player_controls")
        .getUnclippedBoundsInRoot()
}
