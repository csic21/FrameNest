package com.framenest.player

import android.view.ViewGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal player boundary for UI. Intentionally not a multi-engine abstraction —
 * only the operations Compose needs today.
 */
interface PlayerController {
    val state: StateFlow<PlayerState>

    /**
     * Bind video output. Must be called with a live [ViewGroup] that will host
     * [org.videolan.libvlc.util.VLCVideoLayout] (or is one). Safe to call once
     * per surface attach cycle.
     */
    fun attachVideoLayout(container: ViewGroup)

    fun detachVideoLayout(container: ViewGroup)

    /**
     * Prepare media with a pending [startPositionMs], decode the first frame, then hold
     * pause ([PlayerState.firstFrameReady]). The controller applies the pending position
     * using the source's stable seek mode when playback starts.
     */
    fun prepare(source: MediaSource, startPositionMs: Long = 0L)

    fun play()

    fun pause()

    /** Seek once to [positionMs]; remote SMB may snap to a nearby keyframe. */
    fun seekTo(positionMs: Long)

    fun selectAudioTrack(trackId: Int)

    /**
     * Playback rate multiplier. Values are snapped to [PlaybackRates] steps.
     * Applied immediately when a [MediaPlayer] exists; remembered across prepare.
     */
    fun setPlaybackRate(rate: Float)

    fun selectSubtitleTrack(trackId: Int)

    /**
     * Attach an external subtitle slave from a local filesystem path or content URI.
     * Failures must not stop video playback; returns false on error.
     *
     * @param pathOrUri local absolute path or `file://` / content URI string
     * @param select when true, select the newly added SPU track after attach
     */
    fun addExternalSubtitle(pathOrUri: String, select: Boolean = true): Boolean

    /** Disable subtitles (libVLC SPU id -1). */
    fun disableSubtitles()

    /**
     * Subtitle presentation delay in milliseconds (positive = show later).
     * Mapped to libVLC microseconds via [org.videolan.libvlc.MediaPlayer.setSpuDelay].
     */
    fun setSubtitleDelayMs(delayMs: Long)

    /**
     * Relative freetype font size (libVLC `--freetype-rel-fontsize` / media option).
     * Smaller values produce larger on-screen text. Applied on next media options
     * and best-effort live via media options when a media is active.
     */
    fun setSubtitleFontRelSize(relSize: Int)

    /**
     * How the video is fit into the surface (best-fit / fill / fixed ratios).
     * Applied immediately when views are attached; remembered across prepare.
     */
    fun setVideoScaleMode(mode: VideoScaleMode)

    /**
     * Recompute surface size after container layout changes (rotation, chrome
     * show/hide, window size). Safe no-op when views are not attached.
     */
    fun refreshVideoSurfaces()

    /**
     * Start full teardown of MediaPlayer + LibVLC. Idempotent; implementations may
     * complete blocking native/resource release asynchronously after video output detaches.
     */
    fun release()
}
