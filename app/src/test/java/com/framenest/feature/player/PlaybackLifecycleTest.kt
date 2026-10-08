package com.framenest.feature.player

import android.view.ViewGroup
import com.framenest.player.MediaSource
import com.framenest.player.PlayerController
import com.framenest.player.PlayerState
import com.framenest.player.VideoScaleMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLifecycleTest {
    private class Session {
        val player = RecordingPlayer()
        var focusResult = AudioFocusRequestResult.Granted
        var requests = 0
        var abandons = 0
        var repaints = 0
        val surfaces = mutableListOf<() -> Unit>()
        val lifecycle = PlaybackLifecycle(
            player,
            requestFocus = { requests++; focusResult },
            abandonFocus = { abandons++ },
            restoreSurface = { surfaces += it },
            repaint = { repaints++ },
        )
    }

    @Test fun initialEntry_staysPausedAndDoesNotRebindOrAcquireFocus() {
        val s = Session()
        s.lifecycle.onForeground()
        assertEquals(0, s.player.plays)
        assertEquals(0, s.requests)
        assertTrue(s.surfaces.isEmpty())
    }

    @Test fun playingBackgroundReturn_waitsForSurfaceThenRequestsFreshFocus() {
        val s = Session()
        s.lifecycle.play()
        s.lifecycle.onBackground()
        assertEquals(PlayerState.Phase.Paused, s.player.state.value.phase)
        s.lifecycle.onForeground()
        assertEquals(1, s.player.plays)
        assertEquals(1, s.requests)
        s.surfaces.single().invoke()
        assertEquals(2, s.player.plays)
        assertEquals(2, s.requests)
        s.surfaces.single().invoke()
        assertEquals(2, s.player.plays)
    }

    @Test fun intentionalPause_survivesRepeatedBackgroundAndSurfaceRecreation() {
        val s = Session()
        s.lifecycle.play()
        s.lifecycle.pause()
        repeat(3) {
            s.lifecycle.onBackground()
            s.lifecycle.onForeground()
            s.surfaces.last().invoke()
        }
        assertEquals(1, s.player.plays)
        assertEquals(3, s.repaints)
        assertEquals(0, s.player.releases)
    }

    @Test fun delayedFocus_stopThenGain_cannotStartBackgroundPlayback() {
        val s = Session()
        s.focusResult = AudioFocusRequestResult.Delayed
        s.lifecycle.play()
        s.lifecycle.onBackground()
        s.lifecycle.onFocusGained()
        assertEquals(0, s.player.plays)
        s.lifecycle.onForeground()
        s.lifecycle.onFocusGained()
        assertEquals(0, s.player.plays)
        s.surfaces.single().invoke()
        assertEquals(2, s.requests)
        s.lifecycle.onFocusGained()
        assertEquals(1, s.player.plays)
    }

    @Test fun pauseCancelsDelayedFocus_evenBeforeNativePlaybackStarts() {
        val s = Session()
        s.focusResult = AudioFocusRequestResult.Delayed
        s.lifecycle.play()
        s.lifecycle.pause()
        s.lifecycle.onFocusGained()
        s.lifecycle.onBackground()
        s.lifecycle.onForeground()
        s.surfaces.single().invoke()
        assertEquals(0, s.player.plays)
        assertEquals(1, s.requests)
    }

    @Test fun delayedSurfaceFromPreviousReturn_cannotResumeNewBackgroundCycle() {
        val s = Session()
        s.lifecycle.play()
        s.lifecycle.onBackground()
        s.lifecycle.onForeground()
        val stale = s.surfaces.last()
        s.lifecycle.onBackground()
        s.lifecycle.onForeground()
        stale()
        assertEquals(1, s.player.plays)
        s.surfaces.last().invoke()
        assertEquals(2, s.player.plays)
    }

    @Test fun pauseWhileSurfaceRebinds_repaintsInsteadOfPlaying() {
        val s = Session()
        s.lifecycle.play()
        s.lifecycle.onBackground()
        s.lifecycle.onForeground()
        s.lifecycle.pause()
        s.surfaces.single().invoke()
        assertEquals(1, s.player.plays)
        assertEquals(1, s.repaints)
    }

    @Test fun closeIsOnce_oldCallbacksCannotAffectRapidlyOpenedVideo() {
        repeat(20) {
            val a = Session()
            a.lifecycle.play()
            a.lifecycle.onBackground()
            a.lifecycle.onForeground()
            assertTrue(a.lifecycle.close())
            assertFalse(a.lifecycle.close())
            val b = Session()
            a.surfaces.single().invoke()
            a.lifecycle.onFocusGained()
            a.lifecycle.play()
            a.lifecycle.onForeground()
            assertEquals(1, a.player.plays)
            assertEquals(1, a.player.releases)
            assertEquals(0, b.player.plays)
            assertEquals(0, b.player.releases)
            b.lifecycle.play()
            assertEquals(1, b.player.plays)
            b.lifecycle.close()
        }
    }

    @Test fun preparingReplay_preservesPlayIntentAcrossBackground() {
        val s = Session()
        s.player.state.value = PlayerState(phase = PlayerState.Phase.Preparing)
        s.lifecycle.play()
        s.player.state.value = PlayerState(phase = PlayerState.Phase.Preparing)
        s.lifecycle.onBackground()
        s.lifecycle.onForeground()
        s.surfaces.single().invoke()
        assertEquals(2, s.player.plays)
    }

    @Test fun endedOrFailedVideo_doesNotRestartOnForeground() {
        for (phase in listOf(PlayerState.Phase.Ended, PlayerState.Phase.Error)) {
            val s = Session()
            s.lifecycle.play()
            s.player.state.value = PlayerState(phase = phase)
            s.lifecycle.onPlaybackState(s.player.state.value)
            s.lifecycle.onBackground()
            s.lifecycle.onForeground()
            s.surfaces.single().invoke()
            assertEquals(1, s.player.plays)
        }
    }
}

internal class RecordingPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerState(phase = PlayerState.Phase.Ready, firstFrameReady = true))
    var plays = 0
    var releases = 0
    override fun play() { plays++; state.value = state.value.copy(phase = PlayerState.Phase.Playing) }
    override fun pause() { state.value = state.value.copy(phase = PlayerState.Phase.Paused) }
    override fun release() { releases++; state.value = PlayerState() }
    override fun attachVideoLayout(container: ViewGroup) = Unit
    override fun detachVideoLayout(container: ViewGroup) = Unit
    override fun prepare(source: MediaSource, startPositionMs: Long) = Unit
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun setScrubbing(active: Boolean) = Unit
    override fun selectAudioTrack(trackId: Int) = true
    override fun setPlaybackRate(rate: Float) = Unit
    override fun selectSubtitleTrack(trackId: Int) = Unit
    override fun addExternalSubtitle(pathOrUri: String, select: Boolean) = true
    override fun disableSubtitles() = Unit
    override fun setSubtitleDelayMs(delayMs: Long) = Unit
    override fun setSubtitleFontRelSize(relSize: Int) = Unit
    override fun setVideoScaleMode(mode: VideoScaleMode) = Unit
    override fun refreshVideoSurfaces() = Unit
}
