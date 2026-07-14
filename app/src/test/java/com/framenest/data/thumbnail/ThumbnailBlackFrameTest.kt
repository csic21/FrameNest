package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailBlackFrameTest {

    @Test
    fun allBlack_isBlack() {
        val pixels = IntArray(64) { 0xFF000000.toInt() }
        assertTrue(ThumbnailBlackFrame.isBlackFrame(pixels))
    }

    @Test
    fun brightFrame_notBlack() {
        val pixels = IntArray(64) { 0xFFFFFFFF.toInt() }
        assertFalse(ThumbnailBlackFrame.isBlackFrame(pixels))
    }

    @Test
    fun empty_isBlack() {
        assertTrue(ThumbnailBlackFrame.isBlackFrame(IntArray(0)))
    }

    @Test
    fun sampleGrid_picksInteriorPixels() {
        val width = 4
        val height = 4
        val pixels = IntArray(width * height) { idx ->
            0xFF000000.toInt() or idx
        }
        val samples = ThumbnailBlackFrame.sampleGrid(pixels, width, height, samplesPerSide = 2)
        assertEquals(4, samples.size)
        samples.forEach { sample ->
            assertTrue(pixels.contains(sample))
        }
    }
}
