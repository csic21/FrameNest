package com.framenest.feature.player

import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.framenest.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerSurfaceInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun surfaceSemanticsClick_togglesChromeWhilePlaying() {
        var chromeToggles = 0
        setSurface(
            playing = true,
            onToggleChrome = { chromeToggles++ },
        )

        composeRule.onNodeWithTag("player_playing_touch")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle { assertEquals(1, chromeToggles) }
    }

    @Test
    fun surfaceSemanticsClick_playsWhilePaused() {
        var playRequests = 0
        setSurface(
            playing = false,
            onPlay = { playRequests++ },
        )

        composeRule.onNodeWithTag("player_playing_touch")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle { assertEquals(1, playRequests) }
    }

    @Test
    fun cancelledHorizontalGesture_clearsSeekingWithoutCommit() {
        val seekingEvents = mutableListOf<Boolean>()
        val previewTargets = mutableListOf<Long>()
        val committedTargets = mutableListOf<Long>()
        setSurface(
            playing = true,
            onPreviewSeek = { previewTargets += it },
            onCommitSeek = { committedTargets += it },
            onSeekGesture = { seekingEvents += it != null },
        )

        composeRule.onNodeWithTag("player_playing_touch").performTouchInput {
            down(center)
            moveTo(center + Offset(180f, 0f))
            cancel()
        }

        composeRule.runOnIdle {
            assertTrue(previewTargets.isNotEmpty())
            assertTrue(seekingEvents.contains(true))
            assertFalse(seekingEvents.last())
            assertTrue(committedTargets.isEmpty())
        }
    }

    @Test
    fun cancelledTimelineDrag_restoresScrubbingWithoutFinalSeek() {
        val seekingEvents = mutableListOf<Boolean>()
        val previewTargets = mutableListOf<Long>()
        val committedTargets = mutableListOf<Long>()
        composeRule.setContent {
            MaterialTheme {
                PlayerControls(
                    state = PlayerState(
                        phase = PlayerState.Phase.Paused,
                        positionMs = 30_000L,
                        durationMs = 120_000L,
                        isSeekable = true,
                        firstFrameReady = true,
                    ),
                    siblingNav = SiblingNavUiState(),
                    autoNextArmed = false,
                    onPlay = {},
                    onPause = {},
                    onPreviewSeek = { previewTargets += it },
                    onSeek = { committedTargets += it },
                    onCycleVideoScale = {},
                    onCyclePlaybackRate = {},
                    onLockControls = {},
                    orientationLocked = false,
                    onToggleOrientationLock = {},
                    onPrevious = null,
                    onNext = null,
                    overlay = true,
                    onUserInteraction = {},
                    onUserSeeking = { seekingEvents += it },
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        composeRule.onNodeWithTag("player_seek").performTouchInput {
            down(center)
            moveTo(center + Offset(120f, 0f))
            cancel()
        }

        composeRule.runOnIdle {
            assertTrue(previewTargets.isNotEmpty())
            assertTrue(seekingEvents.contains(true))
            assertFalse(seekingEvents.last())
            assertTrue(committedTargets.isEmpty())
        }
    }

    @Test
    fun timelineDrag_showsPreviewImageAboveTheSlider() {
        composeRule.setContent {
            MaterialTheme {
                PlayerControls(
                    state = PlayerState(
                        phase = PlayerState.Phase.Playing,
                        positionMs = 20_000L,
                        durationMs = 120_000L,
                        isSeekable = true,
                        firstFrameReady = true,
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

        assertFalse(previewVisible())
        composeRule.onNodeWithTag("player_seek").performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
        }
        assertTrue(previewVisible())
        composeRule.onNodeWithTag("player_seek").performTouchInput { up() }
        composeRule.waitForIdle()
        assertFalse(previewVisible())
    }

    private fun previewVisible(): Boolean =
        composeRule.onAllNodesWithTag("player_scrub_preview").fetchSemanticsNodes().isNotEmpty()

    private fun setSurface(
        playing: Boolean,
        onToggleChrome: () -> Unit = {},
        onPlay: () -> Unit = {},
        onPreviewSeek: (Long) -> Unit = {},
        onCommitSeek: (Long) -> Unit = {},
        onSeekGesture: (SeekGestureUi?) -> Unit = {},
    ) {
        composeRule.setContent {
            PlayerGestureLayer(
                surfaceCd = "Video surface",
                toggleCd = "Toggle controls",
                playing = playing,
                positionMs = 30_000L,
                durationMs = 120_000L,
                onToggleChrome = onToggleChrome,
                onPlay = onPlay,
                onSkipBack = {},
                onSkipForward = {},
                onPreviewSeek = onPreviewSeek,
                onCommitSeek = onCommitSeek,
                onSeekGesture = onSeekGesture,
                onGestureStart = {},
                onGestureDrag = { _, _, _ -> },
                onGestureEnd = {},
            )
        }
    }
}
