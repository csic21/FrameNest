package com.framenest.data.thumbnail

import kotlin.math.roundToInt

/**
 * Cover frame size. The long side stays at [MAX_EDGE] and the other side
 * follows the video, so portrait is not squeezed into 16:9.
 */
internal object ThumbnailFrameGeometry {
    const val MAX_EDGE: Int = 320

    /** Largest RV32 buffer: a square at [MAX_EDGE] with a 32-byte pitch. */
    const val MAX_BYTES: Int = MAX_EDGE * (((MAX_EDGE * 4) + 31) / 32 * 32)

    data class Size(
        val width: Int,
        val height: Int,
        val pitch: Int,
    ) {
        val byteCount: Int get() = pitch * height
    }

    fun fit(srcWidth: Int, srcHeight: Int): Size {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val scale = MAX_EDGE.toDouble() / maxOf(safeW, safeH).toDouble()
        val width = even((safeW * scale).roundToInt().coerceIn(2, MAX_EDGE))
        val height = even((safeH * scale).roundToInt().coerceIn(2, MAX_EDGE))
        val pitch = ((width * 4 + 31) / 32) * 32
        return Size(width, height, pitch)
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)
}
