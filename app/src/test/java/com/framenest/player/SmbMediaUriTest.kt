package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbMediaUriTest {

    @Test
    fun buildString_omitsCredentialsAndDefaultPort() {
        val text = SmbMediaUri.buildString(
            host = "192.168.1.10",
            share = "media",
            path = "samples/movie.mkv",
        )
        assertEquals("smb://192.168.1.10/media/samples/movie.mkv", text)
        assertFalse(text.contains("@"))
        assertFalse(SmbMediaUri.embedsCredentials(text))
    }

    @Test
    fun buildString_encodesSpacesInPath() {
        val text = SmbMediaUri.buildString(
            host = "nas.local",
            share = "videos",
            path = "My Films/title.mkv",
        )
        assertEquals("smb://nas.local/videos/My%20Films/title.mkv", text)
        assertFalse(text.contains(" "))
    }

    @Test
    fun buildString_includesNonDefaultPort() {
        val text = SmbMediaUri.buildString(
            host = "nas.local",
            share = "media",
            path = "a.mkv",
            port = 4445,
        )
        assertEquals("smb://nas.local:4445/media/a.mkv", text)
    }

    @Test
    fun embedsCredentials_detectsUserinfo() {
        assertTrue(
            SmbMediaUri.embedsCredentials("smb://user:secret@192.168.1.10/share/file.mkv"),
        )
        assertFalse(
            SmbMediaUri.embedsCredentials("smb://192.168.1.10/share/file.mkv"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildString_rejectsHostWithAtSign() {
        SmbMediaUri.buildString(host = "user@host", share = "share")
    }
}
