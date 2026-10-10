package com.framenest.feature.listen_translate.asr

import android.content.Context
import com.framenest.feature.listen_translate.ModelDownloadNetworkPolicy
import java.io.File
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
 * both the weight and token files, resumable `.part` downloads, and downloads gated by
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
            modelDir(lang)?.takeIf { verifyPack(it) }?.let {
                onProgress(1f)
                return@withContext it
            }
            File(packDir, READY_MARKER).delete()
            val dir = packDir
            installSherpaPack(dir, FILES, "$MODEL_ID\n$REVISION\n", onProgress) { spec, dest, progress ->
                downloadFile(spec, dest, allowMeteredDownloads, progress)
            }
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
        onBytes: (Long, Long) -> Unit,
    ) = downloadVerifiedModel(spec.url, dest, spec.expectedBytes, spec.sha256,
        requireNetwork = { ModelDownloadNetworkPolicy.requireAllowed(appContext, allowMeteredDownloads) },
        onBytes = onBytes)

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    data class ModelFile(
        val name: String,
        val url: String,
        val expectedBytes: Long,
        /** SHA-256 of this exact immutable-revision file. */
        val sha256: String,
    )

    companion object {
        /** Full integrity check runs before activation/native load, never on each UI/window poll. */
        internal fun verifyPack(dir: File): Boolean = FILES.all {
            verifiedModelFile(File(dir, it.name), it.expectedBytes, it.sha256)
        }

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
                sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        )

        /** Download size shown before the user accepts (~240MB, one pack). */
        val APPROX_PACK_BYTES: Long = FILES.sumOf { it.expectedBytes }
    }
}

/** Production install seam: file verification has no dependency on an existing ready marker. */
internal suspend fun installSherpaPack(
    dir: File,
    files: List<SherpaModelInstaller.ModelFile>,
    marker: String,
    onProgress: (Float) -> Unit = {},
    download: suspend (SherpaModelInstaller.ModelFile, File, (Long, Long) -> Unit) -> Unit,
) {
    dir.mkdirs()
    File(dir, ".ready").delete()
    val total = files.sumOf { it.expectedBytes }
    var completed = 0L
    onProgress(0.02f)
    files.forEach { spec ->
        download(spec, File(dir, spec.name)) { read, size ->
            val overall = completed + read.coerceAtMost(size)
            onProgress((0.02f + overall.toDouble().div(total).times(0.95).toFloat()).coerceIn(0.02f, 0.97f))
        }
        completed += spec.expectedBytes
    }
    currentCoroutineContext().ensureActive()
    publishModelReady(dir, marker) {
        files.all { verifiedModelFile(File(dir, it.name), it.expectedBytes, it.sha256) }
    }
    onProgress(1f)
}
