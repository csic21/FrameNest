package com.framenest.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
}
