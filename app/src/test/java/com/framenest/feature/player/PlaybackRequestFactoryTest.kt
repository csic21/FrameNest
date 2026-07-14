package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestFactoryTest {

    @Test
    fun historyEntryRoundTrip_preservesShareAndPath() {
        val encoded = PlaybackRequestFactory.encodeHistoryEntryId("media", "films/a.mkv")
        val request = PlaybackRequestFactory.fromNavArgs("srv-1", encoded)
        assertEquals("srv-1", request.identity.serverId)
        assertEquals("media", request.identity.share)
        assertEquals("films/a.mkv", request.identity.path)
        assertEquals("a.mkv", request.displayName)
    }

    @Test
    fun fakeCatalogEntry_usesPlaceholderPath() {
        val request = PlaybackRequestFactory.fromNavArgs("home-nas", "movie-a")
        assertEquals("home-nas", request.identity.serverId)
        assertEquals(PlaybackRequestFactory.PLACEHOLDER_SHARE, request.identity.share)
        assertTrue(request.identity.path.contains("movie-a"))
    }
}
