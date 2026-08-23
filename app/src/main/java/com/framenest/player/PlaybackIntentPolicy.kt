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
}
