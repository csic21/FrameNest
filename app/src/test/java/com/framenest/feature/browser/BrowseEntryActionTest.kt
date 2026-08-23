package com.framenest.feature.browser

import com.framenest.core.model.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseEntryActionTest {

    @Test
    fun share_directory_andVideo_areActionable() {
        val share = entry(
            share = "media",
            path = "",
            name = "media",
            isDirectory = true,
            isShare = true,
        )
        val directory = entry(path = "Shows", name = "Shows", isDirectory = true)
        val video = entry(path = "Shows/Episode.MkV", name = "Episode.MkV")

        assertEquals(BrowseEntryAction.OPEN_SHARE, browseEntryAction(share))
        assertEquals(BrowseEntryAction.OPEN_DIRECTORY, browseEntryAction(directory))
        assertEquals(BrowseEntryAction.OPEN_VIDEO, browseEntryAction(video))
        assertTrue(isBrowseEntryActionable(share))
        assertTrue(isBrowseEntryActionable(directory))
        assertTrue(isBrowseEntryActionable(video))
    }

    @Test
    fun subtitle_andUnknownFile_remainVisibleButHaveNoBrowseAction() {
        val subtitle = entry(path = "Shows/Episode.zh.srt", name = "Episode.zh.srt")
        val unknown = entry(path = "Shows/poster.jpg", name = "poster.jpg")

        assertEquals(BrowseEntryAction.NONE, browseEntryAction(subtitle))
        assertEquals(BrowseEntryAction.NONE, browseEntryAction(unknown))
        assertFalse(isBrowseEntryActionable(subtitle))
        assertFalse(isBrowseEntryActionable(unknown))
    }

    private fun entry(
        share: String = "media",
        path: String,
        name: String,
        isDirectory: Boolean = false,
        isShare: Boolean = false,
    ) = RemoteEntry(
        serverId = "server-1",
        share = share,
        path = path,
        name = name,
        isDirectory = isDirectory,
        isShare = isShare,
    )
}
