package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun transientMute_staysUntilScrubAndPausedPreviewBothEnd() {        assertFalse(
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = true,
                seekPreviewActive = false,
            ),
        )
        assertFalse(
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = false,
                seekPreviewActive = true,
            ),
        )
        assertTrue(
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = false,
                seekPreviewActive = false,
            ),
        )
    }

    @Test
    fun replay_prefersExplicitInEndedSeekTarget() {
        assertEquals(
            60_000L,
            PlaybackIntentPolicy.replayResumeTargetMs(
                seekTargetMs = 60_000L,
                positionMs = 600_000L,
                durationMs = 600_000L,
                endEpsilonMs = 400L,
            ),
        )
    }

    @Test
    fun replay_fallsBackToRestingClockWithoutSeek() {
        assertEquals(
            300_000L,
            PlaybackIntentPolicy.replayResumeTargetMs(
                seekTargetMs = null,
                positionMs = 300_000L,
                durationMs = 600_000L,
                endEpsilonMs = 400L,
            ),
        )
    }

    @Test
    fun replay_dropsTargetsInsideEndEpsilon() {
        assertNull(
            PlaybackIntentPolicy.replayResumeTargetMs(
                seekTargetMs = 599_900L,
                positionMs = 599_900L,
                durationMs = 600_000L,
                endEpsilonMs = 400L,
            ),
        )
    }

    @Test
    fun replay_returnsNullAtStartOrWithoutDuration() {
        assertNull(
            PlaybackIntentPolicy.replayResumeTargetMs(
                seekTargetMs = null,
                positionMs = 0L,
                durationMs = 600_000L,
                endEpsilonMs = 400L,
            ),
        )
        assertEquals(
            60_000L,
            PlaybackIntentPolicy.replayResumeTargetMs(
                seekTargetMs = 60_000L,
                positionMs = 0L,
                durationMs = 0L,
                endEpsilonMs = 400L,
            ),
        )
    }
}
