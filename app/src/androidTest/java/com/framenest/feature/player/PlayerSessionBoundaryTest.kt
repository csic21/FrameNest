package com.framenest.feature.player

import android.app.Application
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackIdentity
import com.framenest.core.model.PlaybackRequest
import com.framenest.data.history.PlaybackHistoryDao
import com.framenest.data.history.PlaybackHistoryEntity
import com.framenest.data.history.PlaybackHistoryRepository
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.AppDatabase
import com.framenest.feature.subtitle.SubtitleSelectionKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.framenest.player.MediaSource
import com.framenest.player.PlayerController
import com.framenest.player.PlayerState
import com.framenest.player.VlcPlayerController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * Real ViewModel + VLC controller + Room history, with controlled native event arrival.
 * No surface is attached, so example.invalid is never opened. These are lifecycle/
 * persistence regressions, not proof of decoded output or SMB device performance.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSessionBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    @Test fun readyThenBack_keepsHeldResumeAndCleansSessionOnce() = withSession { vm, player, history ->
        readyPreview(player)
        instrumentation.runOnMainSync {
            assertEquals(0L, player.state.value.positionMs)
            assertEquals(42_000L, player.progressState.positionMs)
            vm.onLeave()
            vm.onLeave()
            vm.onLeaveOrBackground()
            vm.onReturnToForeground()
            vm.play()
            assertEquals(PlayerState.Phase.Idle, player.state.value.phase)
            assertTrue(vm.scrubPreviewFrames.value.isEmpty())
            assertTrue(vm.subtitleUiState.value.externalOptions.isEmpty())
        }
        assertEquals(42_000L, savedPosition(history, vm))
    }

    @Test fun readyThenBackground_keepsResume_butExplicitZeroOnReturnSavesZero() = withSession { vm, player, history ->
        readyPreview(player)
        instrumentation.runOnMainSync { vm.onLeaveOrBackground() }
        assertEquals(42_000L, savedPosition(history, vm))
        instrumentation.runOnMainSync {
            vm.onReturnToForeground()
            // User seeks after returning; persistence must prefer this explicit
            // zero rather than a "keep the largest position" workaround.
            vm.seekTo(0L)
            assertEquals(0L, player.progressState.positionMs)
            vm.onLeave()
        }
        assertEquals(0L, savedPosition(history, vm))
    }

    @Test fun failedPauseWrite_isRetriedBySamePositionExitSnapshot() = withSession(failFirstWrite = true) { vm, player, history ->
        readyPreview(player)
        instrumentation.runOnMainSync {
            vm.seekTo(0L)
            vm.pause()
            vm.onLeave()
        }
        assertEquals(0L, savedPosition(history, vm))
    }

    @Test fun retiredNativeInputCannotPublishAfterExitOrIntoNextVideo() {
        withSession("A.mkv") { a, old, _ ->
            readyPreview(old)
            lateinit var callback: MediaPlayer.EventListener
            instrumentation.runOnMainSync {
                callback = inputListener(old)
                a.onLeave()
            }
            withSession("B.mkv") { _, current, _ ->
                instrumentation.runOnMainSync {
                    callback.onEvent(TestEvent(MediaPlayer.Event.Playing))
                    callback.onEvent(TestEvent(MediaPlayer.Event.TimeChanged, 999_000L))
                    callback.onEvent(TestEvent(MediaPlayer.Event.Vout, 1L))
                    callback.onEvent(TestEvent(MediaPlayer.Event.EncounteredError))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(PlayerState.Phase.Idle, old.state.value.phase)
                    assertEquals(PlayerState.Phase.Preparing, current.state.value.phase)
                    assertEquals(42_000L, current.progressState.positionMs)
                }
            }
        }
    }

    @Test fun playThenStaleZeroClockCannotEraseUnsettledSmbResume() = withSession { vm, player, history ->
        readyPreview(player)
        instrumentation.runOnMainSync {
            // Mark the product intent without relying on the device granting focus.
            vm.play()
            player.play()
            event(player, TestEvent(MediaPlayer.Event.TimeChanged, 0L))
            assertEquals(42_000L, player.progressState.positionMs)
            vm.onLeave()
        }
        assertEquals(42_000L, savedPosition(history, vm))
    }

    @Test fun interruptedForegroundRepaintCannotReplaceHeldSmbResumeWithZero() = withSession { _, player, _ ->
        readyPreview(player)
        instrumentation.runOnMainSync {
            player.repaintCurrentFrame()
            player.pause() // Another ON_STOP interrupts the internal repaint at zero.
            assertEquals(42_000L, player.progressState.positionMs)
            player.play() // The first real Play must still apply held history.
            assertEquals(42_000L, player.progressState.positionMs)
        }
    }

    @Test fun prepareDoesNotInvalidateAQueuedForegroundSurfaceReadyCallback() {
        lateinit var player: VlcPlayerController
        var readyCallbacks = 0
        try {
            instrumentation.runOnMainSync {
                player = VlcPlayerController(application)
                player.attachVideoLayout(FrameLayout(application))
                player.rebindVideoOutput { readyCallbacks++ }
                val callback = VlcPlayerController::class.java.getDeclaredField("foregroundVoutCallback")
                    .apply { isAccessible = true }.get(player) as IVLCVout.Callback
                val native = VlcPlayerController::class.java.getDeclaredField("mediaPlayer")
                    .apply { isAccessible = true }.get(player) as MediaPlayer
                callback.onSurfacesCreated(native.vlcVout) // Posts readiness to main.
                player.prepare(MediaSource.RawResource(R.raw.sample_h264)) // Replaces input first.
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(1, readyCallbacks) }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test fun canceledHistoryReadCannotPrepareRetiredSession_orBlockFreshVideoAfterItCompletes() {
        val db = AppDatabase.createInMemory(application)
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val gateDao = object : PlaybackHistoryDao by db.playbackHistoryDao() {
            override suspend fun get(serverId: String, share: String, path: String): PlaybackHistoryEntity? {
                started.complete(Unit)
                // Simulates a read-only external operation that ignores cancellation.
                withContext(NonCancellable) { unblock.await() }
                return null
            }
        }
        var a: PlayerViewModel? = null
        var b: PlayerViewModel? = null
        lateinit var old: CountingPlayer
        lateinit var fresh: CountingPlayer
        fun request(path: String) = PlaybackRequest(
            PlaybackIdentity("fn54-test", "local", path), path,
            PlaybackDataSource.LocalRawResource(R.raw.sample_h264),
        )
        try {
            instrumentation.runOnMainSync {
                old = CountingPlayer(VlcPlayerController(application))
                a = PlayerViewModel(
                    application, request("A.mp4"), PlaybackHistoryRepository(gateDao),
                    ListenTranslateRepository(db.listenTranslateDao()),
                    controllerFactory = { old }, browseSessionReleaser = {},
                )
            }
            runBlocking { withTimeout(5_000L) { started.await() } }
            instrumentation.runOnMainSync {
                a!!.onLeave()
                a!!.onLeave()
                fresh = CountingPlayer(VlcPlayerController(application))
                b = PlayerViewModel(
                    application, request("B.mp4"), PlaybackHistoryRepository(db.playbackHistoryDao()),
                    ListenTranslateRepository(db.listenTranslateDao()),
                    controllerFactory = { fresh }, browseSessionReleaser = {},
                )
            }
            unblock.complete(Unit)
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = fresh.prepares == 1 }
                if (!ready) Thread.sleep(10)
            }
            instrumentation.runOnMainSync {
                assertEquals(0, old.prepares)
                assertEquals(1, old.releases)
                assertEquals(1, fresh.prepares)
                b!!.onLeave()
            }
        } finally {
            unblock.complete(Unit)
            instrumentation.runOnMainSync {
                a?.onLeave()
                b?.onLeave()
            }
            runBlocking { PlaybackProgressPersistence.Shared.afterSaves { Unit } }
            db.close()
        }
    }

    private class CountingPlayer(private val delegate: VlcPlayerController) : PlayerController by delegate {
        var prepares = 0
        var releases = 0
        override fun prepare(source: MediaSource, startPositionMs: Long) {
            prepares++
            delegate.prepare(source, startPositionMs)
        }
        override fun release() {
            releases++
            delegate.release()
        }
    }

    @Test fun subtitleOffDuringDirectoryScan_stillPublishesSiblingsAndOptions() {
        verifyManualSelectionDuringDirectoryScan(embeddedTrack = null)
    }

    @Test fun embeddedSubtitleDuringDirectoryScan_stillPublishesSiblingsAndOptions() {
        verifyManualSelectionDuringDirectoryScan(embeddedTrack = 7)
    }

    @Test fun subtitleOffDuringFailedDirectoryScan_finishesBothLoadingStates() {
        verifyManualSelectionDuringDirectoryScan(embeddedTrack = null, failListing = true)
    }

    private fun verifyManualSelectionDuringDirectoryScan(embeddedTrack: Int?, failListing: Boolean = false) {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val listing: (String, String) -> List<String> = { _, _ ->
            entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS)) { "controlled scan was not released" }
            if (failListing) error("controlled directory failure")
            listOf("a.mkv", "movie.mkv", "movie.en.srt", "z.mkv")
        }
        withSession(directoryFileNamesLoader = listing) { vm, player, _ ->
            try {
                readyPreview(player)
                assertTrue("Directory scan never started", entered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertTrue(vm.siblingNavState.value.loading)
                    assertTrue(vm.subtitleUiState.value.scanning)
                    if (embeddedTrack == null) vm.selectSubtitleOff() else vm.selectEmbeddedSubtitle(embeddedTrack)
                }
                unblock.countDown()
                val deadline = SystemClock.elapsedRealtime() + 5_000L
                var finished = false
                while (!finished && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        finished = !vm.siblingNavState.value.loading && !vm.subtitleUiState.value.scanning
                    }
                    if (!finished) Thread.sleep(10)
                }
                instrumentation.runOnMainSync {
                    assertTrue("Manual choice stranded directory loading", finished)
                    assertFalse(vm.siblingNavState.value.loading)
                    assertFalse(vm.subtitleUiState.value.scanning)
                    val expectedSelection = embeddedTrack?.let { SubtitleSelectionKeys.embedded(it) }
                        ?: SubtitleSelectionKeys.OFF
                    assertEquals(expectedSelection, vm.subtitleUiState.value.selectedKey)
                    if (failListing) {
                        assertFalse(vm.siblingNavState.value.hasNext)
                        assertTrue(vm.subtitleUiState.value.externalOptions.isEmpty())
                    } else {
                        assertEquals("a.mkv", vm.siblingNavState.value.previousPath)
                        assertEquals("z.mkv", vm.siblingNavState.value.nextPath)
                        assertEquals(listOf("movie.en.srt"), vm.subtitleUiState.value.externalOptions.map { it.fileName })
                    }
                }
            } finally {
                unblock.countDown()
            }
        }
    }

    private fun readyPreview(player: VlcPlayerController) = instrumentation.runOnMainSync {
        event(player, TestEvent(MediaPlayer.Event.Opening))
        event(player, TestEvent(MediaPlayer.Event.LengthChanged, 600_000L))
        event(player, TestEvent(MediaPlayer.Event.Vout, 1L))
        event(player, TestEvent(MediaPlayer.Event.TimeChanged, 0L))
        assertEquals(PlayerState.Phase.Ready, player.state.value.phase)
    }

    private fun savedPosition(history: PlaybackHistoryRepository, vm: PlayerViewModel): Long = runBlocking {
        PlaybackProgressPersistence.Shared.afterSaves { history.get(vm.identity)!!.positionMs }
    }

    private fun withSession(
        path: String = "movie.mkv",
        failFirstWrite: Boolean = false,
        directoryFileNamesLoader: ((String, String) -> List<String>)? = null,
        block: (PlayerViewModel, VlcPlayerController, PlaybackHistoryRepository) -> Unit,
    ) {
        val db = AppDatabase.createInMemory(application)
        val base = db.playbackHistoryDao()
        var rejectNextWrite = failFirstWrite
        val dao = object : PlaybackHistoryDao by base {
            override suspend fun upsert(entity: PlaybackHistoryEntity) {
                if (rejectNextWrite) {
                    rejectNextWrite = false
                    throw IllegalStateException("controlled first write failure")
                }
                base.upsert(entity)
            }
        }
        val history = PlaybackHistoryRepository(dao)
        val identity = PlaybackIdentity("fn54-test", "media", path)
        runBlocking { PlaybackHistoryRepository(base).saveProgress(identity, path, 42_000L, 600_000L) }
        lateinit var player: VlcPlayerController
        lateinit var vm: PlayerViewModel
        instrumentation.runOnMainSync {
            player = VlcPlayerController(application)
            vm = PlayerViewModel(
                application,
                PlaybackRequest(identity, path, PlaybackDataSource.DirectSmbUrl("example.invalid", "media", path)),
                history,
                ListenTranslateRepository(db.listenTranslateDao()),
                controllerFactory = { player },
                browseSessionReleaser = {},
                directoryFileNamesLoader = directoryFileNamesLoader,
            )
            // Suppress network listing unless this test supplies a controlled scan.
            for (name in listOf("subtitleBootstrapDone", "siblingBootstrapDone")) {
                PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(vm, directoryFileNamesLoader == null)
            }
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var prepared = false
            while (!prepared && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { prepared = player.state.value.phase == PlayerState.Phase.Preparing }
                if (!prepared) Thread.sleep(10)
            }
            assertTrue("Session never prepared", prepared)
            // Preparing resets subtitle bootstrap; configure it again before Ready.
            instrumentation.runOnMainSync {
                for (name in listOf("subtitleBootstrapDone", "siblingBootstrapDone")) {
                    PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                        .set(vm, directoryFileNamesLoader == null)
                }
            }
            block(vm, player, history)
        } finally {
            instrumentation.runOnMainSync { vm.onLeave() }
            runBlocking { PlaybackProgressPersistence.Shared.afterSaves { Unit } }
            db.close()
        }
    }

    private fun event(player: VlcPlayerController, event: MediaPlayer.Event) {
        VlcPlayerController::class.java.getDeclaredMethod("handleEvent", MediaPlayer.Event::class.java)
            .apply { isAccessible = true }.invoke(player, event)
    }

    private fun inputListener(player: VlcPlayerController): MediaPlayer.EventListener {
        val generation = VlcPlayerController::class.java.getDeclaredField("inputGeneration")
            .apply { isAccessible = true }.getLong(player)
        return VlcPlayerController::class.java.getDeclaredMethod("eventListener", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(player, generation) as MediaPlayer.EventListener
    }

    private class TestEvent : MediaPlayer.Event {
        constructor(type: Int) : super(type)
        constructor(type: Int, value: Long) : super(type, value)
    }
}
