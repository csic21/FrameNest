package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipSeekMathTest {

    @Test
    fun targetPosition_clampsToDuration() {
        assertEquals(
            15_000L,
            SkipSeekMath.targetPositionMs(
                positionMs = 5_000L,
                durationMs = 60_000L,
                deltaMs = 10_000L,
            ),
        )
        assertEquals(
            0L,
            SkipSeekMath.targetPositionMs(
                positionMs = 3_000L,
                durationMs = 60_000L,
                deltaMs = -10_000L,
            ),
        )
        assertEquals(
            60_000L,
            SkipSeekMath.targetPositionMs(
                positionMs = 55_000L,
                durationMs = 60_000L,
                deltaMs = 10_000L,
            ),
        )
    }

    @Test
    fun targetPosition_withoutDuration_neverGoesNegative() {
        assertEquals(
            0L,
            SkipSeekMath.targetPositionMs(
                positionMs = 2_000L,
                durationMs = 0L,
                deltaMs = -10_000L,
            ),
        )
        assertEquals(
            12_000L,
            SkipSeekMath.targetPositionMs(
                positionMs = 2_000L,
                durationMs = 0L,
                deltaMs = 10_000L,
            ),
        )
    }

    @Test
    fun classifyTap_doubleTapLeftIsSkipBack() {
        assertEquals(
            SurfaceTapAction.SkipBack,
            SkipSeekMath.classifyTap(
                nowMs = 300L,
                x = 10f,
                widthPx = 100f,
                lastTapAtMs = 100L,
            ),
        )
    }

    @Test
    fun classifyTap_doubleTapRightIsSkipForward() {
        assertEquals(
            SurfaceTapAction.SkipForward,
            SkipSeekMath.classifyTap(
                nowMs = 300L,
                x = 80f,
                widthPx = 100f,
                lastTapAtMs = 100L,
            ),
        )
    }

    @Test
    fun classifyTap_outsideWindowIsSingle() {
        assertEquals(
            SurfaceTapAction.SingleTap,
            SkipSeekMath.classifyTap(
                nowMs = 1_000L,
                x = 10f,
                widthPx = 100f,
                lastTapAtMs = 100L,
            ),
        )
        assertEquals(
            SurfaceTapAction.SingleTap,
            SkipSeekMath.classifyTap(
                nowMs = 100L,
                x = 10f,
                widthPx = 100f,
                lastTapAtMs = 0L,
            ),
        )
    }
}
