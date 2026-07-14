package com.framenest.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowSizeClass
import com.framenest.R
import com.framenest.core.model.RemoteLocation
import com.framenest.smb.SmbPathUtils
import java.net.URLEncoder

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

/**
 * Navigation routes for FrameNest.
 *
 * **Browse** (FN-04):
 * ```
 * servers/browse/{serverId}?share={share}&path={path}
 * ```
 * - empty share → share list
 * - share set, empty path → share root
 * - both set → directory under share
 *
 * **Player** (stable for FN-05):
 * ```
 * player/{serverId}?share={share}&path={path}
 * ```
 * - [share] required for real playback
 * - [path] share-relative file path (no leading slash)
 * - Never put passwords in routes
 */
object FrameNestRoutes {
    const val SERVERS_GRAPH = "servers"
    const val SERVERS_LIST = "servers/list"
    const val BROWSE = "servers/browse/{serverId}?share={share}&path={path}"
    const val PLAYER = "player/{serverId}?share={share}&path={path}"
    const val RECENT = "recent"
    const val SETTINGS = "settings"

    const val ARG_SERVER_ID = "serverId"
    const val ARG_SHARE = "share"
    const val ARG_PATH = "path"

    fun browse(
        serverId: String,
        share: String = "",
        path: String = "",
    ): String {
        val base = "servers/browse/$serverId"
        val params = buildList {
            if (share.isNotEmpty()) add("share=${encodeQuery(share)}")
            val normalized = SmbPathUtils.normalizeRelative(path)
            if (normalized.isNotEmpty()) add("path=${encodeQuery(normalized)}")
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun browse(serverId: String, location: RemoteLocation): String =
        browse(serverId, location.share, location.normalizedPath)

    /**
     * Player route for FN-05.
     * @param share SMB share name (never empty for real media)
     * @param path share-relative file path
     */
    fun player(serverId: String, share: String, path: String): String {
        val normalized = SmbPathUtils.normalizeRelative(path)
        return "player/$serverId?share=${encodeQuery(share)}&path=${encodeQuery(normalized)}"
    }

    fun locationFromArgs(share: String?, path: String?): RemoteLocation =
        RemoteLocation.of(share.orEmpty(), path.orEmpty())

    /** Query encoding that works on JVM unit tests (no android.net.Uri). */
    internal fun encodeQuery(value: String): String =
        // Use String charset name for minSdk 26 (Charset overload is API 33+).
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
}

/**
 * Whether list-detail side-by-side layout should be used for the Servers feature.
 * Uses [WindowSizeClass] width breakpoints — never raw device model or hardcoded UI width checks.
 */
fun shouldUseListDetailLayout(windowSizeClass: WindowSizeClass): Boolean =
    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * True when the current back-stack entry is the product player (full-screen chrome).
 * Player uses [NavigationSuiteType.None] so phone bottom bar / tablet rail do not steal space.
 */
fun isPlayerRoute(route: String?): Boolean =
    route != null && (route == "player" || route.startsWith("player/"))

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
            isPlayerRoute(route) ->
            TopLevelDestination.Servers
        else -> null
    }
}
