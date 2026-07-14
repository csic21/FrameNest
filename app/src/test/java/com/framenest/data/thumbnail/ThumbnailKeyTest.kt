package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailKeyTest {

    @Test
    fun digest_isStableForSameInputs() {
        val a = ThumbnailKey("srv-1", "media", "films/a.mkv", 1_024L, 1_700_000_000_000L)
        val b = ThumbnailKey("srv-1", "media", "films/a.mkv", 1_024L, 1_700_000_000_000L)
        assertEquals(a.digest(), b.digest())
        assertEquals(a.canonicalString(), b.canonicalString())
    }

    @Test
    fun digest_changesWhenSizeOrMtimeChanges() {
        val base = ThumbnailKey("srv-1", "media", "films/a.mkv", 1_024L, 100L)
        val sizeChanged = base.copy(sizeBytes = 2_048L)
        val mtimeChanged = base.copy(modifiedTimeMs = 200L)
        assertNotEquals(base.digest(), sizeChanged.digest())
        assertNotEquals(base.digest(), mtimeChanged.digest())
    }

    @Test
    fun digest_normalizesPathSeparatorsAndSlashes() {
        val slash = ThumbnailKey("s", "share", "dir/video.mp4", 10L, 1L)
        val backslash = ThumbnailKey("s", "share", "dir\\video.mp4", 10L, 1L)
        val leading = ThumbnailKey("s", "share", "/dir/video.mp4", 10L, 1L)
        assertEquals(slash.digest(), backslash.digest())
        assertEquals(slash.digest(), leading.digest())
    }

    @Test
    fun digest_isHexOfExpectedLength() {
        val key = ThumbnailKey("s", "share", "a.mp4", 1L, 1L)
        val d = key.digest()
        assertEquals(ThumbnailKey.DIGEST_HEX_CHARS, d.length)
        assertTrue(d.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun differentServerOrShare_differentDigest() {
        val a = ThumbnailKey("s1", "media", "a.mp4", 1L, 1L)
        val b = ThumbnailKey("s2", "media", "a.mp4", 1L, 1L)
        val c = ThumbnailKey("s1", "other", "a.mp4", 1L, 1L)
        assertNotEquals(a.digest(), b.digest())
        assertNotEquals(a.digest(), c.digest())
    }
}
