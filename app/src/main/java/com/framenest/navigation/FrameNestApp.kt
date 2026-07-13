package com.framenest.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.framenest.ui.screens.BrowseScreen
import com.framenest.ui.screens.PlayerPlaceholderScreen
import com.framenest.ui.screens.RecentScreen
import com.framenest.ui.screens.ServersScreen
import com.framenest.ui.screens.SettingsScreen

/**
 * Adaptive app shell: bottom bar on compact width, navigation rail on larger widths,
 * with Servers → Browse → Player placeholder stack and fake data only.
 *
 * @param windowAdaptiveInfo override for tests; defaults to [currentWindowAdaptiveInfo].
 * @param navigationSuiteType override for tests; defaults from adaptive info.
 * @param navController optional hoisted controller (tests / state restore).
 */
@Composable
fun FrameNestApp(
    modifier: Modifier = Modifier,
    windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo(),
    navigationSuiteType: NavigationSuiteType =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(windowAdaptiveInfo),
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val useListDetail = shouldUseListDetailLayout(windowAdaptiveInfo.windowSizeClass)

    NavigationSuiteScaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(
                when (navigationSuiteType) {
                    NavigationSuiteType.NavigationRail,
                    NavigationSuiteType.NavigationDrawer,
                    -> "nav_suite_rail"
                    else -> "nav_suite_bar"
                },
            ),
        layoutType = navigationSuiteType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { dest ->
                val selected = currentDestination.isTopLevelDestinationInHierarchy(dest)
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = stringResource(dest.labelRes),
                        )
                    },
                    label = { Text(stringResource(dest.labelRes)) },
                    modifier = Modifier.testTag(dest.testTag),
                )
            }
        },
    ) {
        FrameNestNavHost(
            navController = navController,
            useListDetail = useListDetail,
            modifier = Modifier
                .fillMaxSize()
                .testTag("nav_host"),
        )
    }
}

@Composable
private fun FrameNestNavHost(
    navController: NavHostController,
    useListDetail: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = FrameNestRoutes.SERVERS_GRAPH,
        modifier = modifier,
    ) {
        navigation(
            route = FrameNestRoutes.SERVERS_GRAPH,
            startDestination = FrameNestRoutes.SERVERS_LIST,
        ) {
            composable(FrameNestRoutes.SERVERS_LIST) {
                ServersScreen(
                    useListDetail = useListDetail,
                    onOpenBrowse = { serverId ->
                        navController.navigate(
                            FrameNestRoutes.browse(serverId, FakeCatalog.ROOT_PATH_ID),
                        )
                    },
                    onOpenPlayer = { serverId, entryId ->
                        navController.navigate(FrameNestRoutes.player(serverId, entryId))
                    },
                )
            }
            composable(
                route = FrameNestRoutes.BROWSE,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
                    navArgument("pathId") { type = NavType.StringType },
                ),
            ) { entry ->
                val serverId = entry.arguments?.getString("serverId").orEmpty()
                val pathId = entry.arguments?.getString("pathId") ?: FakeCatalog.ROOT_PATH_ID
                BrowseScreen(
                    serverId = serverId,
                    pathId = pathId,
                    onBack = { navController.popBackStack() },
                    onOpenDirectory = { nextPathId ->
                        navController.navigate(FrameNestRoutes.browse(serverId, nextPathId))
                    },
                    onOpenFile = { entryId ->
                        navController.navigate(FrameNestRoutes.player(serverId, entryId))
                    },
                )
            }
        }

        composable(
            route = FrameNestRoutes.PLAYER,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("entryId") { type = NavType.StringType },
            ),
        ) { entry ->
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            val entryId = entry.arguments?.getString("entryId").orEmpty()
            PlayerPlaceholderScreen(
                serverId = serverId,
                entryId = entryId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(FrameNestRoutes.RECENT) {
            RecentScreen(
                onOpenItem = { serverId, entryId ->
                    navController.navigate(FrameNestRoutes.player(serverId, entryId))
                },
            )
        }

        composable(FrameNestRoutes.SETTINGS) {
            SettingsScreen()
        }
    }
}

private fun androidx.navigation.NavDestination?.isTopLevelDestinationInHierarchy(
    destination: TopLevelDestination,
): Boolean {
    if (this == null) return false
    return hierarchy.any { dest ->
        val route = dest.route ?: return@any false
        when (destination) {
            TopLevelDestination.Servers ->
                route.startsWith(FrameNestRoutes.SERVERS_GRAPH) || route.startsWith("player/")
            TopLevelDestination.Recent ->
                route == FrameNestRoutes.RECENT || route.startsWith("${FrameNestRoutes.RECENT}/")
            TopLevelDestination.Settings ->
                route == FrameNestRoutes.SETTINGS || route.startsWith("${FrameNestRoutes.SETTINGS}/")
        }
    }
}
