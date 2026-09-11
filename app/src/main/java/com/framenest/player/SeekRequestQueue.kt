package com.framenest.player

/**
 * Keeps one issued seek and only the latest waiting target.
 *
 * The controller owns native calls and settles the issued seek when playback catches up.
 * [poll] also releases an unacknowledged seek after [timeoutMs], so a missing player event
 * cannot permanently block later targets. Call on one thread with a monotonic clock.
 */
internal class SeekRequestQueue(
    private val timeoutMs: Long = 1_500L,
) {
    private data class InFlight(val targetMs: Long, val startedAtMs: Long)

    private var inFlight: InFlight? = null
    private var pendingTargetMs: Long? = null

    init {
        require(timeoutMs > 0L)
    }

    val inFlightTargetMs: Long?
        get() = inFlight?.targetMs

    /** The last unfinished user target, suitable as the base for another relative seek. */
    val latestTargetMs: Long?
        get() = pendingTargetMs ?: inFlight?.targetMs

    val hasPending: Boolean
        get() = pendingTargetMs != null

    fun submit(targetMs: Long) {
        require(targetMs >= 0L)
        // Dragging back to the issued target cancels an intermediate waiting target.
        pendingTargetMs = targetMs.takeUnless { it == inFlight?.targetMs }
    }

    /** Returns and records a target only when the caller may issue the next native seek. */
    fun poll(nowMs: Long): Long? {
        val active = inFlight
        if (active != null) {
            if (nowMs - active.startedAtMs < timeoutMs) return null
            inFlight = null
        }
        val target = pendingTargetMs ?: return null
        pendingTargetMs = null
        inFlight = InFlight(target, nowMs)
        return target
    }

    /** Completes the issued seek without dropping a newer waiting target. */
    fun markSettled() {
        inFlight = null
    }

    /** Discards work belonging to a stopped, ended, replaced, or failed media input. */
    fun reset() {
        inFlight = null
        pendingTargetMs = null
    }
}
