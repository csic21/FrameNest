package com.framenest.feature.listen_translate.mt

import android.content.Context
import com.framenest.feature.listen_translate.ModelDownloadNetworkPolicy
import com.framenest.feature.listen_translate.asr.InvalidModelDownload
import com.framenest.feature.listen_translate.asr.atomicModelMove
import com.framenest.feature.listen_translate.asr.downloadVerifiedModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Explicitly requested, optional Hy-MT2 download. Constructing this store or asking for a verified
 * file never downloads anything. The UI must show [DISPLAY_NAME], [DOWNLOAD_BYTES] and the network
 * choice before calling [ensureInstalled]. This model is independent of ML Kit and its cache.
 *
 * Only an exact-size, SHA-256 verified file is atomically published in app-private storage. A unique
 * staging directory belongs to each install and is removed on failure/cancellation. There is no
 * cross-attempt resume; the shared ASR downloader's temporary prefix cannot become a final model.
 */
class HyMtModelInstaller internal constructor(
    private val root: File,
    private val spec: ModelFile = MODEL,
    private val requireNetwork: (allowMetered: Boolean) -> Unit,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val download: suspend (ModelFile, File, () -> Unit, (Long, Long) -> Unit) -> Unit =
        { model, destination, network, progress ->
            downloadVerifiedModel(model.url, destination, model.expectedBytes, model.sha256,
                requireNetwork = network, onBytes = progress)
        },
) {
    constructor(context: Context) : this(
        root = File(context.applicationContext.filesDir, "listen_models/hymt"),
        requireNetwork = { ModelDownloadNetworkPolicy.requireAllowed(context.applicationContext, it) },
    )

    private val modelFile: File get() = File(File(root, MODEL_ID), spec.name).absoluteFile

    /** Quick UI hint only. Never pass this result to native code as proof of integrity. */
    fun isDownloaded(): Boolean = isExactLengthRegularFile(modelFile)

    fun approximateBytes(): Long = if (root.isDirectory) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else 0L

    /**
     * Rehash before each engine startup, on IO and with cooperative cancellation. No stat-only cache:
     * same-length corruption or a replacement retaining the old timestamp must still be rejected.
     * Missing/corrupt/unreadable files return null; cancellation always propagates.
     */
    suspend fun verifiedModelFile(): File? = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            modelFile.takeIf { verify(it) }
        }
    }

    /** Call only after explicit download consent. No default mobile-data permission is implied. */
    suspend fun ensureInstalled(
        allowMeteredDownloads: Boolean,
        onProgress: (Float) -> Unit = {},
    ): File = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            val destination = modelFile
            if (verify(destination)) {
                onProgress(1f)
                return@withContext destination
            }
            check(root.isDirectory || root.mkdirs()) { "无法创建 Hy-MT2 模型目录" }
            // Keep the old file untouched until replacement commits; reserve room for the entire
            // new download plus headroom, even when a damaged installed file already occupies space.
            val available = usableSpace(root)
            check(available >= spec.expectedBytes && available - spec.expectedBytes >= DISK_HEADROOM_BYTES) {
                "存储空间不足：Hy-MT2 需要模型下载大小外加 64 MiB 可用空间"
            }
            currentCoroutineContext().ensureActive()
            requireNetwork(allowMeteredDownloads)
            val staging = Files.createTempDirectory(root.toPath(), ".hymt-install-").toFile()
            try {
                onProgress(0f)
                val stagedModel = File(staging, spec.name)
                download(spec, stagedModel, { requireNetwork(allowMeteredDownloads) }) { read, _ ->
                    // Fixed manifest total, never remote-supplied totals or content/URLs in progress.
                    val fraction = read.coerceIn(0L, spec.expectedBytes).toDouble() / spec.expectedBytes
                    onProgress((fraction * 0.95).toFloat())
                }
                currentCoroutineContext().ensureActive()
                if (!verify(stagedModel)) throw InvalidModelDownload("Hy-MT2 模型大小或 SHA-256 校验失败")
                currentCoroutineContext().ensureActive()
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) {
                    "无法创建 Hy-MT2 模型目录"
                }
                atomicModelMove(stagedModel, destination)
                onProgress(1f)
                destination
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: InvalidModelDownload) {
                throw invalid
            } catch (_: IOException) {
                // Network exceptions can contain signed redirect URLs. Do not surface their text or
                // retain a nested cause that UI diagnostics might serialize.
                throw IOException("Hy-MT2 模型下载失败，请检查网络和可用存储后重试")
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** Caller must close the engine first; serialize against installs and startup verification. */
    suspend fun deleteAll() = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            check(!root.exists() || root.deleteRecursively()) { "无法清除 Hy-MT2 模型" }
        }
    }

    private fun isExactLengthRegularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() == spec.expectedBytes

    private suspend fun verify(file: File): Boolean {
        currentCoroutineContext().ensureActive()
        if (!isExactLengthRegularFile(file)) return false
        return try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count.toLong() > spec.expectedBytes - total) return false
                    hash.update(bytes, 0, count)
                    total += count
                }
            }
            currentCoroutineContext().ensureActive()
            total == spec.expectedBytes && isExactLengthRegularFile(file) &&
                hash.digest().joinToString("") { "%02x".format(it) }.equals(spec.sha256, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    internal data class ModelFile(val name: String, val url: String, val expectedBytes: Long, val sha256: String) {
        init {
            require(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
            require(expectedBytes > 0L && sha256.matches(Regex("[a-fA-F0-9]{64}")))
        }
    }

    companion object {
        const val MODEL_ID = "hymt2-1.8b-q4_k_m"
        const val MODEL_FILE = "Hy-MT2-1.8B-Q4_K_M.gguf"
        const val DISPLAY_NAME = "Tencent Hy-MT2-1.8B (Q4_K_M)"
        const val REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
        /** Append runtime/prompt-policy version in the engine's cache identity, separately from ML Kit. */
        const val MODEL_VERSION = "$MODEL_ID-$REVISION"
        const val DOWNLOAD_BYTES = 1_133_080_448L // 1.06 GiB; excludes runtime working memory.
        const val SHA256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
        const val SOURCE_URL = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/tree/$REVISION"
        const val LICENSE_ID = "Apache-2.0"
        const val LICENSE_URL = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/$REVISION/LICENSE.txt"
        const val LICENSE_ASSET = "listen_models/hymt2-1.8b-LICENSE.txt"
        internal const val DISK_HEADROOM_BYTES = 64L * 1024 * 1024
        internal val MODEL = ModelFile(
            MODEL_FILE,
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/$REVISION/$MODEL_FILE",
            DOWNLOAD_BYTES,
            SHA256,
        )
        // One process-wide writer, including when UI/engine create separate installer instances.
        private val INSTALL_MUTEX = Mutex()
    }
}
