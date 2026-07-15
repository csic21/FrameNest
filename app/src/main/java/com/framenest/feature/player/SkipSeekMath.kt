package com.framenest.feature.player

/**
 * Pure helpers for double-tap skip and surface tap classification (FN-16).
 */
internal object SkipSeekMath {
    const val SKIP_DELTA_MS: Long = 10_000L
    const val DOUBLE_TAP_WINDOW_MS: Long = 280L

    /**
     * Clamp a relative skip so the result stays in `[0, duration]` when duration
     * is known, or at least ≥0 when duration is unknown.
     */
    fun targetPositionMs(
        positionMs: Long,
        durationMs: Long,
        deltaMs: Long,
    ): Long {
        val position = positionMs.coerceAtLeast(0L)
        val raw = position + deltaMs
        return if (durationMs > 0L) {
            raw.coerceIn(0L, durationMs)
        } else {
            raw.coerceAtLeast(0L)
        }
    }

    /**
     * Classify a non-drag release as single-tap (chrome) or double-tap skip.
     * [lastTapAtMs] is 0 when there is no pending first tap.
     */
    fun classifyTap(
        nowMs: Long,
        x: Float,
        widthPx: Float,
        lastTapAtMs: Long,
        doubleTapWindowMs: Long = DOUBLE_TAP_WINDOW_MS,
    ): SurfaceTapAction {
        val isDouble = lastTapAtMs > 0L &&
            nowMs - lastTapAtMs <= doubleTapWindowMs
        if (!isDouble) return SurfaceTapAction.SingleTap
        val half = widthPx.coerceAtLeast(1f) / 2f
        return if (x < half) SurfaceTapAction.SkipBack else SurfaceTapAction.SkipForward
    }
}

internal enum class SurfaceTapAction {
    SingleTap,
    SkipBack,
    SkipForward,
}
