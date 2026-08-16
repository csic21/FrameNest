package com.framenest.player

/**
 * Tracks the one paused-seek preview that may be retargeted by later seeks.
 *
 * A second seek must not cancel the first preview's eventual pause. It only moves
 * the target and reuses the same short-lived native playback session.
 */
internal class SeekPreviewSession {
    var active: Boolean = false
        private set

    var targetMs: Long = NO_TARGET
        private set

    /** Returns true when native preview playback needs to be started. */
    fun startOrRetarget(targetMs: Long): Boolean {
        val shouldStart = !active
        active = true
        this.targetMs = targetMs.coerceAtLeast(0L)
        return shouldStart
    }

    /** Returns whether an active preview was cleared. */
    fun clear(): Boolean {
        val wasActive = active
        active = false
        targetMs = NO_TARGET
        return wasActive
    }

    private companion object {
        const val NO_TARGET = -1L
    }
}
