package com.framenest.feature.player

import com.framenest.player.PlayerController
import com.framenest.player.PlayerState

/** Destination-owned playback intent. All calls, including surface/focus callbacks, run on main. */
internal class PlaybackLifecycle(
    private val controller: PlayerController,
    private val requestFocus: () -> AudioFocusRequestResult,
    private val abandonFocus: () -> Unit,
    private val restoreSurface: (onReady: () -> Unit) -> Unit,
    private val repaint: () -> Unit,
) {
    var closed: Boolean = false
        private set
    var foreground: Boolean = true
        private set
    private var surfaceReady = true
    private var wantsPlayback = false
    private var waitingForFocus = false
    private var surfaceGeneration = 0L

    fun play() {
        if (closed) return
        wantsPlayback = true
        startWhenReady()
    }

    fun pause() {
        if (closed) return
        wantsPlayback = false
        waitingForFocus = false
        abandonFocus()
        controller.pause()
    }

    fun onFocusLost() = pause()

    fun onFocusGained() {
        if (!closed && foreground && surfaceReady && wantsPlayback && waitingForFocus) {
            waitingForFocus = false
            controller.play()
        }
    }

    fun onPlaybackState(state: PlayerState) {
        if (state.phase == PlayerState.Phase.Ended || state.phase == PlayerState.Phase.Error) {
            wantsPlayback = false
            waitingForFocus = false
            abandonFocus()
        }
    }

    fun onBackground() {
        if (closed || !foreground) return
        wantsPlayback = wantsPlayback || controller.state.value.phase == PlayerState.Phase.Playing
        foreground = false
        surfaceReady = false
        surfaceGeneration++
        waitingForFocus = false
        abandonFocus()
        // Ready/Paused can still be doing a muted frame decode.
        controller.pause()
    }

    fun onForeground() {
        if (closed || foreground) return
        foreground = true
        val generation = ++surfaceGeneration
        restoreSurface {
            if (!closed && foreground && generation == surfaceGeneration && !surfaceReady) {
                surfaceReady = true
                if (wantsPlayback) startWhenReady() else repaint()
            }
        }
    }

    /** True only for the first exit; rotation never invokes this. */
    fun close(): Boolean {
        if (closed) return false
        closed = true
        foreground = false
        surfaceReady = false
        surfaceGeneration++
        wantsPlayback = false
        waitingForFocus = false
        abandonFocus()
        controller.release()
        return true
    }

    private fun startWhenReady() {
        if (closed || !foreground || !surfaceReady || !wantsPlayback) return
        waitingForFocus = false
        when (requestFocus()) {
            AudioFocusRequestResult.Granted -> controller.play()
            AudioFocusRequestResult.Delayed -> waitingForFocus = true
            AudioFocusRequestResult.Failed -> wantsPlayback = false
        }
    }
}
