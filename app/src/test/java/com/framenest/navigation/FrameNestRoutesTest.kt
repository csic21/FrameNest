package com.framenest.navigation

import com.framenest.core.model.RemoteLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
