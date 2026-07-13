package com.framenest.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowSizeClass
import com.framenest.R

/**
 * Top-level destinations shown in the adaptive navigation suite
 * (bottom bar on compact width, rail on larger widths).
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val testTag: String,
) {
    Servers(
        route = FrameNestRoutes.SERVERS_GRAPH,
        labelRes = R.string.nav_servers,
        icon = Icons.Filled.Storage,
        testTag = "nav_servers",
    ),
    Recent(
        route = FrameNestRoutes.RECENT,
        labelRes = R.string.nav_recent,
        icon = Icons.Filled.History,
        testTag = "nav_recent",
    ),
    Settings(
        route = FrameNestRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        icon = Icons.Filled.Settings,
        testTag = "nav_settings",
    ),
}

object FrameNestRoutes {
    const val SERVERS_GRAPH = "servers"
    const val SERVERS_LIST = "servers/list"
    const val BROWSE = "servers/browse/{serverId}/{pathId}"
    const val PLAYER = "player/{serverId}/{entryId}"
    const val RECENT = "recent"
    const val SETTINGS = "settings"

    fun browse(serverId: String, pathId: String = FakeCatalog.ROOT_PATH_ID): String =
        "servers/browse/$serverId/$pathId"

    fun player(serverId: String, entryId: String): String =
        "player/$serverId/$entryId"
}

/**
 * Whether list-detail side-by-side layout should be used for the Servers feature.
 * Uses [WindowSizeClass] width breakpoints — never raw device model or hardcoded UI width checks.
 */
fun shouldUseListDetailLayout(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * Maps a Nav back-stack route to the matching top-level destination, if any.
 */
fun topLevelDestinationForRoute(route: String?): TopLevelDestination? {
    if (route == null) return null
    return when {
        route == FrameNestRoutes.RECENT || route.startsWith("${FrameNestRoutes.RECENT}/") ->
            TopLevelDestination.Recent
        route == FrameNestRoutes.SETTINGS || route.startsWith("${FrameNestRoutes.SETTINGS}/") ->
            TopLevelDestination.Settings
        route.startsWith(FrameNestRoutes.SERVERS_GRAPH) ||
            route.startsWith("player/") ->
            TopLevelDestination.Servers
        else -> null
    }
}
