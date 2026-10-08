package com.framenest.feature.update

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private class Store : UpdateCheckStore {
        override var lastAttempt = 0L
        override var dismissedVersion = 0L
    }
    private class Backend : UpdateBackend {
        override val installedVersionCode = 10L
        override val supportedAbis = listOf("arm64-v8a")
        override val sdkInt = 36
        var checks = 0
        var downloads = 0
        var verifications = 0
        var discarded = 0
        var manifest = testManifest()
        var checkGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var verifyGate: CompletableDeferred<Unit>? = null
        var checkFailure = false
        var verificationFailure = false
        var progress: ((Long) -> Unit)? = null
        override suspend fun check(): UpdateManifest {
            checks++
            checkGate?.await()
            if (checkFailure) throw UpdateProblem("更新服务器暂时不可用")
            return manifest
        }
        override suspend fun download(manifest: UpdateManifest, asset: UpdateAsset, progress: (Long) -> Unit): File {
            downloads++
            this.progress = progress
            // Simulate a blocking transport that completes after cancellation.
            withContext(NonCancellable) { downloadGate?.await() }
            progress(100)
            return File("/tmp/test-update-$downloads.apk")
        }
        override suspend fun verify(file: File, manifest: UpdateManifest, asset: UpdateAsset) {
            verifications++
            verifyGate?.await()
            if (verificationFailure) throw UpdateProblem("安装包摘要不匹配，已拒绝安装")
        }
        override suspend fun discard(file: File) { discarded++ }
    }

    @Test fun `automatic errors are quiet rate limited and manual check retries`() = runTest {
        val backend = Backend().apply { checkFailure = true }
        val store = Store()
        val controller = UpdateController(backend, store, this) { 1_000 }
        controller.checkAutomatically(); runCurrent()
        assertEquals(UpdatePhase.Error, controller.state.value.phase)
        assertFalse(controller.state.value.dialogVisible)
        controller.checkAutomatically(); runCurrent()
        assertEquals(1, backend.checks)
        controller.checkManually(); runCurrent()
        assertEquals(2, backend.checks)
        assertTrue(controller.state.value.dialogVisible)
    }

    @Test fun `closing checking dialog prevents a late result from reopening it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { checkGate = gate }
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent()
        controller.checkManually()
        assertEquals(1, backend.checks)
        controller.dismiss(); gate.complete(Unit); runCurrent()
        assertEquals(UpdatePhase.Available, controller.state.value.phase)
        assertFalse(controller.state.value.dialogVisible)
    }

    @Test fun `dismissed version stays quiet on next automatic check but manual access remains`() = runTest {
        val backend = Backend()
        val store = Store().apply { dismissedVersion = 11 }
        val controller = UpdateController(backend, store, this) { 1000 }
        controller.checkAutomatically(); runCurrent()
        assertFalse(controller.state.value.dialogVisible)
        controller.checkManually()
        assertTrue(controller.state.value.dialogVisible)
        assertEquals(1, backend.checks)
    }

    @Test fun `a long lived dismissed result is refreshed after the daily interval`() = runTest {
        val backend = Backend()
        val store = Store()
        var now = 1000L
        val controller = UpdateController(backend, store, this) { now }
        controller.checkAutomatically(); runCurrent()
        controller.dismiss()
        now += UpdatePolicy.AUTO_CHECK_INTERVAL_MS
        backend.manifest = testManifest(12)
        controller.checkAutomatically(); runCurrent()
        assertEquals(2, backend.checks)
        assertEquals(12L, controller.state.value.manifest?.versionCode)
        assertTrue(controller.state.value.dialogVisible)
    }

    @Test fun `same or lower code is current even with a new label`() = runTest {
        val backend = Backend().apply { manifest = testManifest(10).copy(versionName = "99.0") }
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent()
        assertEquals(UpdatePhase.NoUpdate, controller.state.value.phase)
    }

    @Test fun `repeated download taps stay single flight cancellation ignores late progress and permits retry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { downloadGate = gate }
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent()
        controller.download(); controller.download(); runCurrent()
        assertEquals(1, backend.downloads)
        controller.cancelDownload()
        backend.progress?.invoke(99); gate.complete(Unit); runCurrent()
        assertEquals(UpdatePhase.Available, controller.state.value.phase)
        assertEquals(0L, controller.state.value.downloadedBytes)
        assertFalse(controller.state.value.dialogVisible)
        assertEquals(0, backend.verifications)
        controller.download(); runCurrent()
        assertEquals(2, backend.downloads)
        assertEquals(UpdatePhase.Ready, controller.state.value.phase)
    }

    @Test fun `failed verification discards APK and supports a fresh download`() = runTest {
        val backend = Backend().apply { verificationFailure = true }
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent(); controller.download(); runCurrent()
        assertEquals(UpdatePhase.Error, controller.state.value.phase)
        assertEquals(1, backend.discarded)
        backend.verificationFailure = false
        controller.retry(); runCurrent()
        assertEquals(UpdatePhase.Ready, controller.state.value.phase)
        assertEquals(2, backend.downloads)
    }

    @Test fun `background or player route prevents installer even for downloaded verified APK`() = runTest {
        val backend = Backend()
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent(); controller.download(); runCurrent()
        assertNull(controller.verifiedInstallFile())
        controller.setPresentationAllowed(true)
        assertNotNull(controller.verifiedInstallFile())
        assertEquals(2, backend.verifications)
        controller.installerOpened()
        assertNull(controller.verifiedInstallFile())
    }

    @Test fun `leaving and returning during preinstall verification invalidates the original install request`() = runTest {
        val backend = Backend()
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent(); controller.download(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        backend.verifyGate = gate
        controller.setPresentationAllowed(true)
        val pending = async { controller.verifiedInstallFile() }
        runCurrent()
        assertNull(controller.verifiedInstallFile()) // Repeated confirm while hashing.
        controller.setPresentationAllowed(false)
        controller.setPresentationAllowed(true)
        gate.complete(Unit); runCurrent()
        assertNull(pending.await())
        assertEquals(UpdatePhase.Ready, controller.state.value.phase)
    }

    @Test fun `install revalidation rejects an APK changed since download`() = runTest {
        val backend = Backend()
        val controller = UpdateController(backend, Store(), this)
        controller.checkManually(); runCurrent(); controller.download(); runCurrent()
        backend.verificationFailure = true
        controller.setPresentationAllowed(true)
        assertNull(controller.verifiedInstallFile())
        assertEquals(UpdatePhase.Error, controller.state.value.phase)
        assertEquals(1, backend.discarded)
    }
}
