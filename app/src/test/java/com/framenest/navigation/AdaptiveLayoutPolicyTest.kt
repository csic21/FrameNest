package com.framenest.navigation

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic for adaptive list-detail and top-level route mapping.
 * Uses [WindowSizeClass] breakpoints — not raw pixel device checks.
 */
class AdaptiveLayoutPolicyTest {

    @Test
    fun listDetail_false_forCompactWidthBucket() {
        // Compact width bucket lower bound is 0 dp.
        val compact = WindowSizeClass(minWidthDp = 0, minHeightDp = 0)
        assertFalse(shouldUseListDetailLayout(compact))
    }

    @Test
    fun listDetail_true_forMediumWidthBucket() {
        val medium = WindowSizeClass(
            minWidthDp = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
            minHeightDp = 0,
        )
        assertTrue(shouldUseListDetailLayout(medium))
    }

    @Test
    fun listDetail_true_forExpandedWidthBucket() {
        val expanded = WindowSizeClass(
            minWidthDp = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
            minHeightDp = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
        )
        assertTrue(shouldUseListDetailLayout(expanded))
    }

    @Test
    fun isPlayerRoute_detectsPlayerAndNotBrowse() {
        assertTrue(isPlayerRoute("player/{serverId}?share={share}&path={path}"))
        assertTrue(isPlayerRoute(FrameNestRoutes.player("s1", "media", "a.mkv")))
        assertFalse(isPlayerRoute(FrameNestRoutes.SERVERS_LIST))
        assertFalse(isPlayerRoute(FrameNestRoutes.browse("s1", "media", "dir")))
        assertFalse(isPlayerRoute(null))
        assertFalse(isPlayerRoute("recent"))
    }

    @Test
    fun navigationSuiteTestTag_coversBarRailNone() {
        assertEquals("nav_suite_bar", navigationSuiteTestTag(NavigationSuiteType.NavigationBar))
        assertEquals("nav_suite_rail", navigationSuiteTestTag(NavigationSuiteType.NavigationRail))
        assertEquals("nav_suite_none", navigationSuiteTestTag(NavigationSuiteType.None))
    }

    @Test
    fun topLevelDestination_mapsNestedRoutes_playerIsNotATab() {
        assertEquals(
            TopLevelDestination.Servers,
            topLevelDestinationForRoute(FrameNestRoutes.SERVERS_LIST),
        )
        assertEquals(
            TopLevelDestination.Servers,
            topLevelDestinationForRoute(FrameNestRoutes.browse("home-nas", "media", "movies")),
        )
        // Player is full-screen; tab highlight is owned by last non-player tab (KI-05).
        assertEquals(
            null,
            topLevelDestinationForRoute(
                FrameNestRoutes.player("home-nas", "media", "Movies/a.mkv"),
            ),
        )
        assertEquals(
            TopLevelDestination.Recent,
            topLevelDestinationForRoute(FrameNestRoutes.RECENT),
        )
        assertEquals(
            TopLevelDestination.Settings,
            topLevelDestinationForRoute(FrameNestRoutes.SETTINGS),
        )
        assertEquals(null, topLevelDestinationForRoute(null))
        assertEquals(null, topLevelDestinationForRoute("unknown"))
    }

    @Test
    fun routeBuilders_useShareAndPathQuery() {
        assertEquals(
            "servers/browse/home-nas",
            FrameNestRoutes.browse("home-nas"),
        )
        assertEquals(
            "servers/browse/home-nas?share=media",
            FrameNestRoutes.browse("home-nas", "media"),
        )
        assertEquals(
            "player/home-nas?share=media&path=Movies%2Fa.mkv",
            FrameNestRoutes.player("home-nas", "media", "Movies/a.mkv"),
        )
    }
}
