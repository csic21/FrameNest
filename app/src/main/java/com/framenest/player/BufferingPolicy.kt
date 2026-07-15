package com.framenest.player

/**
 * Pure helpers for libVLC Buffering events (FN-19).
 *
 * libVLC delivers [org.videolan.libvlc.MediaPlayer.Event.Buffering] with a float
 * progress in 0..100. Values at or above 100 mean the buffer is full.
 */
object BufferingPolicy {
    /** Treat progress at/above this as "not buffering". */
    const val FULL_PERCENT: Float = 100f

    /**
     * Normalize a raw libVLC buffering float into a display percent and whether
     * the UI should show a buffer indicator.
     */
    fun fromEventProgress(raw: Float): Snapshot {
        if (raw.isNaN() || raw.isInfinite()) {
            return Snapshot(isBuffering = false, percent = 0f)
        }
        val percent = raw.coerceIn(0f, FULL_PERCENT)
        return Snapshot(
            isBuffering = percent < FULL_PERCENT,
            percent = percent,
        )
    }

    /**
     * Whether the player surface should draw a buffer overlay.
     * - Preparing: always ok to show (enhances the loading spinner).
     * - Playing with first frame: mid-stream rebuffer / post-seek fill.
     * Never overlay Error (Retry must stay primary).
     */
    fun showOverlay(state: PlayerState): Boolean {
        if (!state.isBuffering) return false
        return when (state.phase) {
            PlayerState.Phase.Preparing -> true
            PlayerState.Phase.Playing -> state.firstFrameReady
            PlayerState.Phase.Paused,
            PlayerState.Phase.Ready,
            -> state.firstFrameReady
            PlayerState.Phase.Idle,
            PlayerState.Phase.Ended,
            PlayerState.Phase.Error,
            -> false
        }
    }

    data class Snapshot(
        val isBuffering: Boolean,
        val percent: Float,
    )
}
