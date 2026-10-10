package com.framenest.feature.player

import com.framenest.feature.player.PlayerCaptionGeometry.VideoDisplayBounds
import com.framenest.player.PlayerVideoSize
import com.framenest.player.VideoScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCaptionGeometryTest {

    private val fullHd = PlayerVideoSize(width = 1920, height = 1080)

    @Test
    fun bestFitLetterboxes16to9InPortraitWindow() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 1216,
            viewportHeight = 2640,
            video = fullHd,
            scaleMode = VideoScaleMode.BestFit,
            isPortrait = true,
        )

        assertEquals(VideoDisplayBounds(left = 0, top = 978, right = 1216, bottom = 1662), bounds)
    }

    @Test
    fun fillStretchesToWholeViewport() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 1216,
            viewportHeight = 2640,
            video = fullHd,
            scaleMode = VideoScaleMode.Fill,
            isPortrait = true,
        )

        assertEquals(VideoDisplayBounds(left = 0, top = 0, right = 1216, bottom = 2640), bounds)
    }

    @Test
    fun fitScreenCoversAndCropsHorizontally() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 1216,
            viewportHeight = 2640,
            video = fullHd,
            scaleMode = VideoScaleMode.FitScreen,
            isPortrait = true,
        )!!

        assertTrue(bounds.left < 0)
        assertTrue(bounds.right > 1216)
        assertEquals(0, bounds.top)
        assertEquals(2640, bounds.bottom)
    }

    @Test
    fun fourThreeVideoUsesFullWidthInPortraitWindow() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 1216,
            viewportHeight = 2640,
            video = PlayerVideoSize(width = 720, height = 576),
            scaleMode = VideoScaleMode.BestFit,
            isPortrait = true,
        )

        assertEquals(VideoDisplayBounds(left = 0, top = 833, right = 1216, bottom = 1806), bounds)
    }

    @Test
    fun anamorphicSarIsCorrectedBeforeFitting() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 1216,
            viewportHeight = 2640,
            video = PlayerVideoSize(width = 720, height = 576, sarNum = 16, sarDen = 15),
            scaleMode = VideoScaleMode.BestFit,
            isPortrait = true,
        )

        assertEquals(VideoDisplayBounds(left = 0, top = 864, right = 1216, bottom = 1776), bounds)
    }

    @Test
    fun landscapeBestFitPillarboxes16to9() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 2640,
            viewportHeight = 1216,
            video = fullHd,
            scaleMode = VideoScaleMode.BestFit,
            isPortrait = false,
        )

        assertEquals(VideoDisplayBounds(left = 239, top = 0, right = 2401, bottom = 1216), bounds)
    }

    @Test
    fun viewportMismatchingOrientationUsesLibVlcSwap() {
        val bounds = PlayerCaptionGeometry.videoDisplayBounds(
            viewportWidth = 2000,
            viewportHeight = 1000,
            video = fullHd,
            scaleMode = VideoScaleMode.BestFit,
            isPortrait = true,
        )

        assertEquals(VideoDisplayBounds(left = 500, top = 218, right = 1500, bottom = 781), bounds)
    }

    @Test
    fun missingInputsReturnNull() {
        assertNull(
            PlayerCaptionGeometry.videoDisplayBounds(
                viewportWidth = 0,
                viewportHeight = 2640,
                video = fullHd,
                scaleMode = VideoScaleMode.BestFit,
                isPortrait = true,
            ),
        )
        assertNull(
            PlayerCaptionGeometry.videoDisplayBounds(
                viewportWidth = 1216,
                viewportHeight = 2640,
                video = PlayerVideoSize(width = 0, height = 0),
                scaleMode = VideoScaleMode.BestFit,
                isPortrait = true,
            ),
        )
    }

    @Test
    fun listenPaddingSitsAtPictureBottomWithoutCc() {
        val padding = PlayerCaptionGeometry.listenOverlayBottomPx(
            viewportHeight = 2640,
            bounds = VideoDisplayBounds(left = 0, top = 978, right = 1216, bottom = 1662),
            ccActive = false,
            subtitleFontRelSize = 16,
            gapPx = 16f,
            fallbackPx = 20f,
        )

        assertEquals(978f, padding, 0.01f)
    }

    @Test
    fun listenPaddingStacksOneCcLineAboveWhenCcActive() {
        val padding = PlayerCaptionGeometry.listenOverlayBottomPx(
            viewportHeight = 2640,
            bounds = VideoDisplayBounds(left = 0, top = 978, right = 1216, bottom = 1662),
            ccActive = true,
            subtitleFontRelSize = 16,
            gapPx = 16f,
            fallbackPx = 20f,
        )

        // 978 below the picture + 684/16*1.35 CC line + 16 gap.
        assertEquals(1051.71f, padding, 0.01f)
    }

    @Test
    fun listenPaddingFallsBackWithoutVideoGeometry() {
        val padding = PlayerCaptionGeometry.listenOverlayBottomPx(
            viewportHeight = 2640,
            bounds = null,
            ccActive = true,
            subtitleFontRelSize = 16,
            gapPx = 16f,
            fallbackPx = 72f,
        )

        assertEquals(72f, padding, 0.01f)
    }

    @Test
    fun ccLineHeightMatchesVlcFreetypeRelativeSize() {
        assertEquals(57.7125f, PlayerCaptionGeometry.estimatedCcLineHeightPx(684, 16), 0.001f)
        assertEquals(102.6f, PlayerCaptionGeometry.estimatedCcLineHeightPx(1216, 16), 0.001f)
        assertEquals(0f, PlayerCaptionGeometry.estimatedCcLineHeightPx(0, 16), 0.001f)
    }
}
