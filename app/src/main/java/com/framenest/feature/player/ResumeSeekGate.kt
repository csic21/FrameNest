package com.framenest.feature.player

import com.framenest.player.PlayerState

/**
 * One-shot gate that fires the resume-position seek exactly once per `prepare` cycle.
 *
 * Earlier code reused `startPositionMs > 0` as the "already fired?" flag, keyed to
 * `phase == Ready/Paused`. That broke when the user tapped `play` before the first
 * `Ready` state arrived: `play()` flipped phase to `Playing`, the stale check later
 * matched on the next `Paused`, and a residual `startPositionMs` dragged the viewer
 * back to the resume point. This gate makes "did we fire?" an explicit boolean that
 * [markFired] / [reset] owns, decoupled from phase observation.
 *
 * Thread note: only read/written from `viewModelScope` (main dispatcher) via
 * `controller.state.collect`, so no synchronization is required for correctness.
 */
internal class ResumeSeekGate {
    private var fired: Boolean = false

    /** True after a natural [shouldFire] or a manual [markFired], until [reset]. */
    val hasFired: Boolean
        get() = fired

    /**
     * Decide whether the resume seek should fire for the given state snapshot.
     *
     * Returns true (and latches [fired]) only the first time the state has a decoded
     * frame and a rest phase where a seek is safe to apply. Subsequent calls return
     * false until [reset].
     */
    fun shouldFire(
        firstFrameReady: Boolean,
        phase: PlayerState.Phase,
        startPositionMs: Long,
    ): Boolean {
        if (fired || startPositionMs <= 0L || !firstFrameReady) return false
        if (phase != PlayerState.Phase.Ready && phase != PlayerState.Phase.Paused) return false
        fired = true
        return true
    }

    /** Mark the gate as spent even without a natural fire (e.g. user pressed play). */
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
    livePositionMs?.takeIf { it > 0L }
        ?: lastSavedPositionMs?.takeIf { it > 0L }
        ?: 0L
