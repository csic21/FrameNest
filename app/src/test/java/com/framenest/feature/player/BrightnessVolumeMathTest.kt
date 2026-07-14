package com.framenest.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM rules for brightness / volume drag mapping.
 * No Android — same style as [ResumeSeekGateTest].
 */
class BrightnessVolumeMathTest {
    @Test
    fun `brightness accumulates from baseline using total delta`() {
        // Half-screen up from mid brightness → full bright.
        val target = BrightnessVolumeMath.brightnessTarget(
            baseline = 0.5f,
            totalDeltaPx = 500f,
            rangePx = 1000f,
        )
        assertEquals(1.0f, target, 0.001f)
    }

    @Test
    fun `brightness does not stick to per-frame steps`() {
        // Simulates a long drag reported as one cumulative distance (correct).
        val end = BrightnessVolumeMath.brightnessTarget(0.2f, totalDeltaPx = 800f, rangePx = 1000f)
        assertEquals(1.0f, end, 0.001f)
        // Same drag as two equal half-steps would still land at the same end
        // only when each call uses total-from-start, not last-frame delta:
        val mid = BrightnessVolumeMath.brightnessTarget(0.2f, totalDeltaPx = 400f, rangePx = 1000f)
        assertEquals(0.6f, mid, 0.001f)
        val again = BrightnessVolumeMath.brightnessTarget(0.2f, totalDeltaPx = 800f, rangePx = 1000f)
        assertEquals(1.0f, again, 0.001f)
    }

    @Test
    fun `brightness clamps to 0 and 1`() {
        assertEquals(
            0f,
            BrightnessVolumeMath.brightnessTarget(0.1f, totalDeltaPx = -500f, rangePx = 100f),
            0.001f,
        )
        assertEquals(
            1f,
            BrightnessVolumeMath.brightnessTarget(0.9f, totalDeltaPx = 500f, rangePx = 100f),
            0.001f,
        )
    }

    @Test
    fun `volume accumulates from baseline using total delta`() {
        // Full range drag up from 5 of 15 → 15.
        val target = BrightnessVolumeMath.volumeTarget(
            baseline = 5,
            maxVolume = 15,
            totalDeltaPx = 1000f,
            rangePx = 1000f,
        )
        assertEquals(15, target)
    }

    @Test
    fun `volume clamps and handles zero max`() {
        assertEquals(
            0,
            BrightnessVolumeMath.volumeTarget(3, maxVolume = 10, totalDeltaPx = -5000f, rangePx = 100f),
        )
        assertEquals(
            0,
            BrightnessVolumeMath.volumeTarget(0, maxVolume = 0, totalDeltaPx = 100f, rangePx = 100f),
        )
    }

    @Test
    fun `percent helpers`() {
        assertEquals(50, BrightnessVolumeMath.brightnessPercent(0.5f))
        assertEquals(0, BrightnessVolumeMath.volumePercent(0, 15))
        assertEquals(100, BrightnessVolumeMath.volumePercent(15, 15))
        assertEquals(0, BrightnessVolumeMath.volumePercent(3, 0))
    }
}
