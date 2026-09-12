package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailScaledDstSizeTest {

    @Test
    fun landscape1080p_scalesTo320Wide() {
        assertEquals(
            Pair(320, 180),
            ThumbnailFrameExtractor.scaledDstSize(1920, 1080, 320),
        )
    }

    @Test
    fun portrait4k_preservesAspect() {
        assertEquals(
            Pair(180, 320),
            ThumbnailFrameExtractor.scaledDstSize(2160, 3840, 320),
        )
    }

    @Test
    fun smallVideo_returnsOriginalSize() {
        assertEquals(
            Pair(160, 120),
            ThumbnailFrameExtractor.scaledDstSize(160, 120, 320),
        )
    }

    @Test
    fun unknownDimensions_returnsNullForFallback() {
        assertNull(ThumbnailFrameExtractor.scaledDstSize(0, 0, 320))
        assertNull(ThumbnailFrameExtractor.scaledDstSize(1920, 0, 320))
        assertNull(ThumbnailFrameExtractor.scaledDstSize(0, 1080, 320))
    }
}
