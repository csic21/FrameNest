package com.framenest.feature.listen_translate.mt

import com.framenest.feature.listen_translate.asr.downloadVerifiedModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HyMtModelInstallerTest {
    private val payload = "a small verified model".toByteArray()
    private val spec = HyMtModelInstaller.ModelFile(
        "fixture.gguf", "https://huggingface.co/test/resolve/pinned/fixture.gguf",
        payload.size.toLong(), sha256(payload),
    )

    private fun root(): File = Files.createTempDirectory("framenest-hymt-test-").toFile()
    private fun destination(root: File): File = File(File(root, HyMtModelInstaller.MODEL_ID), spec.name)
    private fun stages(root: File): List<File> = root.listFiles().orEmpty().filter { it.name.startsWith(".hymt-install-") }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun metadataPinsExactOfficialFileAndIndependentCacheVersion() {
        assertEquals("Hy-MT2-1.8B-Q4_K_M.gguf", HyMtModelInstaller.MODEL_FILE)
        assertEquals(1_133_080_448L, HyMtModelInstaller.DOWNLOAD_BYTES)
        assertEquals("a0c709d9fac510f2c807aa3af52872340dc37a4a", HyMtModelInstaller.REVISION)
        assertEquals("dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", HyMtModelInstaller.SHA256)
        assertEquals("Apache-2.0", HyMtModelInstaller.LICENSE_ID)
        assertTrue(HyMtModelInstaller.MODEL.url.contains("/resolve/${HyMtModelInstaller.REVISION}/"))
        assertTrue(HyMtModelInstaller.MODEL_VERSION.contains(HyMtModelInstaller.REVISION))
        assertFalse(HyMtModelInstaller.MODEL_VERSION.contains("mlkit", ignoreCase = true))
        assertFalse(HyMtModelInstaller.MODEL.url.contains("/main/"))
    }

    @Test fun manifestAndBundledLicenseMatchCompiledPin() {
        val assetRoot = listOf(File("src/main/assets/listen_models"), File("app/src/main/assets/listen_models"))
            .first { it.isDirectory }
        val manifest = File(assetRoot, "hymt2-1.8b-q4_k_m.json").readText()
        assertTrue(manifest.contains("\"automaticDownload\": false"))
        assertTrue(manifest.contains("\"optInRequired\": true"))
        assertTrue(manifest.contains(HyMtModelInstaller.MODEL.url))
        assertTrue(manifest.contains(HyMtModelInstaller.MODEL_VERSION))
        assertTrue(manifest.contains("\"bytes\": ${HyMtModelInstaller.DOWNLOAD_BYTES}"))
        assertTrue(manifest.contains(HyMtModelInstaller.SHA256))
        val license = File(assetRoot, "hymt2-1.8b-LICENSE.txt").readBytes()
        assertEquals("a1d52d448f81c584a47c583e19dfab2d3851c7c84431b07baee093c1113ed114", sha256(license))
        assertTrue(manifest.contains(sha256(license)))
    }

    @Test fun startupChecksNeverDownloadAndPresenceDoesNotMeanVerified() = runBlocking {
        val root = root()
        try {
            var networkCalls = 0
            var downloadCalls = 0
            val store = HyMtModelInstaller(root, spec, requireNetwork = { networkCalls++ }, download = { _, _, _, _ -> downloadCalls++ })
            assertFalse(store.isDownloaded())
            assertNull(store.verifiedModelFile())
            val final = destination(root).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(payload.size)) }
            assertTrue(store.isDownloaded())
            assertNull(store.verifiedModelFile())
            final.writeBytes(payload)
            assertEquals(final.absoluteFile, store.verifiedModelFile())
            val previousModified = final.lastModified()
            final.writeBytes(ByteArray(payload.size))
            assertTrue(final.setLastModified(previousModified))
            assertTrue(store.isDownloaded())
            assertNull(store.verifiedModelFile()) // Same size and timestamp cannot reuse an old digest.
            assertEquals(0, networkCalls)
            assertEquals(0, downloadCalls)
        } finally { root.deleteRecursively() }
    }

    @Test fun validFileSkipsNetworkAndFullDownloadOnRepeatedInstall() = runBlocking {
        val root = root()
        try {
            val final = destination(root).apply { parentFile!!.mkdirs(); writeBytes(payload) }
            val store = HyMtModelInstaller(root, spec, requireNetwork = { error("Must not use network") },
                usableSpace = { 0L }, download = { _, _, _, _ -> error("Must not download") })
            val progress = mutableListOf<Float>()
            assertEquals(final.absoluteFile, store.ensureInstalled(false, progress::add))
            assertEquals(listOf(1f), progress)
            assertEquals(payload.size.toLong(), store.approximateBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun verifiedDownloadPublishesLastAndProgressUsesOnlyFixedSize() = runBlocking {
        val root = root()
        try {
            val progress = mutableListOf<Float>()
            val permissions = mutableListOf<Boolean>()
            val store = HyMtModelInstaller(root, spec, requireNetwork = { permissions += it },
                download = { _, staged, network, onBytes ->
                    network()
                    assertFalse(destination(root).exists())
                    assertTrue(staged.parentFile!!.name.startsWith(".hymt-install-"))
                    onBytes(-100L, Long.MAX_VALUE)
                    onBytes(Long.MAX_VALUE, 1L)
                    staged.writeBytes(payload)
                    assertFalse(destination(root).exists())
                })
            val final = store.ensureInstalled(true, progress::add)
            assertTrue(final.isAbsolute)
            assertArrayEquals(payload, final.readBytes())
            assertEquals(final, store.verifiedModelFile())
            assertTrue(permissions.isNotEmpty() && permissions.all { it })
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.dropLast(1).all { it.isFinite() && it in 0f..0.95f })
            assertTrue(stages(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongLengthOrShaNeverPublishesAndCleansTaskOwnedFiles() = runBlocking {
        for (contents in listOf(ByteArray(payload.size), payload.dropLast(1).toByteArray(), payload + byteArrayOf(1))) {
            val root = root()
            try {
                val unrelated = File(root, "unrelated.part").apply { writeText("keep") }
                val store = HyMtModelInstaller(root, spec, requireNetwork = {}, download = { _, staged, _, _ ->
                    staged.writeBytes(contents)
                    File(staged.path + ".part").writeText("unfinished")
                })
                assertTrue(runCatching { store.ensureInstalled(false) }.isFailure)
                assertFalse(destination(root).exists())
                assertNull(store.verifiedModelFile())
                assertTrue(stages(root).isEmpty())
                assertEquals("keep", unrelated.readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun networkFailureDoesNotExposeSignedUrlOrLeaveFinalOrPartial() = runBlocking {
        val root = root()
        try {
            val store = HyMtModelInstaller(root, spec, requireNetwork = {}, download = { _, staged, _, _ ->
                File(staged.path + ".part").writeText("part")
                throw IOException("https://cdn.hf.co/model?token=secret&signature=secret")
            })
            val failure = runCatching { store.ensureInstalled(false) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure.toString().contains("https://"))
            assertFalse(failure.toString().contains("secret"))
            assertNull(failure!!.cause)
            assertFalse(destination(root).exists())
            assertTrue(stages(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun insufficientDiskFailsBeforeAnyNetworkOrStaging() = runBlocking {
        val root = root()
        try {
            var calls = 0
            val store = HyMtModelInstaller(root, spec, requireNetwork = { calls++ },
                usableSpace = { spec.expectedBytes + HyMtModelInstaller.DISK_HEADROOM_BYTES - 1 },
                download = { _, _, _, _ -> calls++ })
            assertTrue(runCatching { store.ensureInstalled(false) }.isFailure)
            assertEquals(0, calls)
            assertFalse(destination(root).exists())
            assertTrue(stages(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun deniedNetworkPolicyRunsBeforeDownloadAndPreservesFlag() = runBlocking {
        val root = root()
        try {
            val store = HyMtModelInstaller(root, spec, requireNetwork = { allowMetered ->
                assertFalse(allowMetered)
                error("Wi-Fi required")
            }, download = { _, _, _, _ -> error("Must not download") })
            val failure = runCatching { store.ensureInstalled(false) }.exceptionOrNull()
            assertEquals("Wi-Fi required", failure!!.message)
            assertFalse(destination(root).exists())
            assertTrue(stages(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellingActiveInstallRemovesOnlyItsOwnStagingAndReleasesMutex() = runBlocking {
        val root = root()
        try {
            val started = CompletableDeferred<Unit>()
            val unrelated = File(root, ".hymt-install-unrelated").apply { mkdirs() }
            File(unrelated, "owned-by-other-task").writeText("keep")
            val attempts = AtomicInteger()
            val store = HyMtModelInstaller(root, spec, requireNetwork = {}, download = { _, staged, _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    File(staged.path + ".part").writeText("part")
                    started.complete(Unit)
                    awaitCancellation()
                }
                staged.writeBytes(payload)
            })
            val job = launch { store.ensureInstalled(false) }
            withTimeout(5_000) { started.await() }
            job.cancelAndJoin()
            assertFalse(destination(root).exists())
            assertEquals(listOf(unrelated), stages(root))
            assertEquals("keep", File(unrelated, "owned-by-other-task").readText())
            withTimeout(5_000) { assertNotNull(store.ensureInstalled(false)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun differentInstallerInstancesShareOneInstallAndCancelledWaiterCannotDeleteIt() = runBlocking {
        val root = root()
        try {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val downloadCount = AtomicInteger()
            val download: suspend (HyMtModelInstaller.ModelFile, File, () -> Unit, (Long, Long) -> Unit) -> Unit =
                { _, staged, _, _ ->
                    downloadCount.incrementAndGet()
                    File(staged.path + ".part").writeText("part")
                    started.complete(Unit)
                    finish.await()
                    staged.writeBytes(payload)
                }
            val first = HyMtModelInstaller(root, spec, requireNetwork = {}, download = download)
            val second = HyMtModelInstaller(root, spec, requireNetwork = {}, download = download)
            val firstJob = async { first.ensureInstalled(false) }
            withTimeout(5_000) { started.await() }
            val cancelledWaiter = launch { second.ensureInstalled(false) }
            cancelledWaiter.cancelAndJoin()
            assertEquals(1, stages(root).size)
            val secondJob = async { second.ensureInstalled(false) }
            finish.complete(Unit)
            withTimeout(5_000) { assertEquals(firstJob.await(), secondJob.await()) }
            assertEquals(1, downloadCount.get())
            assertTrue(stages(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedReplacementLeavesOldFileUntouchedAndUnverified() = runBlocking {
        val root = root()
        try {
            val previous = destination(root).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(payload.size)) }
            val store = HyMtModelInstaller(root, spec, requireNetwork = {}, download = { _, _, _, _ -> throw IOException() })
            assertTrue(runCatching { store.ensureInstalled(false) }.isFailure)
            assertArrayEquals(ByteArray(payload.size), previous.readBytes())
            assertNull(store.verifiedModelFile())
        } finally { root.deleteRecursively() }
    }

    @Test fun finalSymlinkIsNotAcceptedAsVerifiedModel() = runBlocking {
        val root = root()
        try {
            val real = File(root, "real").apply { writeBytes(payload) }
            val final = destination(root).apply { parentFile!!.mkdirs() }
            Files.createSymbolicLink(final.toPath(), real.toPath())
            val store = HyMtModelInstaller(root, spec, requireNetwork = {})
            assertFalse(store.isDownloaded())
            assertNull(store.verifiedModelFile())
        } finally { root.deleteRecursively() }
    }

    @Test fun productionDownloaderSeamStartsAtZeroAndChecksFakeHttpPayload() = runBlocking {
        for (contents in listOf(payload, ByteArray(payload.size), payload.dropLast(1).toByteArray())) {
            val root = root()
            try {
                var disconnected = false
                val store = HyMtModelInstaller(root, spec, requireNetwork = {},
                    download = { model, staged, network, progress ->
                        downloadVerifiedModel(model.url, staged, model.expectedBytes, model.sha256, network, progress) { _, offset ->
                            assertEquals(0L, offset)
                            object : HttpURLConnection(URL("https://huggingface.co/fixture")) {
                                override fun disconnect() { disconnected = true }
                                override fun usingProxy() = false
                                override fun connect() = Unit
                                override fun getResponseCode() = 200
                                override fun getContentLengthLong() = -1L
                                override fun getInputStream() = ByteArrayInputStream(contents)
                            }
                        }
                    })
                val result = runCatching { store.ensureInstalled(false) }
                assertEquals(contents.contentEquals(payload), result.isSuccess)
                assertEquals(contents.contentEquals(payload), destination(root).exists())
                assertTrue(disconnected)
                assertTrue(stages(root).isEmpty())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun explicitDeleteClearsOnlyThisModelStore() = runBlocking {
        val parent = root()
        try {
            val root = File(parent, "hymt").apply { mkdirs() }
            val unrelated = File(parent, "mlkit").apply { mkdirs() }
            destination(root).apply { parentFile!!.mkdirs(); writeBytes(payload) }
            val store = HyMtModelInstaller(root, spec, requireNetwork = {})
            store.deleteAll()
            assertFalse(store.isDownloaded())
            assertNull(store.verifiedModelFile())
            assertTrue(unrelated.isDirectory)
        } finally { parent.deleteRecursively() }
    }

    @Test fun explicitDeleteRefusesAnySharedModelLeaseUntilActuallyReleased() = runBlocking {
        val root = root()
        val lease = HyMtModelUseGuard.shared.acquire()
        try {
            val final = destination(root).apply { parentFile!!.mkdirs(); writeBytes(payload) }
            val store = HyMtModelInstaller(root, spec, requireNetwork = {})
            assertTrue(runCatching { store.deleteAll() }.isFailure)
            assertArrayEquals(payload, final.readBytes())
            lease.close()
            store.deleteAll()
            assertFalse(final.exists())
        } finally { lease.close(); root.deleteRecursively() }
    }
}
