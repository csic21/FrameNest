package com.framenest

import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.window.core.layout.WindowSizeClass
import com.framenest.navigation.FrameNestApp
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Rule
import org.junit.Test

/**
 * Adaptive shell UI tests for phone-sized (compact) and tablet-sized (medium+) layouts,
 * plus configuration-change retention of the selected top-level destination.
 */
class AdaptiveShellPhoneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWidth_showsBottomBarAndSinglePaneServers() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = compactAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationBar,
                )
            }
        }

        composeRule.onNodeWithTag("nav_suite_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_servers").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_recent").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_single_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_title").assertIsDisplayed()
    }

    @Test
    fun compact_navigateServerToBrowseToPlayer_andBack() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = compactAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationBar,
                )
            }
        }

        composeRule.onNodeWithTag("server_item_home-nas").performClick()
        composeRule.onNodeWithTag("browse_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("browse_item_movies").performClick()
        composeRule.onNodeWithTag("browse_item_movie-a").performClick()
        composeRule.onNodeWithTag("player_placeholder").assertIsDisplayed()
        composeRule.onNodeWithTag("player_back").performClick()
        composeRule.onNodeWithTag("browse_screen").assertIsDisplayed()
    }

    @Test
    fun topLevelTabs_switchBetweenDestinations() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = compactAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationBar,
                )
            }
        }

        composeRule.onNodeWithTag("nav_recent").performClick()
        composeRule.onNodeWithTag("recent_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("recent_title").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_title").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_servers").performClick()
        composeRule.onNodeWithTag("servers_single_pane").assertIsDisplayed()
    }
}

class AdaptiveShellTabletTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediumWidth_showsRailAndListDetailServers() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = mediumAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationRail,
                )
            }
        }

        composeRule.onNodeWithTag("nav_suite_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_list_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_detail_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("server_item_home-nas").assertIsDisplayed()
    }

    @Test
    fun tablet_openBrowseFromDetailAndPlayerPlaceholder() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = mediumAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationRail,
                )
            }
        }

        composeRule.onNodeWithTag("server_item_media-box").performClick()
        composeRule.onNodeWithTag("servers_open_browse").performClick()
        composeRule.onNodeWithTag("browse_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("browse_back").performClick()
        composeRule.onNodeWithTag("servers_list_detail").assertIsDisplayed()

        composeRule.onNodeWithTag("servers_open_player").performClick()
        composeRule.onNodeWithTag("player_placeholder").assertIsDisplayed()
    }
}

class AdaptiveShellRotationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun recreate_keepsSelectedTopLevelDestination() {
        // Navigate to Settings on the real activity (config change survivor).
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsSelected()
    }
}

private fun compactAdaptiveInfo(): WindowAdaptiveInfo =
    WindowAdaptiveInfo(
        windowSizeClass = WindowSizeClass(minWidthDp = 0, minHeightDp = 0),
        windowPosture = Posture(),
    )

private fun mediumAdaptiveInfo(): WindowAdaptiveInfo =
    WindowAdaptiveInfo(
        windowSizeClass = WindowSizeClass(
            minWidthDp = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
            minHeightDp = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
        ),
        windowPosture = Posture(),
    )
