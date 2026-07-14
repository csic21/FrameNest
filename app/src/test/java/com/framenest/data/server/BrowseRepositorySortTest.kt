package com.framenest.data.server

import com.framenest.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseRepositorySortTest {

    @Test
    fun sortRemoteEntries_directoriesFirstThenCaseInsensitiveName() {
        val entries = listOf(
            file("zebra.mkv"),
            dir("Alpha"),
            file("beta.mkv"),
            dir("movies"),
            share("media"),
        )
        val sorted = BrowseRepository.sortRemoteEntries(entries).map { it.name }
        assertEquals(listOf("Alpha", "media", "movies", "beta.mkv", "zebra.mkv"), sorted)
    }

    private fun dir(name: String) = RemoteEntry(
        serverId = "s",
        share = "media",
        path = name,
        name = name,
        isDirectory = true,
    )

    private fun file(name: String) = RemoteEntry(
        serverId = "s",
        share = "media",
        path = name,
        name = name,
        isDirectory = false,
    )

    private fun share(name: String) = RemoteEntry(
        serverId = "s",
        share = name,
        path = "",
        name = name,
        isDirectory = true,
        isShare = true,
    )
}
