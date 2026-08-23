package com.framenest.feature.player

import kotlin.math.abs

/**
 * Pure mapping for horizontal swipe seeking on the video surface.
 *
 * Left → right is forward; right → left is backward. A full-width swipe covers
 * at most [FULL_WIDTH_RANGE_MS], or the whole clip when it is shorter.
 */
internal object GestureSeekMath {
    const val FULL_WIDTH_RANGE_MS: Long = 90_000L
    const val MIN_RANGE_MS: Long = 15_000L

    enum class Axis {
        None,
        Vertical,
        Horizontal,
    }

    fun classifyAxis(dxPx: Float, dyPx: Float, touchSlop: Float): Axis {
        val dx = abs(dxPx)
        val dy = abs(dyPx)
        val slop = touchSlop.coerceAtLeast(0f)
        if (dx <= slop && dy <= slop) return Axis.None
        return if (dx > dy) Axis.Horizontal else Axis.Vertical
    }

    fun rangeMs(durationMs: Long): Long {
        if (durationMs <= 0L) return FULL_WIDTH_RANGE_MS
        return minOf(FULL_WIDTH_RANGE_MS, durationMs)
            .coerceAtLeast(minOf(MIN_RANGE_MS, durationMs))
    }

    fun deltaMs(dxPx: Float, widthPx: Float, durationMs: Long): Long {
        val width = widthPx.coerceAtLeast(1f)
        return ((dxPx / width) * rangeMs(durationMs)).toLong()
    }

    fun targetMs(
        startPositionMs: Long,
        durationMs: Long,
        dxPx: Float,
        widthPx: Float,
    ): Long = SkipSeekMath.targetPositionMs(
        positionMs = startPositionMs,
        durationMs = durationMs,
        deltaMs = deltaMs(dxPx, widthPx, durationMs),
    )
}
