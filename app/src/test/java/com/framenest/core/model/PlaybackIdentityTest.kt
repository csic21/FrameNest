package com.framenest.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackIdentityTest {

    @Test
    fun normalizedPath_trimsSlashesAndBackslashes() {
        val id = PlaybackIdentity("s", "media", "\\foo\\bar.mkv")
        assertEquals("foo/bar.mkv", id.normalizedPath())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankServerId() {
        PlaybackIdentity("", "media", "a.mp4")
    }
}
