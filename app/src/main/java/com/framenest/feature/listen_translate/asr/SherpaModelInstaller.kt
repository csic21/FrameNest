package com.framenest.feature.listen_translate.asr

import android.content.Context
import com.framenest.feature.listen_translate.ModelDownloadNetworkPolicy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads the single SenseVoice-Small int8 pack into app-private storage.
 *
 * Layout: `filesDir/listen_models/sherpa/sensevoice-2024-07-17-int8/` holding
 * `model.int8.onnx` + `tokens.txt` + `.ready`. One pack covers zh/yue/en/ja/ko
 * (see [SherpaAsrEngine.supportedSourceLanguages]). Uninstall clears filesDir
 * (decision 0005); "清除听译模型" deletes this root via [deleteAll].
 *
 * Integrity mirrors [VoskModelInstaller]: revision-pinned HTTPS URLs (the
 * Hugging Face commit cannot move under us), exact byte sizes, SHA-256 for
 * the 239MB weight file, resumable `.part` downloads, and downloads gated by
 * [ModelDownloadNetworkPolicy] (default Wi-Fi/Ethernet only).
 */
class SherpaModelInstaller(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val root = File(context.applicationContext.filesDir, "listen_models/sherpa").also {
        it.mkdirs()
    }

    /** Pack dir when fully installed, or null (also null for unsupported langs). */
    fun modelDir(langTag: String): File? {
        if (!isSupported(langTag)) return null
        return packDir.takeIf { isReady(it) }
    }

    fun isInstalled(langTag: String): Boolean = modelDir(langTag) != null

    fun approximateBytes(): Long = dirSize(root)

    suspend fun ensureInstalled(
        langTag: String,
        allowMeteredDownloads: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): File = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            val lang = langTag.lowercase()
            if (!isSupported(lang)) {
                error("源语言「$lang」暂无离线 SenseVoice 支持（仅中/粤/英/日/韩）")
            }
            modelDir(lang)?.let {
                onProgress(1f)
                return@withContext it
            }
            val dir = packDir.also { it.mkdirs() }
            onProgress(0.02f)
            val totalBytes = FILES.sumOf { it.expectedBytes }
            var doneBytes = 0L
            FILES.forEach { spec ->
                downloadFile(spec, File(dir, spec.name), allowMeteredDownloads) { read, total ->
                    val overall = doneBytes + read.coerceAtMost(total)
                    val p = if (totalBytes > 0) {
                        0.02f + (overall.toDouble() / totalBytes.toDouble() * 0.95).toFloat()
                    } else {
                        0.4f
                    }
                    onProgress(p.coerceIn(0.02f, 0.97f))
                }
                doneBytes += spec.expectedBytes
            }
            if (!isCompletePack(dir)) {
                error("SenseVoice 模型安装后校验失败")
            }
            File(dir, READY_MARKER).writeText("$MODEL_ID\n$REVISION\n")
            onProgress(1f)
            dir
        }
    }

    suspend fun deleteAll() = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
        }
    }

    private val packDir: File get() = File(root, MODEL_ID)

    private fun isSupported(langTag: String): Boolean =
        SherpaAsrEngine.supportedSourceLanguages().contains(langTag.lowercase())

    private fun isReady(dir: File): Boolean = isCompletePack(dir)

    private fun isCompletePack(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (!File(dir, READY_MARKER).isFile) return false
        return FILES.all { spec ->
            val file = File(dir, spec.name)
            file.isFile && file.length() == spec.expectedBytes
        }
    }

    private suspend fun downloadFile(
        spec: ModelFile,
        dest: File,
        allowMeteredDownloads: Boolean,
        onBytes: (read: Long, total: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + PART_SUFFIX)

        if (dest.isFile && verifyFile(dest, spec)) {
            onBytes(spec.expectedBytes, spec.expectedBytes)
            return
        }
        if (dest.exists()) dest.delete()
        if (part.length() > spec.expectedBytes) part.delete()
        if (part.length() == spec.expectedBytes) {
            if (verifyFile(part, spec)) {
                promote(part, dest)
                onBytes(spec.expectedBytes, spec.expectedBytes)
                return
            }
            part.delete()
        }

        ModelDownloadNetworkPolicy.requireAllowed(appContext, allowMeteredDownloads)
        val requestedOffset = part.length()
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (requestedOffset > 0L) {
                setRequestProperty("Range", "bytes=$requestedOffset-")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("下载 SenseVoice 模型失败 HTTP $code")
            val plan = resumeDownloadPlan(requestedOffset, code)
            if (plan.append) {
                val contentRange = conn.getHeaderField("Content-Range").orEmpty()
                check(contentRange.startsWith("bytes $requestedOffset-")) {
                    "SenseVoice 模型服务器未返回预期的续传范围"
                }
            }
            val contentLength = conn.contentLengthLong
            val expectedFromResponse = if (contentLength > 0L) {
                plan.startBytes + contentLength
            } else {
                spec.expectedBytes
            }
            check(expectedFromResponse == spec.expectedBytes) {
                "SenseVoice 模型大小与固定清单不一致"
            }
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    FileOutputStream(part, plan.append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = plan.startBytes
                        onBytes(readTotal, spec.expectedBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = bis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            onBytes(readTotal, spec.expectedBytes)
                        }
                        out.fd.sync()
                        if (readTotal != spec.expectedBytes) {
                            error("SenseVoice 模型下载不完整：$readTotal/${spec.expectedBytes} bytes")
                        }
                    }
                }
            }
            if (!verifyFile(part, spec)) {
                part.delete()
                error("SenseVoice 模型完整性校验失败")
            }
            promote(part, dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyFile(file: File, spec: ModelFile): Boolean {
        if (file.length() != spec.expectedBytes) return false
        val sha = spec.sha256 ?: return true
        return sha256(file).equals(sha, ignoreCase = true)
    }

    private fun promote(source: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    data class ModelFile(
        val name: String,
        val url: String,
        val expectedBytes: Long,
        /** Null means size-only verification (tiny sidecar without published hash). */
        val sha256: String?,
    )

    companion object {
        const val MODEL_ID = "sensevoice-2024-07-17-int8"

        /** Cache/model label: intentionally distinct from any Vosk tag. */
        const val MODEL_VERSION = "sherpa-sensevoice-2024-07-17-int8"

        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"

        /** Pinned Hugging Face commit: the download can never move under us. */
        private const val REVISION = "2365baeacb507f821a0c8120fcee3d484dba7a07"
        private const val HF_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" +
                "/resolve/$REVISION"
        private const val READY_MARKER = ".ready"
        private const val PART_SUFFIX = ".part"
        private val INSTALL_MUTEX = Mutex()

        /**
         * Both files ship in
         * `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`.
         * Weight hash is the file content SHA-256 from the Hub blob view.
         */
        val FILES: List<ModelFile> = listOf(
            ModelFile(
                name = MODEL_FILE,
                url = "$HF_BASE/model.int8.onnx",
                expectedBytes = 239_233_841L,
                sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            ),
            ModelFile(
                name = TOKENS_FILE,
                url = "$HF_BASE/tokens.txt",
                expectedBytes = 315_894L,
                sha256 = null,
            ),
        )

        /** Download size shown before the user accepts (~240MB, one pack). */
        val APPROX_PACK_BYTES: Long = FILES.sumOf { it.expectedBytes }
    }
}
