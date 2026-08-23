package com.framenest.navigation

import com.framenest.core.model.RemoteEntry
import com.framenest.core.model.RemoteLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameNestRoutesTest {

    @Test
    fun browse_root_omitsQuery() {
        assertEquals("servers/browse/abc", FrameNestRoutes.browse("abc"))
    }

    @Test
    fun browse_shareAndPath_encoded() {
        val route = FrameNestRoutes.browse("sid", "media", "Movies/A B.mkv")
        assertTrue(route.startsWith("servers/browse/sid?"))
        assertTrue(route.contains("share=media"))
        assertTrue(route.contains("path="))
        assertFalse(route.contains("password"))
    }

    @Test
    fun player_route_hasShareAndPath() {
        val route = FrameNestRoutes.player("sid", "media", "Movies/x.mkv")
        assertEquals(
            "player/sid?share=media&path=Movies%2Fx.mkv",
            route,
        )
    }

    @Test
    fun locationFromArgs_normalizes() {
        val loc = FrameNestRoutes.locationFromArgs("media", "/Movies/")
        assertEquals(RemoteLocation.of("media", "Movies"), loc)
        assertEquals(RemoteLocation.ROOT, FrameNestRoutes.locationFromArgs("", ""))
    }

    @Test
    fun initialBrowseBackStack_defaultShare_preservesShareListParent() {
        assertEquals(
            listOf(
                "servers/browse/sid",
                "servers/browse/sid?share=Family%20Videos",
            ),
            FrameNestRoutes.initialBrowseBackStack("sid", "  Family Videos  "),
        )
    }

    @Test
    fun initialBrowseBackStack_withoutDefaultShare_opensShareListOnly() {
        assertEquals(
            listOf("servers/browse/sid"),
            FrameNestRoutes.initialBrowseBackStack("sid", null),
        )
        assertEquals(
            listOf("servers/browse/sid"),
            FrameNestRoutes.initialBrowseBackStack("sid", "   "),
        )
    }

    @Test
    fun playerForBrowseEntry_allowsOnlyValidVideoFiles() {
        val video = entry(name = "Episode 1.MKV", path = "Shows/Episode 1.MKV")
        val subtitle = entry(name = "Episode 1.zh.srt", path = "Shows/Episode 1.zh.srt")
        val videoNamedDirectory = entry(
            name = "Extras.mkv",
            path = "Shows/Extras.mkv",
            isDirectory = true,
        )

        assertEquals(
            "player/sid?share=media&path=Shows%2FEpisode%201.MKV",
            FrameNestRoutes.playerForBrowseEntry(video),
        )
        assertNull(FrameNestRoutes.playerForBrowseEntry(subtitle))
        assertNull(FrameNestRoutes.playerForBrowseEntry(videoNamedDirectory))
        assertNull(FrameNestRoutes.playerForBrowseEntry(video.copy(share = "")))
        assertNull(FrameNestRoutes.playerForBrowseEntry(video.copy(path = "")))
    }

    private fun entry(
        name: String,
        path: String,
        isDirectory: Boolean = false,
    ) = RemoteEntry(
        serverId = "sid",
        share = "media",
        path = path,
        name = name,
        isDirectory = isDirectory,
    )
}
