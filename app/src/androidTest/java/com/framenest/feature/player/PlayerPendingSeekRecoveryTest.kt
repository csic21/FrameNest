package com.framenest.feature.player

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import com.framenest.player.MediaSource
import com.framenest.player.PlayerError
import com.framenest.player.PlayerState
import com.framenest.player.VlcPlayerController
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.MediaPlayer

/**
 * Injects only the hard-to-time EOF/watchdog events. Reopen, first frame, seek and subsequent
 * clock advancement still run through real libVLC and the bundled video, without a NAS.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerPendingSeekRecoveryTest {
    @Test
    fun eofFromOlderSeek_keepsPendingMiddleTarget_andPlaybackCanContinue() {
        verifyPendingTargetSurvivesEof(targetMs = 1_000L)
    }

    @Test
    fun eofFromOlderSeek_keepsPendingZeroTarget_andPlaybackCanContinue() {
        verifyPendingTargetSurvivesEof(targetMs = 0L)
    }

    @Test
    fun pausedRemotePreview_doesNotPauseDuringBuffering_thenSettlesAfterBufferingEnds() {
        withPlayer { scenario, player ->
            scenario.onActivity {
                val sourceField = VlcPlayerController::class.java.getDeclaredField("currentSource")
                sourceField.isAccessible = true
                val originalSource = sourceField.get(player)
                try {
                    // Change only the controller's seek policy marker. The already-open
                    // native input remains the bundled file; never prepare this URI.
                    sourceField.set(
                        player,
                        MediaSource.Smb(Uri.parse("smb://example.invalid/media/sample.mp4")),
                    )
                    // Zero is a deterministic keyframe for the remote fast-seek policy;
                    // this regression tests buffering and pause, not precise seeking.
                    player.seekTo(0L)
                    assertTrue("Test must start paused seek decoding", player.state.value.isSeeking)
                    handleEvent(player, TestEvent(MediaPlayer.Event.Buffering, 25f))

                    val timeout = field(player, "seekPreviewPauseRunnable") as Runnable
                    // Emulate a dequeued timer, so buffering completion must supply a
                    // new fallback instead of relying on the original scheduled copy.
                    (field(player, "mainHandler") as Handler).removeCallbacks(timeout)
                    timeout.run()
                    val stillLoading = player.state.value
                    assertTrue("Preview timeout paused an unfinished buffer", stillLoading.isSeeking)
                    assertTrue("Preview timeout hid an unfinished buffer", stillLoading.isBuffering)
                    assertTrue("Internal preview changed user pause intent", stillLoading.isPausedForUser())
                    assertEquals(0, (field(player, "mediaPlayer") as MediaPlayer).volume)

                    handleEvent(player, TestEvent(MediaPlayer.Event.Buffering, 100f))
                    assertFalse(player.state.value.isBuffering)
                } finally {
                    // Even a failed assertion must leave no possible remote reopen source.
                    sourceField.set(player, originalSource)
                }
            }

            val landed = awaitNative(scenario, player, "preview after buffering completed") { snapshot ->
                // Muted decoding advances until the 750ms pause fallback (or an
                // earlier settle). Check a bounded preview near the start, not
                // the precise-seek tolerance used by the separate local tests.
                snapshot.state.firstFrameReady && snapshot.state.isPausedForUser() &&
                    !snapshot.state.isSeeking && !snapshot.state.isBuffering && !snapshot.isPlaying &&
                    snapshot.positionMs in 0L..(snapshot.state.durationMs / 2L)
            }
            assertNativeClockAdvances(scenario, player, landed.positionMs)

            scenario.onActivity {
                player.pause()
                val sourceField = VlcPlayerController::class.java.getDeclaredField("currentSource")
                sourceField.isAccessible = true
                val originalSource = sourceField.get(player)
                try {
                    sourceField.set(
                        player,
                        MediaSource.Smb(Uri.parse("smb://example.invalid/media/sample.mp4")),
                    )
                    player.seekTo(2_000L)
                    handleEvent(player, TestEvent(MediaPlayer.Event.Buffering, 25f))
                    assertTrue(player.state.value.isSeeking)

                    // Lifecycle/audio-focus pause must interrupt the internal muted
                    // decode immediately, even though its public phase was Paused.
                    player.pause()
                    assertFalse("Pause left internal preview running", player.state.value.isSeeking)
                    assertTrue(player.state.value.isPausedForUser())
                    handleEvent(player, TestEvent(MediaPlayer.Event.Buffering, 100f))
                } finally {
                    sourceField.set(player, originalSource)
                }
            }
            awaitNative(scenario, player, "explicit pause during buffered preview") { snapshot ->
                snapshot.state.isPausedForUser() && !snapshot.state.isSeeking && !snapshot.isPlaying
            }
            // Observe beyond the preview fallback: a late buffering completion must
            // neither restart native playback nor reinstate a preview that was cancelled.
            val pausedUntil = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < pausedUntil) {
                scenario.onActivity {
                    assertTrue(player.state.value.isPausedForUser())
                    assertFalse(player.state.value.isSeeking)
                    assertFalse((field(player, "mediaPlayer") as MediaPlayer).isPlaying)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    @Test
    fun loadTimeout_clearsBuffering_ignoresOldEvents_andFreshInputReallyPlays() {
        withPlayer { scenario, player ->
            lateinit var staleListener: MediaPlayer.EventListener
            var positionAtError = 0L
            scenario.onActivity {
                staleListener = listenerForCurrentInput(player)
                handleEvent(player, TestEvent(MediaPlayer.Event.Buffering, 25f))
                assertTrue("Test must enter buffering before timing out", player.state.value.isBuffering)

                // Exercise the production watchdog without spending 30 seconds asleep.
                (field(player, "loadTimeoutRunnable") as Runnable).run()
                val failed = player.state.value
                assertEquals(PlayerState.Phase.Error, failed.phase)
                assertEquals(PlayerError.Code.OpenFailed, failed.error?.code)
                assertTrue(failed.error?.retryable == true)
                assertFalse(failed.firstFrameReady)
                assertFalse(failed.isBuffering)
                assertFalse(failed.isSeeking)
                positionAtError = failed.positionMs

                staleListener.onEvent(TestEvent(MediaPlayer.Event.Playing))
                staleListener.onEvent(TestEvent(MediaPlayer.Event.TimeChanged, 3_000L))
                staleListener.onEvent(TestEvent(MediaPlayer.Event.Vout, 1L))
                staleListener.onEvent(TestEvent(MediaPlayer.Event.EndReached))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val failed = player.state.value
                assertEquals("Old input revived the timed-out player", PlayerState.Phase.Error, failed.phase)
                assertEquals(positionAtError, failed.positionMs)
                assertFalse(failed.firstFrameReady)
                player.prepare(MediaSource.RawResource(R.raw.sample_h264))
            }

            val ready = awaitNative(scenario, player, "fresh input after timeout") { snapshot ->
                snapshot.state.firstFrameReady && snapshot.state.isPausedForUser()
            }
            scenario.onActivity {
                // Once the new input is Ready, global suppression is no longer enough:
                // the old listener's generation must keep these terminal events out.
                staleListener.onEvent(TestEvent(MediaPlayer.Event.EncounteredError))
                staleListener.onEvent(TestEvent(MediaPlayer.Event.EndReached))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue("Old terminal event replaced the new input", player.state.value.isPausedForUser())
            }
            assertNativeClockAdvances(scenario, player, ready.positionMs)
        }
    }

    private fun verifyPendingTargetSurvivesEof(targetMs: Long) {
        withPlayer { scenario, player ->
            scenario.onActivity { player.seekTo(2_000L) }
            awaitNative(scenario, player, "paused before controlled EOF") { snapshot ->
                snapshot.state.firstFrameReady && snapshot.state.isPausedForUser() &&
                    !snapshot.state.isSeeking && !snapshot.isPlaying &&
                    abs(snapshot.positionMs - 2_000L) <= TARGET_TOLERANCE_MS
            }

            scenario.onActivity {
                val duration = player.state.value.durationMs
                assertTrue("Bundled sample must have room for a middle seek", duration > 2_500L)
                seedPendingSeek(player, issuedTargetMs = duration, pendingTargetMs = targetMs)
                // Only emulate arrival order: EOF from the issued tail seek arrives after
                // the user's newer target. The ensuing stop/reopen is entirely native.
                handleEvent(player, TestEvent(MediaPlayer.Event.EndReached))
                assertEquals(PlayerState.Phase.Preparing, player.state.value.phase)
                assertFalse(player.state.value.firstFrameReady)
            }

            val landed = awaitNative(scenario, player, "pending target $targetMs after EOF") { snapshot ->
                snapshot.state.firstFrameReady && snapshot.state.isPausedForUser() &&
                    !snapshot.state.isSeeking && !snapshot.isPlaying &&
                    abs(snapshot.positionMs - targetMs) <= TARGET_TOLERANCE_MS
            }
            assertNativeClockAdvances(scenario, player, landed.positionMs)
        }
    }

    private fun seedPendingSeek(
        player: VlcPlayerController,
        issuedTargetMs: Long,
        pendingTargetMs: Long,
    ) {
        val queue = field(player, "remoteSeeks")
        val submit = queue.javaClass.getDeclaredMethod("submit", java.lang.Long.TYPE)
        val poll = queue.javaClass.getDeclaredMethod("poll", java.lang.Long.TYPE)
        submit.isAccessible = true
        poll.isAccessible = true
        submit.invoke(queue, issuedTargetMs)
        assertEquals(issuedTargetMs, poll.invoke(queue, SystemClock.elapsedRealtime()))
        submit.invoke(queue, pendingTargetMs)
    }

    private fun listenerForCurrentInput(player: VlcPlayerController): MediaPlayer.EventListener {
        val generation = field(player, "inputGeneration") as Long
        val method = VlcPlayerController::class.java.getDeclaredMethod("eventListener", java.lang.Long.TYPE)
        method.isAccessible = true
        return method.invoke(player, generation) as MediaPlayer.EventListener
    }

    private fun handleEvent(player: VlcPlayerController, event: MediaPlayer.Event) {
        val method = VlcPlayerController::class.java.getDeclaredMethod("handleEvent", MediaPlayer.Event::class.java)
        method.isAccessible = true
        method.invoke(player, event)
    }

    private fun field(player: VlcPlayerController, name: String): Any {
        val field = VlcPlayerController::class.java.getDeclaredField(name)
        field.isAccessible = true
        return checkNotNull(field.get(player)) { "Controller field $name is unavailable" }
    }

    private fun assertNativeClockAdvances(
        scenario: ActivityScenario<PlayerSpikeActivity>,
        player: VlcPlayerController,
        fromMs: Long,
    ) {
        scenario.onActivity { player.play() }
        awaitNative(scenario, player, "native playback after recovery") { snapshot ->
            snapshot.state.phase == PlayerState.Phase.Playing && snapshot.isPlaying &&
                snapshot.positionMs >= fromMs + MINIMUM_CLOCK_ADVANCE_MS
        }
    }

    private fun withPlayer(
        block: (ActivityScenario<PlayerSpikeActivity>, VlcPlayerController) -> Unit,
    ) {
        ActivityScenario.launch(PlayerSpikeActivity::class.java).use { scenario ->
            lateinit var player: VlcPlayerController
            scenario.onActivity { activity ->
                val field = PlayerSpikeActivity::class.java.getDeclaredField("controller")
                field.isAccessible = true
                player = field.get(activity) as VlcPlayerController
            }
            awaitNative(scenario, player, "bundled video first frame") { snapshot ->
                snapshot.state.firstFrameReady && snapshot.state.isPausedForUser()
            }
            block(scenario, player)
        }
    }

    private fun awaitNative(
        scenario: ActivityScenario<PlayerSpikeActivity>,
        player: VlcPlayerController,
        label: String,
        predicate: (NativeSnapshot) -> Boolean,
    ): NativeSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var last: NativeSnapshot? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            // Read after the entire main-thread callback, avoiding an intermediate
            // optimistic state update before seek-preview has actually started.
            scenario.onActivity {
                val native = field(player, "mediaPlayer") as MediaPlayer
                last = NativeSnapshot(player.state.value, native.time, native.isPlaying)
            }
            val snapshot = checkNotNull(last)
            if (snapshot.state.phase == PlayerState.Phase.Error) {
                throw AssertionError("$label failed: $snapshot; ${deviceSummary()}")
            }
            if (predicate(snapshot)) return snapshot
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("$label timed out: $last; ${deviceSummary()}")
    }

    private fun PlayerState.isPausedForUser(): Boolean =
        phase == PlayerState.Phase.Ready || phase == PlayerState.Phase.Paused

    private fun deviceSummary(): String =
        "model=${Build.MODEL} hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT}"

    private data class NativeSnapshot(
        val state: PlayerState,
        val positionMs: Long,
        val isPlaying: Boolean,
    )

    private class TestEvent : MediaPlayer.Event {
        constructor(type: Int) : super(type)
        constructor(type: Int, value: Long) : super(type, value)
        constructor(type: Int, value: Float) : super(type, value)
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
        const val TARGET_TOLERANCE_MS = 500L
        const val MINIMUM_CLOCK_ADVANCE_MS = 150L
    }
}
