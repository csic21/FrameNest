package com.framenest.data.history

/**
 * Pure rules for progress persistence and "completed" marking.
 *
 * Completion rule (documented for product + tests):
 * - Treat as completed when a sufficiently long video's remaining duration is
 *   **&lt; [COMPLETE_REMAINING_MS]**, **or** position fraction is
 *   **≥ [COMPLETE_FRACTION]** (90%),
 *   or position has reached/passed duration.
 * - Completed items resume from the start (position 0), not near EOF.
 */
object PlaybackProgressRules {
    /** Mark completed when less than 30 seconds remain. */
    const val COMPLETE_REMAINING_MS: Long = 30_000L

    /** Mark completed when watched at least 90% of duration. */
    const val COMPLETE_FRACTION: Double = 0.90

    /**
     * The absolute remaining-time rule only applies to videos at least this long.
     * Otherwise a 20-second clip would be considered completed at position zero.
     */
    const val MIN_DURATION_FOR_REMAINING_RULE_MS: Long = COMPLETE_REMAINING_MS * 2

    /** Default interval for periodic progress saves while playing. */
    const val PERIODIC_SAVE_INTERVAL_MS: Long = 5_000L

    /**
     * @return true when the viewer should be considered finished for resume purposes.
     */
    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val position = positionMs.coerceAtLeast(0L)
        if (position >= durationMs) return true
        val remaining = durationMs - position
        if (durationMs >= MIN_DURATION_FOR_REMAINING_RULE_MS &&
            remaining < COMPLETE_REMAINING_MS
        ) {
            return true
        }
        val fraction = position.toDouble() / durationMs.toDouble()
        return fraction >= COMPLETE_FRACTION
    }

    /**
     * Position to resume from after loading history.
     * Completed (or would-be-completed) entries start over at 0.
     */
    fun resumePositionMs(positionMs: Long, durationMs: Long, completed: Boolean): Long {
        if (completed) return 0L
        if (isCompleted(positionMs, durationMs)) return 0L
        return positionMs.coerceAtLeast(0L)
    }

    /**
     * Whether a progress write is meaningful (avoid thrashing identical snapshots).
     */
    fun shouldPersist(
        previousPositionMs: Long?,
        newPositionMs: Long,
        minDeltaMs: Long = 1_000L,
    ): Boolean {
        if (previousPositionMs == null) return true
        return kotlin.math.abs(newPositionMs - previousPositionMs) >= minDeltaMs
    }
}
