package com.framenest.feature.player

/**
 * One-shot latch recording that the resume position has been consumed for the
 * current `prepare` cycle.
 *
 * The controller owns the resume seek itself (SMB fast-seek on first play, or
 * `:start-time` for local media), so the ViewModel only needs "did we already
 * hand the resume to this open?" to decide what a retry should restore. That
 * is an explicit boolean that [markFired] / [reset] owns.
 *
 * Thread note: only read/written from `viewModelScope` (main dispatcher), so
 * no synchronization is required for correctness.
 */
internal class ResumeSeekGate {
    private var fired: Boolean = false

    /** True after a manual [markFired], until [reset]. */
    val hasFired: Boolean
        get() = fired

    /** Mark the gate as spent (e.g. user pressed play, sought, or a source opened). */
    fun markFired() {
        fired = true
    }

    /** Re-arm the gate for a fresh prepare/retry cycle. */
    fun reset() {
        fired = false
    }
}

/** Picks the freshest retry position captured before the controller resets to Idle/0. */
internal fun retryResumePosition(livePositionMs: Long?, lastSavedPositionMs: Long?): Long =
    livePositionMs?.takeIf { it >= 0L }
        ?: lastSavedPositionMs?.takeIf { it > 0L }
        ?: 0L

/** Keeps a captured retry target across replacement jobs while teardown resets native state. */
internal class RetryResumeLatch {
    private var pending: Long? = null

    fun capture(positionMs: Long): Long = pending ?: positionMs.coerceAtLeast(0L).also { pending = it }

    fun userSeek(positionMs: Long) {
        // Explicit zero is intent, unlike Idle/0 produced by closeCurrentMedia().
        if (pending != null) pending = positionMs.coerceAtLeast(0L)
    }

    fun ready() { pending = null }
}
