package com.framenest.navigation

/**
 * Placeholder recent-history data until FN-05.
 * Servers / browse use real repositories from FN-04.
 */
data class FakeRecentItem(
    val id: String,
    val title: String,
    val serverId: String,
    val serverName: String,
    val share: String,
    val path: String,
    /** 0f..1f playback progress fraction for display only. */
    val progressFraction: Float,
)

object FakeCatalog {
    val recent: List<FakeRecentItem> = listOf(
        FakeRecentItem(
            id = "rec-1",
            title = "Sample Movie.mkv",
            serverId = "home-nas",
            serverName = "Home NAS",
            share = "media",
            path = "Movies/Sample Movie.mkv",
            progressFraction = 0.42f,
        ),
        FakeRecentItem(
            id = "rec-2",
            title = "Episode 03.mp4",
            serverId = "media-box",
            serverName = "Media Box",
            share = "shows",
            path = "S01/Episode 03.mp4",
            progressFraction = 0.78f,
        ),
        FakeRecentItem(
            id = "rec-3",
            title = "Documentary.webm",
            serverId = "home-nas",
            serverName = "Home NAS",
            share = "media",
            path = "Docs/Documentary.webm",
            progressFraction = 0.05f,
        ),
    )
}
