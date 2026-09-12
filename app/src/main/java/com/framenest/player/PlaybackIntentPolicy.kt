package com.framenest.player

/** Pure event gating for keeping libVLC's asynchronous state aligned with UI intent. */
internal object PlaybackIntentPolicy {
    fun shouldForcePauseOnPlayingEvent(
        firstFrameReady: Boolean,
        playRequested: Boolean,
        seekPreviewActive: Boolean,
    ): Boolean = firstFrameReady && !playRequested && !seekPreviewActive

    fun shouldAcceptPausedEvent(playRequested: Boolean): Boolean = !playRequested

    /** Seeking changes position, never a user's Ready/Paused playback intent. */
    fun shouldPreservePausedIntentOnSeek(phase: PlayerState.Phase): Boolean =
        phase == PlayerState.Phase.Ready || phase == PlayerState.Phase.Paused

    /**
     * Player-local mute used by in-drag preview and paused frame preview.
     * Restore only when neither session still owns the decoder.
     */
    fun shouldRestoreTransientVolume(
        scrubbing: Boolean,
        seekPreviewActive: Boolean,
    ): Boolean = !scrubbing && !seekPreviewActive

    /**
     * Target to apply on a fresh input after replaying from EOF.
     *
     * An ended input cannot be resumed with setTime, so replay reopens from
     * zero and seeks once the first frame lands. Prefer an explicit in-Ended
     * seek; otherwise reuse the resting clock. Returns null when there is
     * nothing worth restoring (start of media) or when the candidate sits
     * inside the end-epsilon window (seeking there would EOF again — the
     * caller replays from zero and the user can seek once playback runs).
     */
    fun replayResumeTargetMs(
        seekTargetMs: Long?,
        positionMs: Long,
        durationMs: Long,
        endEpsilonMs: Long,
    ): Long? {
        val candidate = seekTargetMs ?: positionMs.takeIf { it > 0L } ?: return null
        if (durationMs > 0L && candidate >= (durationMs - endEpsilonMs).coerceAtLeast(0L)) {
            return null
        }
        return candidate
    }
}
