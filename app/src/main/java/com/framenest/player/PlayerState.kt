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
    /**
     * How the decoded frame is fit into the video surface.
     * Default [VideoScaleMode.BestFit] preserves aspect (no stretch).
     */
    val videoScaleMode: VideoScaleMode = VideoScaleMode.BestFit,
    /**
     * Playback rate multiplier (1.0 = normal). Discrete steps in [PlaybackRates.ALL].
     */
    val playbackRate: Float = PlaybackRates.DEFAULT,
    /**
     * True while libVLC reports a Buffering event with progress &lt; 100%.
     * Does not change [phase] — mid-stream rebuffer keeps [Phase.Playing].
     */
    val isBuffering: Boolean = false,
    /**
     * Buffer fill 0f..100f from [org.videolan.libvlc.MediaPlayer.Event.getBuffering].
     * Meaningful only when [isBuffering] is true (or freshly cleared at 100).
     */
    val bufferPercent: Float = 0f,
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

    /**
     * True only when a real decoded frame has been delivered ([firstFrameReady])
     * and the phase accepts play. List static thumbnails must never set this.
     */
    val canPlay: Boolean
        get() = firstFrameReady &&
            (phase == Phase.Ready || phase == Phase.Paused || phase == Phase.Ended)

    val canPause: Boolean
        get() = phase == Phase.Playing
}
