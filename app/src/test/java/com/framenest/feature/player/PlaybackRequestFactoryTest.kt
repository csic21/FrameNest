package com.framenest.feature.player

import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestFactoryTest {

    @Test
    fun fromSavedServer_buildsSeekableSmbIdentity() {
        val server = SavedServer(
            id = "srv-1",
            name = "Home",
            host = "192.168.1.10",
            port = 445,
            username = "user",
            domain = null,
            credentialAlias = "alias-1",
            defaultShare = "media",
        )
        val password = "secret".toCharArray()
        val request = PlaybackRequestFactory.fromSavedServer(
            server = server,
            share = "media",
            path = "films/a.mkv",
            password = password,
            startPositionMs = 12_000L,
        )
        assertEquals("srv-1", request.identity.serverId)
        assertEquals("media", request.identity.share)
        assertEquals("films/a.mkv", request.identity.path)
        assertEquals("a.mkv", request.displayName)
        assertEquals(12_000L, request.startPositionMs)
        assertTrue(request.dataSource is PlaybackDataSource.SeekableSmb)
        password.fill('\u0000')
    }

    @Test
    fun localSample_usesRawResource() {
        val request = PlaybackRequestFactory.localSample()
        assertEquals("local", request.identity.serverId)
        assertTrue(request.dataSource is PlaybackDataSource.LocalRawResource)
    }
}
