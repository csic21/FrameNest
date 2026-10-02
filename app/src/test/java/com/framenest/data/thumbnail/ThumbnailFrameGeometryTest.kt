package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailFrameGeometryTest {

    @Test
    fun landscape16by9KeepsItsShape() {
        val size = ThumbnailFrameGeometry.fit(1920, 1080)
        assertEquals(320, size.width)
        assertEquals(180, size.height)
        assertEquals(1280, size.pitch)
    }

    @Test
    fun portraitKeepsItsShape() {
        val size = ThumbnailFrameGeometry.fit(1080, 1920)
        assertEquals(180, size.width)
        assertEquals(320, size.height)
        assertTrue(size.pitch >= size.width * 4)
        assertEquals(0, size.pitch % 32)
        assertTrue(size.byteCount <= ThumbnailFrameGeometry.MAX_BYTES)
    }

    @Test
    fun fourKPortraitMatchesHdPortrait() {
        assertEquals(
            ThumbnailFrameGeometry.fit(1080, 1920),
            ThumbnailFrameGeometry.fit(2160, 3840),
        )
    }
}
