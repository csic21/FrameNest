package com.framenest.player

/** Pure event gating for keeping libVLC's asynchronous state aligned with UI intent. */
internal object PlaybackIntentPolicy {
    fun shouldForcePauseOnPlayingEvent(
        firstFrameReady: Boolean,
        playRequested: Boolean,
        seekPreviewActive: Boolean,
    ): Boolean = firstFrameReady && !playRequested && !seekPreviewActive

    fun shouldAcceptPausedEvent(playRequested: Boolean): Boolean = !playRequested
}
