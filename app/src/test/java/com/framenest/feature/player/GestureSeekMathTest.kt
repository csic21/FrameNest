package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureSeekMathTest {

    @Test
    fun classifyAxis_requiresSlopThenPicksDominantDirection() {
        assertEquals(
            GestureSeekMath.Axis.None,
            GestureSeekMath.classifyAxis(dxPx = 6f, dyPx = 4f, touchSlop = 8f),
        )
        assertEquals(
            GestureSeekMath.Axis.Horizontal,
            GestureSeekMath.classifyAxis(dxPx = 40f, dyPx = 8f, touchSlop = 8f),
        )
        assertEquals(
            GestureSeekMath.Axis.Vertical,
            GestureSeekMath.classifyAxis(dxPx = 8f, dyPx = 40f, touchSlop = 8f),
        )
        assertEquals(
            GestureSeekMath.Axis.Vertical,
            GestureSeekMath.classifyAxis(dxPx = 20f, dyPx = 20f, touchSlop = 8f),
        )
    }

    @Test
    fun swipeRightSeeksForwardAndLeftSeeksBack() {
        assertEquals(
            55_000L,
            GestureSeekMath.targetMs(
                startPositionMs = 10_000L,
                durationMs = 120_000L,
                dxPx = 400f,
                widthPx = 800f,
            ),
        )
        assertEquals(
            0L,
            GestureSeekMath.targetMs(
                startPositionMs = 10_000L,
                durationMs = 120_000L,
                dxPx = -400f,
                widthPx = 800f,
            ),
        )
    }

    @Test
    fun shortMediaUsesItsOwnDurationAsRange() {
        assertEquals(
            10_000L,
            GestureSeekMath.rangeMs(10_000L),
        )
        assertEquals(
            GestureSeekMath.FULL_WIDTH_RANGE_MS,
            GestureSeekMath.rangeMs(0L),
        )
        assertEquals(
            30_000L,
            GestureSeekMath.targetMs(
                startPositionMs = 0L,
                durationMs = 30_000L,
                dxPx = 100f,
                widthPx = 100f,
            ),
        )
    }
}
