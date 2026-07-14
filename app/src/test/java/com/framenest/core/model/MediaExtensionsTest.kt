package com.framenest.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaExtensionsTest {

    @Test
    fun extensionOf_handlesDotsAndCase() {
        assertEquals("mkv", MediaExtensions.extensionOf("Movie.MKV"))
        assertEquals("srt", MediaExtensions.extensionOf("a.b.srt"))
        assertEquals("", MediaExtensions.extensionOf("noext"))
        assertEquals("", MediaExtensions.extensionOf(".hidden"))
    }

    @Test
    fun isVideo_coversMvpFormats() {
        assertTrue(MediaExtensions.isVideo("clip.mp4"))
        assertTrue(MediaExtensions.isVideo("clip.MKV"))
        assertTrue(MediaExtensions.isVideo("clip.m2ts"))
        assertFalse(MediaExtensions.isVideo("clip.srt"))
        assertFalse(MediaExtensions.isVideo("readme.txt"))
    }

    @Test
    fun isSubtitle_coversMvpFormats() {
        assertTrue(MediaExtensions.isSubtitle("a.srt"))
        assertTrue(MediaExtensions.isSubtitle("a.ass"))
        assertTrue(MediaExtensions.isSubtitle("a.ssa"))
        assertTrue(MediaExtensions.isSubtitle("a.vtt"))
        assertFalse(MediaExtensions.isSubtitle("a.mp4"))
    }

    @Test
    fun isBrowsableMediaFile_videoAndSubtitleOnly() {
        assertTrue(MediaExtensions.isBrowsableMediaFile("x.mkv"))
        assertTrue(MediaExtensions.isBrowsableMediaFile("x.srt"))
        assertFalse(MediaExtensions.isBrowsableMediaFile("folder"))
        assertFalse(MediaExtensions.isBrowsableMediaFile("poster.jpg"))
        assertFalse(MediaExtensions.isBrowsableMediaFile("info.nfo"))
    }
}
