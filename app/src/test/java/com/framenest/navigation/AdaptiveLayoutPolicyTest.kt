package com.framenest.navigation

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
    fun topLevelDestination_mapsNestedAndPlayerRoutes() {
        assertEquals(
            TopLevelDestination.Servers,
            topLevelDestinationForRoute(FrameNestRoutes.SERVERS_LIST),
        )
        assertEquals(
            TopLevelDestination.Servers,
            topLevelDestinationForRoute(FrameNestRoutes.browse("home-nas", "movies")),
        )
        assertEquals(
            TopLevelDestination.Servers,
            topLevelDestinationForRoute(FrameNestRoutes.player("home-nas", "movie-a")),
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
    fun fakeCatalog_hasServersAndBrowseTree() {
        assertTrue(FakeCatalog.servers.isNotEmpty())
        assertTrue(FakeCatalog.recent.isNotEmpty())
        val root = FakeCatalog.browseEntries("home-nas", FakeCatalog.ROOT_PATH_ID)
        assertTrue(root.any { it.isDirectory })
        val movies = FakeCatalog.browseEntries("home-nas", "movies")
        assertTrue(movies.any { !it.isDirectory })
    }

    @Test
    fun routeBuilders_useExpectedPatterns() {
        assertEquals(
            "servers/browse/home-nas/root",
            FrameNestRoutes.browse("home-nas"),
        )
        assertEquals(
            "player/home-nas/movie-a",
            FrameNestRoutes.player("home-nas", "movie-a"),
        )
    }
}
