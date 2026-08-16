package com.framenest.feature.player

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke: spike activity shows player UI for the bundled sample and loads without crash.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerSpikeLocalPlaybackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<PlayerSpikeActivity>()

    @Test
    fun spikeScreen_isDisplayed() {
        composeRule.onNodeWithContentDescription("player_spike_screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("player_video_surface").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("player_status").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("player_play").assertExists()
        // Allow prepare/first-frame path a moment; must not crash.
        composeRule.waitForIdle()
        Thread.sleep(2_000)
        composeRule.onNodeWithContentDescription("player_spike_screen").assertIsDisplayed()
    }

    @Test
    fun pausedContinuousSeek_settlesOnTargetWithoutEscapingIntoPlayback() {
        assumeFalse(
            "Bundled libVLC sample is not decodable on the project AVDs",
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true),
        )
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            runCatching {
                val config = composeRule
                    .onNodeWithContentDescription("player_play")
                    .fetchSemanticsNode()
                    .config
                !config.contains(SemanticsProperties.Disabled)
            }.getOrDefault(false)
        }

        val slider = composeRule.onNodeWithContentDescription("player_seek")
        slider.performTouchInput {
            swipe(
                start = Offset(width * 0.25f, height * 0.5f),
                end = Offset(width * 0.75f, height * 0.5f),
                durationMillis = 400L,
            )
        }

        // Paused seek preview is bounded at 750ms; leave margin for a slow decoder.
        Thread.sleep(1_500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("player_play").assertIsEnabled()
        composeRule.onNodeWithContentDescription("player_pause").assertIsNotEnabled()

        val settledProgress = sliderProgress()
        Thread.sleep(1_000L)
        composeRule.waitForIdle()
        val laterProgress = sliderProgress()

        assertTrue(
            "paused seek kept advancing: settled=$settledProgress later=$laterProgress",
            abs(laterProgress - settledProgress) < 0.04f,
        )
    }

    private fun sliderProgress(): Float = composeRule
        .onNodeWithContentDescription("player_seek")
        .fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo]
        .current
}
