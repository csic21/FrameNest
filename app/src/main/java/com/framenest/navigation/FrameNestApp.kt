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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.framenest.ContextAppContainer
import com.framenest.app.AppContainer
import com.framenest.core.model.RemoteLocation
import com.framenest.feature.browser.BrowseRoute
import com.framenest.feature.player.PlayerRoute
import com.framenest.feature.servers.ServersRoute
import com.framenest.ui.screens.RecentScreen
import com.framenest.ui.screens.SettingsScreen

/**
 * Adaptive app shell: bottom bar on compact width, navigation rail on larger widths.
 * Player route uses [NavigationSuiteType.None] for immersive full-screen playback.
 * Servers → Browse → Player wired to FN-04 repositories and FN-05 product player.
 */
@Composable
fun FrameNestApp(
    modifier: Modifier = Modifier,
    windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo(),
    navigationSuiteType: NavigationSuiteType =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(windowAdaptiveInfo),
    navController: NavHostController = rememberNavController(),
    appContainer: AppContainer? = null,
) {
    val context = LocalContext.current
    val container = remember(appContainer, context) {
        appContainer ?: ContextAppContainer(context)
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val useListDetail = shouldUseListDetailLayout(windowAdaptiveInfo.windowSizeClass)
    // Full-screen player: hide suite via NavigationSuiteType.None (not a raw width check).
    val effectiveSuiteType = if (isPlayerRoute(currentRoute)) {
        NavigationSuiteType.None
    } else {
        navigationSuiteType
    }

    NavigationSuiteScaffold(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .testTag(navigationSuiteTestTag(effectiveSuiteType)),
        layoutType = effectiveSuiteType,
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
            container = container,
            modifier = Modifier
                .fillMaxSize()
                .testTag("nav_host"),
        )
    }
}

internal fun navigationSuiteTestTag(suiteType: NavigationSuiteType): String =
    when (suiteType) {
        NavigationSuiteType.None -> "nav_suite_none"
        NavigationSuiteType.NavigationRail,
        NavigationSuiteType.NavigationDrawer,
        -> "nav_suite_rail"
        else -> "nav_suite_bar"
    }

@Composable
private fun FrameNestNavHost(
    navController: NavHostController,
    useListDetail: Boolean,
    container: AppContainer,
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
                ServersRoute(
                    useListDetail = useListDetail,
                    serverRepository = container.serverRepository,
                    onOpenBrowse = { serverId, defaultShare ->
                        val location = if (!defaultShare.isNullOrBlank()) {
                            RemoteLocation.shareRoot(defaultShare)
                        } else {
                            RemoteLocation.ROOT
                        }
                        navController.navigate(FrameNestRoutes.browse(serverId, location))
                    },
                )
            }
            composable(
                route = FrameNestRoutes.BROWSE,
                arguments = listOf(
                    navArgument(FrameNestRoutes.ARG_SERVER_ID) { type = NavType.StringType },
                    navArgument(FrameNestRoutes.ARG_SHARE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(FrameNestRoutes.ARG_PATH) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val serverId = entry.arguments?.getString(FrameNestRoutes.ARG_SERVER_ID).orEmpty()
                val share = entry.arguments?.getString(FrameNestRoutes.ARG_SHARE).orEmpty()
                val path = entry.arguments?.getString(FrameNestRoutes.ARG_PATH).orEmpty()
                val location = FrameNestRoutes.locationFromArgs(share, path)
                BrowseRoute(
                    serverId = serverId,
                    location = location,
                    serverRepository = container.serverRepository,
                    browseRepository = container.browseRepository,
                    onBack = { navController.popBackStack() },
                    onOpenDirectory = { next ->
                        navController.navigate(FrameNestRoutes.browse(serverId, next))
                    },
                    onOpenFile = { remote ->
                        navController.navigate(
                            FrameNestRoutes.player(remote.serverId, remote.share, remote.path),
                        )
                    },
                )
            }
        }

        composable(
            route = FrameNestRoutes.PLAYER,
            arguments = listOf(
                navArgument(FrameNestRoutes.ARG_SERVER_ID) { type = NavType.StringType },
                navArgument(FrameNestRoutes.ARG_SHARE) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(FrameNestRoutes.ARG_PATH) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val serverId = entry.arguments?.getString(FrameNestRoutes.ARG_SERVER_ID).orEmpty()
            val share = entry.arguments?.getString(FrameNestRoutes.ARG_SHARE).orEmpty()
            val path = entry.arguments?.getString(FrameNestRoutes.ARG_PATH).orEmpty()
            PlayerRoute(
                serverId = serverId,
                share = share,
                path = path,
                container = container,
                onBack = { navController.popBackStack() },
            )
        }

        composable(FrameNestRoutes.RECENT) {
            RecentScreen(
                onOpenItem = { serverId, share, path ->
                    navController.navigate(FrameNestRoutes.player(serverId, share, path))
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
