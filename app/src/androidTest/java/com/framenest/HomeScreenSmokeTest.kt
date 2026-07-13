package com.framenest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Compose smoke test: launches MainActivity and verifies the empty home screen.
 */
class HomeScreenSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsAppNameAndPlaceholder() {
        composeRule.onNodeWithText("FrameNest").assertIsDisplayed()
        composeRule.onNodeWithText("栖影 · 工程骨架就绪").assertIsDisplayed()
    }
}
