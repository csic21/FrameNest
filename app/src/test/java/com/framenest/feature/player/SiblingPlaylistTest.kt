package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiblingPlaylistTest {

    @Test
    fun build_filtersVideosAndSortsCaseInsensitive() {
        val playlist = SiblingPlaylistFactory.build(
            currentPath = "Shows/S01/Ep02.mkv",
            directoryFileNames = listOf(
                "Ep10.mkv",
                "readme.txt",
                "Ep02.mkv",
                "ep01.MKV",
                "cover.jpg",
                "Ep02.srt",
            ),
        )
        assertEquals(listOf("ep01.MKV", "Ep02.mkv", "Ep10.mkv"), playlist.videos.map { it.name })
        assertEquals(1, playlist.currentIndex)
        assertEquals("Shows/S01/ep01.MKV", playlist.previous?.path)
        assertEquals("Shows/S01/Ep10.mkv", playlist.next?.path)
        assertEquals("2 / 3", playlist.positionLabel)
    }

    @Test
    fun build_shareRootParent() {
        val playlist = SiblingPlaylistFactory.build(
            currentPath = "movie.mp4",
            directoryFileNames = listOf("a.mp4", "movie.mp4", "z.mp4"),
        )
        assertEquals(1, playlist.currentIndex)
        assertEquals("a.mp4", playlist.previous?.path)
        assertEquals("z.mp4", playlist.next?.path)
    }

    @Test
    fun build_singleVideo_hasNoNeighbors() {
        val playlist = SiblingPlaylistFactory.build(
            currentPath = "only.mp4",
            directoryFileNames = listOf("only.mp4"),
        )
        assertNull(playlist.previous)
        assertNull(playlist.next)
        assertEquals("1 / 1", playlist.positionLabel)
    }

    @Test
    fun build_missingCurrent_stillListsNeighborsAsEmptyNav() {
        val playlist = SiblingPlaylistFactory.build(
            currentPath = "gone.mp4",
            directoryFileNames = listOf("a.mp4", "b.mp4"),
        )
        assertEquals(-1, playlist.currentIndex)
        assertNull(playlist.previous)
        assertNull(playlist.next)
    }

    @Test
    fun uiState_fromPlaylist() {
        val playlist = SiblingPlaylistFactory.build(
            currentPath = "dir/b.mp4",
            directoryFileNames = listOf("a.mp4", "b.mp4", "c.mp4"),
        )
        val ui = SiblingNavUiState.from(playlist)
        assertTrue(ui.hasPrevious)
        assertTrue(ui.hasNext)
        assertEquals("dir/a.mp4", ui.previousPath)
        assertEquals("dir/c.mp4", ui.nextPath)
        assertEquals("2 / 3", ui.positionLabel)
        assertFalse(ui.loading)
    }
}
