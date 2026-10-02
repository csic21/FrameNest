package com.framenest.feature.player

import com.framenest.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMediaFactsTest {
    @Test
    fun remember_keepsAFileListingAndIgnoresADirectory() {
        val file = RemoteEntry(
            serverId = "srv",
            share = "media",
            path = "/films/a.mkv",
            name = "a.mkv",
            isDirectory = false,
            sizeBytes = 80L,
            modifiedTimeMs = 7L,
        )
        PlaybackMediaFacts.remember(file)
        val stamp = PlaybackMediaFacts.lookup("srv", "media", "films/a.mkv")
        assertEquals(80L, stamp?.sizeBytes)
        assertEquals(7L, stamp?.modifiedTimeMs)

        PlaybackMediaFacts.remember(
            file.copy(isDirectory = true, name = "films", path = "films"),
        )
        assertNull(PlaybackMediaFacts.lookup("srv", "media", "films"))
        assertNull(PlaybackMediaFacts.lookup("srv", "media", "missing.mkv"))
    }
}
