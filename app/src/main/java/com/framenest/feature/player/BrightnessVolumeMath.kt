package com.framenest.feature.player

import kotlin.math.roundToInt

/**
 * Pure mapping from a vertical drag to brightness / volume targets.
 *
 * Gesture contract:
 * - [totalDeltaPx] is **cumulative** from the press (positive = finger moved up).
 * - [rangePx] is the distance that maps to a full 0→1 (or 0→max) swing from the
 *   baseline snapped at gesture start.
 * - Per-frame deltas must **not** be applied against a fixed baseline — that
 *   only ever moves by the last tiny step and feels stuck.
 */
internal object BrightnessVolumeMath {
    /**
     * @return brightness in `0f..1f` for [WindowManager.LayoutParams.screenBrightness].
     */
    fun brightnessTarget(baseline: Float, totalDeltaPx: Float, rangePx: Float): Float {
        val range = rangePx.coerceAtLeast(1f)
        return (baseline + totalDeltaPx / range).coerceIn(0f, 1f)
    }

    /**
     * @return stream volume index in `0..maxVolume` (inclusive).
     */
    fun volumeTarget(baseline: Int, maxVolume: Int, totalDeltaPx: Float, rangePx: Float): Int {
        val max = maxVolume.coerceAtLeast(0)
        if (max == 0) return 0
        val range = rangePx.coerceAtLeast(1f)
        val steps = (totalDeltaPx / range * max).roundToInt()
        return (baseline + steps).coerceIn(0, max)
    }

    fun brightnessPercent(level: Float): Int =
        (level.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)

    fun volumePercent(index: Int, maxVolume: Int): Int {
        val max = maxVolume.coerceAtLeast(0)
        if (max == 0) return 0
        return (index.coerceIn(0, max) * 100 / max).coerceIn(0, 100)
    }
}
