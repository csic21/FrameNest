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
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import com.framenest.navigation.FrameNestApp
import com.framenest.navigation.FrameNestRoutes
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Rule
import org.junit.Test

/**
 * Adaptive shell UI tests for phone (compact) and tablet (medium+) configurations.
 * Real server list is empty until the user adds a NAS — empty states must still be correct.
 */
class AdaptiveShellPhoneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWidth_showsBottomBarAndUnifiedServerEmptyState() {
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
        composeRule.onNodeWithTag("servers_empty_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_title").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty_scan").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty_add").assertIsDisplayed()
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
        composeRule.onNodeWithTag("recent_empty").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_title").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_servers").performClick()
        composeRule.onNodeWithTag("servers_empty_pane").assertIsDisplayed()
    }

    @Test
    fun compact_openAddServerDialog() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = compactAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationBar,
                )
            }
        }

        composeRule.onNodeWithTag("servers_empty_add").performClick()
        composeRule.onNodeWithTag("server_field_name").assertIsDisplayed()
        composeRule.onNodeWithTag("server_field_host").assertIsDisplayed()
        composeRule.onNodeWithTag("server_test").assertIsDisplayed()
        composeRule.onNodeWithTag("server_save").assertIsDisplayed()
    }

    @Test
    fun compact_playerRoute_hidesNavigationSuite() {
        composeRule.setContent {
            FrameNestTheme {
                val nav = rememberNavController()
                FrameNestApp(
                    windowAdaptiveInfo = compactAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationBar,
                    navController = nav,
                )
                // Navigate after composition so the shell can observe the player route.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    nav.navigate(FrameNestRoutes.player("missing", "share", "file.mkv"))
                }
            }
        }

        // Missing server → player route error (still player destination → suite None).
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_route_error"))
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_route_loading"))
                    .fetchSemanticsNodes()
                    .isNotEmpty() ||
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("player_screen"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        composeRule.onNodeWithTag("nav_suite_none").assertIsDisplayed()
    }
}

class AdaptiveShellTabletTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediumWidth_showsRailAndOneUnifiedServerEmptyState() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = mediumAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationRail,
                )
            }
        }

        composeRule.onNodeWithTag("nav_suite_rail").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty_scan").assertIsDisplayed()
        composeRule.onNodeWithTag("servers_empty_add").assertIsDisplayed()
    }

    @Test
    fun mediumWidth_recentAndSettingsEmptyStates() {
        composeRule.setContent {
            FrameNestTheme {
                FrameNestApp(
                    windowAdaptiveInfo = mediumAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationRail,
                )
            }
        }

        composeRule.onNodeWithTag("nav_recent").performClick()
        composeRule.onNodeWithTag("recent_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("recent_empty").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_title").assertIsDisplayed()
    }

    @Test
    fun medium_playerRoute_hidesNavigationRail() {
        composeRule.setContent {
            FrameNestTheme {
                val nav = rememberNavController()
                FrameNestApp(
                    windowAdaptiveInfo = mediumAdaptiveInfo(),
                    navigationSuiteType = NavigationSuiteType.NavigationRail,
                    navController = nav,
                )
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    nav.navigate(FrameNestRoutes.player("missing", "share", "file.mkv"))
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("nav_suite_none"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("nav_suite_none").assertIsDisplayed()
    }
}

class AdaptiveShellRotationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun recreate_keepsSelectedTopLevelDestination() {
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").assertIsSelected()
    }

    @Test
    fun recreate_keepsRecentDestination() {
        composeRule.onNodeWithTag("nav_recent").performClick()
        composeRule.onNodeWithTag("recent_screen").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithTag("recent_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_recent").assertIsSelected()
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
