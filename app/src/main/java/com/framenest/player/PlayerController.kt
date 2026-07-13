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

    /** Full teardown of MediaPlayer + LibVLC. Idempotent. */
    fun release()
}
