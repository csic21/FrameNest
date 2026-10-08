package com.framenest.feature.player

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.framenest.R
import com.framenest.player.MediaSource
import com.framenest.player.PlayerState
import com.framenest.player.VlcPlayerController
import kotlin.math.abs
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real libVLC input/surface regressions; codec failures fail explicitly, including on AVDs. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlayerReplayRecoveryTest {

    @Test
    fun firstPrepare_doesNotCaptureUninitializedOpenSlesVolume_beforeFirstPlay() {
        withPlayer { scenario, player ->
            val restore = VlcPlayerController::class.java.getDeclaredField("seekPreviewRestoreVolume")
                .apply { isAccessible = true }
            var firstPositionMs = 0L
            scenario.onActivity {
                assertTrue("Bundled regression sample must include an audio track", player.state.value.audioTracks.any { it.id >= 0 })
                assertNull("Initial output volume is not a valid mute restore snapshot", restore.get(player))
                firstPositionMs = player.state.value.positionMs
                player.play()
                assertNull("First Play must not restore an uninitialized pre-play volume", restore.get(player))
            }
            awaitState(player, "first playback after audio-enabled prepare") { state ->
                state.phase == PlayerState.Phase.Playing && state.positionMs > firstPositionMs + 150L
            }
            // This exercises real decode/start ordering, not acoustic output capture.
            // Audible level and absence of a prepare burst still require device checks.
        }
    }

    @Test
    fun naturalEnd_replaysFromBeginningTwice_andReachesEndEachTime() {
        withPlayer { scenario, player ->
            playUntilNaturalEnd(scenario, player, "initial playback")
            repeat(2) { replay ->
                playUntilNaturalEnd(scenario, player, "replay ${replay + 1}")
            }
        }
    }

    @Test
    fun rapidPrepareReplacements_lastValidSourceWinsOverStaleErrors() {
        withPlayer { scenario, player ->
            scenario.onActivity {
                repeat(4) {
                    player.prepare(MediaSource.LocalFile(MISSING_LOCAL_FILE))
                    player.prepare(MediaSource.RawResource(R.raw.sample_h264))
                }
            }

            awaitState(player, "last valid source prepared") { state ->
                state.firstFrameReady && state.durationMs > 0L && state.isPausedForUser()
            }
            // Require actual playback through EOF, not just a Ready copied from an old input.
            playUntilNaturalEnd(scenario, player, "last valid source playback")
        }
    }

    @Test
    fun pauseDuringReplayPreparation_doesNotAutoplayAfterFirstFrame() {
        withPlayer { scenario, player ->
            playUntilNaturalEnd(scenario, player, "playback before paused replay")
            scenario.onActivity {
                player.play()
                assertTrue(
                    "Replay must reopen the ended input before it can play",
                    player.state.value.phase == PlayerState.Phase.Preparing,
                )
                player.pause()
            }

            val paused = awaitState(player, "paused replay first frame") { state ->
                state.firstFrameReady && state.isPausedForUser() && !state.isSeeking
            }
            val settledAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - settledAt < PAUSED_OBSERVATION_MS) {
                val state = player.state.value
                assertTrue("Paused replay started playing: ${state.summary()}", state.isPausedForUser())
                assertTrue(
                    "Paused replay clock kept advancing: ${paused.positionMs} -> ${state.positionMs}",
                    abs(state.positionMs - paused.positionMs) <= PAUSED_POSITION_TOLERANCE_MS,
                )
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    private fun withPlayer(
        block: (ActivityScenario<PlayerSpikeActivity>, VlcPlayerController) -> Unit,
    ) {
        ActivityScenario.launch(PlayerSpikeActivity::class.java).use { scenario ->
            lateinit var player: VlcPlayerController
            scenario.onActivity { activity ->
                // Read the spike's existing controller without adding a product/debug test API.
                val field = PlayerSpikeActivity::class.java.getDeclaredField("controller")
                field.isAccessible = true
                player = field.get(activity) as VlcPlayerController
            }
            awaitState(player, "bundled H.264 first frame") { state ->
                state.firstFrameReady && state.durationMs > 0L && state.isPausedForUser()
            }
            block(scenario, player)
        }
    }

    private fun playUntilNaturalEnd(
        scenario: ActivityScenario<PlayerSpikeActivity>,
        player: VlcPlayerController,
        label: String,
    ) {
        scenario.onActivity { player.play() }
        // play() can publish Playing/0 optimistically. A positive clock within the
        // first half, followed by further clock advancement, proves native input ran.
        val started = awaitState(player, "$label started near the beginning") { state ->
            state.phase == PlayerState.Phase.Playing &&
                state.durationMs > 0L &&
                state.positionMs in 1L..(state.durationMs / 2L)
        }
        awaitState(player, "$label native clock advanced") { state ->
            state.phase == PlayerState.Phase.Playing &&
                state.positionMs >= started.positionMs + MINIMUM_CLOCK_ADVANCE_MS
        }
        awaitState(player, "$label reached natural EOF") { state ->
            state.phase == PlayerState.Phase.Ended
        }
    }

    private fun awaitState(
        player: VlcPlayerController,
        label: String,
        predicate: (PlayerState) -> Boolean,
    ): PlayerState {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = player.state.value
            if (state.phase == PlayerState.Phase.Error) {
                throw AssertionError("$label failed: ${state.summary()}; ${deviceSummary()}")
            }
            if (predicate(state)) return state
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "$label timed out: ${player.state.value.summary()}; ${deviceSummary()}",
        )
    }

    private fun PlayerState.isPausedForUser(): Boolean =
        phase == PlayerState.Phase.Ready || phase == PlayerState.Phase.Paused

    private fun PlayerState.summary(): String =
        "phase=$phase firstFrame=$firstFrameReady position=$positionMs duration=$durationMs " +
            "buffering=$isBuffering seeking=$isSeeking error=${error?.code}"

    private fun deviceSummary(): String =
        "model=${Build.MODEL} hardware=${Build.HARDWARE} sdk=${Build.VERSION.SDK_INT}"

    private companion object {
        const val MISSING_LOCAL_FILE = "/__framenest_replay_regression_missing__.mp4"
        const val STATE_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
        const val MINIMUM_CLOCK_ADVANCE_MS = 100L
        const val PAUSED_OBSERVATION_MS = 1_500L
        const val PAUSED_POSITION_TOLERANCE_MS = 250L
    }
}
