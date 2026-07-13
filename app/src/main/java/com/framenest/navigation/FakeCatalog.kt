package com.framenest.navigation

/**
 * Placeholder domain models and catalog for FN-03 shell only.
 * No SMB / Room / player I/O — replaced by real feature modules later.
 */
data class FakeServer(
    val id: String,
    val name: String,
    val host: String,
    val shareCount: Int,
)

data class FakeBrowseEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
)

data class FakeRecentItem(
    val id: String,
    val title: String,
    val serverId: String,
    val serverName: String,
    /** 0f..1f playback progress fraction for display only. */
    val progressFraction: Float,
)

object FakeCatalog {
    val servers: List<FakeServer> = listOf(
        FakeServer(id = "home-nas", name = "Home NAS", host = "192.168.1.10", shareCount = 3),
        FakeServer(id = "media-box", name = "Media Box", host = "nas.local", shareCount = 2),
        FakeServer(id = "backup", name = "Backup Share", host = "10.0.0.5", shareCount = 1),
    )

    val recent: List<FakeRecentItem> = listOf(
        FakeRecentItem(
            id = "rec-1",
            title = "Sample Movie.mkv",
            serverId = "home-nas",
            serverName = "Home NAS",
            progressFraction = 0.42f,
        ),
        FakeRecentItem(
            id = "rec-2",
            title = "Episode 03.mp4",
            serverId = "media-box",
            serverName = "Media Box",
            progressFraction = 0.78f,
        ),
        FakeRecentItem(
            id = "rec-3",
            title = "Documentary.webm",
            serverId = "home-nas",
            serverName = "Home NAS",
            progressFraction = 0.05f,
        ),
    )

    fun server(id: String): FakeServer? = servers.find { it.id == id }

    @Suppress("UNUSED_PARAMETER")
    fun browseEntries(serverId: String, pathId: String = ROOT_PATH_ID): List<FakeBrowseEntry> {
        // Same tree for every server — enough for navigation shell demos.
        return when (pathId) {
            ROOT_PATH_ID -> listOf(
                FakeBrowseEntry(id = "movies", name = "Movies", isDirectory = true),
                FakeBrowseEntry(id = "shows", name = "TV Shows", isDirectory = true),
                FakeBrowseEntry(id = "readme", name = "readme.txt", isDirectory = false),
            )
            "movies" -> listOf(
                FakeBrowseEntry(id = "movie-a", name = "Sample Movie.mkv", isDirectory = false),
                FakeBrowseEntry(id = "movie-b", name = "Another Film.mp4", isDirectory = false),
            )
            "shows" -> listOf(
                FakeBrowseEntry(id = "ep-1", name = "Episode 01.mp4", isDirectory = false),
                FakeBrowseEntry(id = "ep-2", name = "Episode 02.mp4", isDirectory = false),
            )
            else -> emptyList()
        }
    }

    fun entryTitle(entryId: String): String {
        return when (entryId) {
            "movie-a" -> "Sample Movie.mkv"
            "movie-b" -> "Another Film.mp4"
            "ep-1" -> "Episode 01.mp4"
            "ep-2" -> "Episode 02.mp4"
            "readme" -> "readme.txt"
            "movies" -> "Movies"
            "shows" -> "TV Shows"
            else -> entryId
        }
    }

    const val ROOT_PATH_ID: String = "root"
}
