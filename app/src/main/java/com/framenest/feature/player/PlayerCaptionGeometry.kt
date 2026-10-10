package com.framenest.feature.player

import com.framenest.player.PlayerVideoSize
import com.framenest.player.VideoScaleMode
import kotlin.math.ceil

/**
 * Mirrors libVLC 3.6.5 `VideoHelper.updateVideoSurfaces` display sizing so
 * app-drawn captions can be placed against the same picture band libVLC draws
 * CC subtitles into. Pure math: no Android views, no player state.
 */
internal object PlayerCaptionGeometry {

    /** Picture band inside the full-window surface host, in viewport pixels. */
    data class VideoDisplayBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun videoDisplayBounds(
        viewportWidth: Int,
        viewportHeight: Int,
        video: PlayerVideoSize,
        scaleMode: VideoScaleMode,
        isPortrait: Boolean,
    ): VideoDisplayBounds? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        if (video.width <= 0 || video.height <= 0) return null

        // libVLC corrects viewport values that do not match the activity
        // orientation before computing the display aspect ratio.
        var dw = viewportWidth.toDouble()
        var dh = viewportHeight.toDouble()
        if ((viewportWidth > viewportHeight && isPortrait) ||
            (viewportWidth < viewportHeight && !isPortrait)
        ) {
            val swap = dw
            dw = dh
            dh = swap
        }

        val sarNum = if (video.sarNum > 0) video.sarNum else 1
        val sarDen = if (video.sarDen > 0) video.sarDen else 1
        val visibleWidth = video.width
        val visibleHeight = video.height
        val aspectWidth: Double
        val aspect: Double
        if (sarDen == sarNum) {
            aspectWidth = visibleWidth.toDouble()
            aspect = visibleWidth.toDouble() / visibleHeight
        } else {
            aspectWidth = visibleWidth * (sarNum.toDouble() / sarDen)
            aspect = aspectWidth / visibleHeight
        }

        val displayAspect = dw / dh
        when (scaleMode) {
            VideoScaleMode.BestFit ->
                if (displayAspect < aspect) dh = dw / aspect else dw = dh * aspect

            VideoScaleMode.FitScreen ->
                if (displayAspect >= aspect) dh = dw / aspect else dw = dh * aspect

            VideoScaleMode.Fill -> Unit

            VideoScaleMode.Ratio16_9 -> {
                val ratio = 16.0 / 9.0
                if (displayAspect < ratio) dh = dw / ratio else dw = dh * ratio
            }

            VideoScaleMode.Ratio4_3 -> {
                val ratio = 4.0 / 3.0
                if (displayAspect < ratio) dh = dw / ratio else dw = dh * ratio
            }

            VideoScaleMode.Original -> {
                dh = visibleHeight.toDouble()
                dw = aspectWidth
            }
        }

        val width = ceil(dw * video.width / visibleWidth).toInt()
        val height = ceil(dh * video.height / visibleHeight).toInt()
        val left = (viewportWidth - width) / 2
        val top = (viewportHeight - height) / 2
        return VideoDisplayBounds(left, top, left + width, top + height)
    }

    /**
     * Screen-space bottom padding for the listen-translate caption: the gap
     * below the picture band plus, when CC is active, one reserved CC line so
     * the listen line stacks above it instead of overlapping.
     */
    fun listenOverlayBottomPx(
        viewportHeight: Int,
        bounds: VideoDisplayBounds?,
        ccActive: Boolean,
        subtitleFontRelSize: Int,
        gapPx: Float,
        fallbackPx: Float,
    ): Float {
        if (viewportHeight <= 0 || bounds == null) return fallbackPx
        val videoBottom = bounds.bottom.coerceIn(0, viewportHeight)
        val ccReserve = if (ccActive) {
            estimatedCcLineHeightPx(bounds.height, subtitleFontRelSize) + gapPx
        } else {
            0f
        }
        return (viewportHeight - videoBottom).toFloat().coerceAtLeast(0f) + ccReserve
    }

    /**
     * libVLC freetype: relative font size N renders at `videoHeight / N` px
     * (smaller N = larger text; default 16). Reserve a line box with leading
     * slack so the listen caption clears the CC line.
     */
    fun estimatedCcLineHeightPx(displayHeightPx: Int, subtitleFontRelSize: Int): Float {
        if (displayHeightPx <= 0) return 0f
        val relSize = subtitleFontRelSize.coerceAtLeast(1)
        return displayHeightPx.toFloat() / relSize * CC_LINE_BOX
    }

    private const val CC_LINE_BOX = 1.35f
}
