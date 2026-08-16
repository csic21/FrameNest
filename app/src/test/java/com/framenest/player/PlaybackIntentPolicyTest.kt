package com.framenest.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentPolicyTest {
    @Test
    fun openingPlayback_runsUntilFirstFrame() {
        assertFalse(
            PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                firstFrameReady = false,
                playRequested = false,
                seekPreviewActive = false,
            ),
        )
    }

    @Test
    fun delayedPlayingEvent_cannotEscapePausedFirstFrame() {
        assertTrue(
            PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                firstFrameReady = true,
                playRequested = false,
                seekPreviewActive = false,
            ),
        )
    }

    @Test
    fun userPlayAndSeekPreview_areNotForcedBackToPause() {
        assertFalse(
            PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                firstFrameReady = true,
                playRequested = true,
                seekPreviewActive = false,
            ),
        )
        assertFalse(
            PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                firstFrameReady = true,
                playRequested = false,
                seekPreviewActive = true,
            ),
        )
    }

    @Test
    fun stalePausedEvent_isNotAcceptedAsUiStateAfterPlayRequest() {
        assertFalse(PlaybackIntentPolicy.shouldAcceptPausedEvent(playRequested = true))
        assertTrue(PlaybackIntentPolicy.shouldAcceptPausedEvent(playRequested = false))
    }

    @Test
    fun seek_preservesReadyAndPausedIntentWithoutStoppingPlayingIntent() {
        assertTrue(
            PlaybackIntentPolicy.shouldPreservePausedIntentOnSeek(PlayerState.Phase.Ready),
        )
        assertTrue(
            PlaybackIntentPolicy.shouldPreservePausedIntentOnSeek(PlayerState.Phase.Paused),
        )
        assertFalse(
            PlaybackIntentPolicy.shouldPreservePausedIntentOnSeek(PlayerState.Phase.Playing),
        )
    }
}
