package com.framenest.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPathUtilsTest {

    @Test
    fun normalizeRelative_stripsSlashes() {
        assertEquals("", SmbPathUtils.normalizeRelative("/"))
        assertEquals("", SmbPathUtils.normalizeRelative(""))
        assertEquals("Movies/A", SmbPathUtils.normalizeRelative("/Movies/A/"))
        assertEquals("Movies/A", SmbPathUtils.normalizeRelative("\\Movies\\A\\"))
    }

    @Test
    fun join_buildsRelativePath() {
        assertEquals("Movies", SmbPathUtils.join("", "Movies"))
        assertEquals("Movies/x.mkv", SmbPathUtils.join("Movies", "x.mkv"))
        assertEquals("a/b", SmbPathUtils.join("/a/", "/b/"))
    }

    @Test
    fun parentOf_returnsParent() {
        assertEquals("", SmbPathUtils.parentOf(""))
        assertEquals("", SmbPathUtils.parentOf("Movies"))
        assertEquals("Movies", SmbPathUtils.parentOf("Movies/A"))
        assertEquals("Movies/A", SmbPathUtils.parentOf("Movies/A/b.mkv"))
    }

    @Test
    fun sortEntries_directoriesFirstThenCaseInsensitiveName() {
        val entries = listOf(
            entry("zebra.mkv", isDir = false),
            entry("Alpha", isDir = true),
            entry("beta.mkv", isDir = false),
            entry("movies", isDir = true),
        )
        val sorted = SmbPathUtils.sortEntries(entries).map { it.name }
        assertEquals(listOf("Alpha", "movies", "beta.mkv", "zebra.mkv"), sorted)
    }

    @Test
    fun isDotEntry() {
        assertTrue(SmbPathUtils.isDotEntry("."))
        assertTrue(SmbPathUtils.isDotEntry(".."))
    }

    private fun entry(name: String, isDir: Boolean) = SmbEntry(
        name = name,
        path = name,
        isDirectory = isDir,
        sizeBytes = 0,
        lastModifiedEpochMs = 0,
    )
}
