package com.framenest.data.thumbnail

import com.framenest.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailRequestTest {

    @Test
    fun fromVideoEntry_acceptsVideoFiles() {
        val entry = RemoteEntry(
            serverId = "s1",
            share = "media",
            path = "films/a.mkv",
            name = "a.mkv",
            isDirectory = false,
            sizeBytes = 99L,
            modifiedTimeMs = 42L,
        )
        val request = ThumbnailRequest.fromVideoEntry(entry)
        assertNotNull(request)
        assertEquals("s1", request!!.key.serverId)
        assertEquals("media", request.key.share)
        assertEquals("films/a.mkv", request.key.path)
        assertEquals(99L, request.key.sizeBytes)
        assertEquals(42L, request.key.modifiedTimeMs)
    }

    @Test
    fun fromVideoEntry_rejectsDirectoriesAndSubtitles() {
        val dir = RemoteEntry(
            serverId = "s1",
            share = "media",
            path = "films",
            name = "films",
            isDirectory = true,
        )
        val sub = RemoteEntry(
            serverId = "s1",
            share = "media",
            path = "films/a.srt",
            name = "a.srt",
            isDirectory = false,
        )
        val share = RemoteEntry(
            serverId = "s1",
            share = "media",
            path = "",
            name = "media",
            isDirectory = true,
            isShare = true,
        )
        assertNull(ThumbnailRequest.fromVideoEntry(dir))
        assertNull(ThumbnailRequest.fromVideoEntry(sub))
        assertNull(ThumbnailRequest.fromVideoEntry(share))
    }
}
