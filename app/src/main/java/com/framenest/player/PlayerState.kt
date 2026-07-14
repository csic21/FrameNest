package com.framenest.player

/**
 * Immutable playback state for Compose / feature layers.
 *
 * [firstFrameReady] is true only after libVLC has delivered at least one vout
 * (decoded frame bound to the surface) while the controller is holding pause
 * for the product "prepare then show first frame" flow.
 */
data class PlayerState(
    val phase: Phase = Phase.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isSeekable: Boolean = false,
    val firstFrameReady: Boolean = false,
    val audioTracks: List<PlayerTrack> = emptyList(),
    val subtitleTracks: List<PlayerTrack> = emptyList(),
    val selectedAudioTrackId: Int? = null,
    val selectedSubtitleTrackId: Int? = null,
    /** Subtitle delay in milliseconds (libVLC SPU delay / 1000). */
    val subtitleDelayMs: Long = 0L,
    /**
     * Relative freetype font size for subtitles (smaller ⇒ larger text).
     * Default 16 matches libVLC's typical freetype-rel-fontsize.
     */
    val subtitleFontRelSize: Int = 16,
    val hwDecoderRequested: Boolean = true,
    val error: PlayerError? = null,
) {
    enum class Phase {
        Idle,
        Preparing,
        Ready,
        Playing,
        Paused,
        Ended,
        Error,
    }

    val canPlay: Boolean
        get() = phase == Phase.Ready || phase == Phase.Paused || phase == Phase.Ended

    val canPause: Boolean
        get() = phase == Phase.Playing
}
