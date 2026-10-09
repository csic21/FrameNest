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
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbjClient
import com.framenest.smb.GatedAuxiliarySmbClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
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

    @Test fun retryDuringDelayedTeardown_preserves42Seconds() = verifyOverlappingRetries(explicitZero = false)

    @Test fun retryAfterExplicitZero_doesNotRestoreOlder42Seconds() = verifyOverlappingRetries(explicitZero = true)

    private fun verifyOverlappingRetries(explicitZero: Boolean) {
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        withSession(retryTeardown = {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                check(firstRelease.await(5, TimeUnit.SECONDS))
            } else {
                secondEntered.countDown()
                check(secondRelease.await(5, TimeUnit.SECONDS))
            }
        }) { vm, player, _ ->
            try {
                readyPreview(player)
                instrumentation.runOnMainSync {
                    if (explicitZero) vm.seekTo(0L)
                    vm.retry()
                }
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                lateinit var oldOpen: Job
                instrumentation.runOnMainSync {
                    assertEquals(PlayerState.Phase.Idle, player.state.value.phase)
                    oldOpen = job(vm, "openJob")
                    vm.retry()
                }
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                secondRelease.countDown()
                awaitMain { player.state.value.phase == PlayerState.Phase.Preparing }
                instrumentation.runOnMainSync {
                    assertEquals(if (explicitZero) 0L else 42_000L, player.progressState.positionMs)
                }
                firstRelease.countDown()
                runBlocking { withTimeout(5_000) { oldOpen.join() } }
                instrumentation.runOnMainSync {
                    assertEquals(if (explicitZero) 0L else 42_000L, player.progressState.positionMs)
                }
            } finally {
                firstRelease.countDown()
                secondRelease.countDown()
            }
        }
    }

    @Test fun retiredDirectoryFailureCannotClearSuccessfulRetryOptionsAndSiblings() {
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        withSession(directoryFileNamesLoader = { _, _ ->
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                check(firstRelease.await(5, TimeUnit.SECONDS))
                error("retired scan failure")
            }
            secondEntered.countDown()
            check(secondRelease.await(5, TimeUnit.SECONDS))
            listOf("a-new.mkv", "movie.mkv", "movie.en.srt", "z-new.mkv")
        }) { vm, player, _ ->
            try {
                readyPreview(player)
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                lateinit var oldScan: Job
                instrumentation.runOnMainSync {
                    oldScan = job(vm, "directoryJob")
                    vm.retry()
                }
                awaitMain { player.state.value.phase == PlayerState.Phase.Preparing }
                readyPreview(player)
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { vm.selectSubtitleOff() }
                secondRelease.countDown()
                awaitMain { !vm.subtitleUiState.value.scanning && !vm.siblingNavState.value.loading }
                firstRelease.countDown()
                runBlocking { withTimeout(5_000) { oldScan.join() } }
                instrumentation.runOnMainSync {
                    assertEquals(listOf("movie.en.srt"), vm.subtitleUiState.value.externalOptions.map { it.fileName })
                    assertEquals("z-new.mkv", vm.siblingNavState.value.nextPath)
                    assertEquals(SubtitleSelectionKeys.OFF, vm.subtitleUiState.value.selectedKey)
                }
            } finally {
                firstRelease.countDown()
                secondRelease.countDown()
            }
        }
    }

    @Test fun exitAbortsBlockedDirectoryConnect() = verifyDirectoryExit(GatedAuxiliarySmbClient.Stage.CONNECT)

    @Test fun exitAbortsBlockedDirectoryList() = verifyDirectoryExit(GatedAuxiliarySmbClient.Stage.LIST)

    private fun verifyDirectoryExit(stage: GatedAuxiliarySmbClient.Stage) {
        val client = GatedAuxiliarySmbClient(stage)
        withSession(auxiliaryClientFactory = { client }) { vm, player, _ ->
            try {
                readyPreview(player)
                assertTrue(client.entered.await(5, TimeUnit.SECONDS))
                lateinit var scan: Job
                instrumentation.runOnMainSync {
                    scan = job(vm, "directoryJob")
                    vm.onLeave()
                }
                assertTrue(client.aborted.await(5, TimeUnit.SECONDS))
                assertFalse(client.abortThread === android.os.Looper.getMainLooper().thread)
                runBlocking { withTimeout(5_000) { scan.join() } }
                assertEquals(1, client.aborts.get())
            } finally { client.unblock.countDown() }
        }
    }

    @Test fun replacedListenPreparationAbortsOldConnectWithoutClosingNewTransport() {
        val old = GatedAuxiliarySmbClient(GatedAuxiliarySmbClient.Stage.CONNECT)
        val fresh = GatedAuxiliarySmbClient(GatedAuxiliarySmbClient.Stage.CONNECT)
        val calls = AtomicInteger()
        withSession(auxiliaryClientFactory = { if (calls.incrementAndGet() == 1) old else fresh }) { vm, _, _ ->
            try {
                // Leave native playback Preparing: no directory scan or ASR model download.
                instrumentation.runOnMainSync { vm.setListenTranslateEnabled(true) }
                assertTrue(old.entered.await(5, TimeUnit.SECONDS))
                lateinit var oldPreparation: Job
                instrumentation.runOnMainSync {
                    oldPreparation = job(vm, "listenPrepareJob")
                    vm.setListenSourceLang("en")
                }
                assertTrue(old.aborted.await(5, TimeUnit.SECONDS))
                assertTrue(fresh.entered.await(5, TimeUnit.SECONDS))
                runBlocking { withTimeout(5_000) { oldPreparation.join() } }
                assertEquals(1, old.aborts.get())
                assertEquals(0, fresh.aborts.get())
                lateinit var freshPreparation: Job
                instrumentation.runOnMainSync {
                    freshPreparation = job(vm, "listenPrepareJob")
                    vm.onLeave()
                }
                assertTrue(fresh.aborted.await(5, TimeUnit.SECONDS))
                runBlocking { withTimeout(5_000) { freshPreparation.join() } }
                assertEquals(1, fresh.aborts.get())
                assertFalse(fresh.abortThread === android.os.Looper.getMainLooper().thread)
            } finally {
                old.unblock.countDown()
                fresh.unblock.countDown()
            }
        }
    }

    private fun job(vm: PlayerViewModel, field: String): Job =
        PlayerViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(vm) as Job

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { done = condition() }
            if (!done) Thread.sleep(10)
        }
        assertTrue("Condition did not become true", done)
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
        retryTeardown: suspend () -> Unit = {},
        auxiliaryClientFactory: (() -> SmbClient)? = null,
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
                retryTeardown = retryTeardown,
                auxiliaryClientFactory = auxiliaryClientFactory ?: { SmbjClient() },
            )
            // Suppress network listing unless this test supplies a controlled scan.
            for (name in listOf("subtitleBootstrapDone", "siblingBootstrapDone")) {
                PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(vm, directoryFileNamesLoader == null && auxiliaryClientFactory == null)
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
                        .set(vm, directoryFileNamesLoader == null && auxiliaryClientFactory == null)
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
