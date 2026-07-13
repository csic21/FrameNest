package com.framenest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test: launches MainActivity and verifies the adaptive shell is present.
 */
class HomeScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivity_showsAdaptiveShellAndServers() {
        composeRule.onNodeWithTag("nav_servers").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_recent").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_title").assertIsDisplayed()
    }
}
