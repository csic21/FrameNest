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

    fun detachVideoLayout()

    /** Prepare media, decode first frame, then hold pause ([PlayerState.firstFrameReady]). */
    fun prepare(source: MediaSource)

    fun play()

    fun pause()

    /** Seek to [positionMs]; no-op if not seekable. */
    fun seekTo(positionMs: Long)

    fun selectAudioTrack(trackId: Int)

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

    /** Full teardown of MediaPlayer + LibVLC. Idempotent. */
    fun release()
}
