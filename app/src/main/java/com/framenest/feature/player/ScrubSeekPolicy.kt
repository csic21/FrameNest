package com.framenest.feature.player

import kotlin.math.abs

/**
 * Pure rules for slider / gesture scrubbing.
 *
 * Pointer events are far denser than a stable SMB seek. Preview seeks are
 * throttled by time and by how far the target moved; release always commits
 * the last thumb position.
 */
internal object ScrubSeekPolicy {
    /** Minimum gap between in-drag preview seeks. */
    const val PREVIEW_INTERVAL_MS: Long = 160L

    /** Ignore preview retargets smaller than this once a preview has been sent. */
    const val PREVIEW_MIN_DELTA_MS: Long = 400L

    fun targetMs(durationMs: Long, fraction: Float): Long {
        if (durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /**
     * First preview is immediate. Later previews need both enough time and a
     * meaningful jump so overlapping libVLC/SMB seeks stay bounded.
     */
    fun shouldEmitPreview(
        nowMs: Long,
        lastPreviewAtMs: Long,
        lastTargetMs: Long,
        targetMs: Long,
    ): Boolean {
        if (targetMs == lastTargetMs) return false
        if (lastPreviewAtMs <= 0L) return true
        if (nowMs - lastPreviewAtMs < PREVIEW_INTERVAL_MS) return false
        return abs(targetMs - lastTargetMs) >= PREVIEW_MIN_DELTA_MS
    }
}
