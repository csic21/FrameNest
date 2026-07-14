package com.framenest.feature.listen_translate.asr

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Downloads and unpacks small Vosk models into app-private storage.
 *
 * Layout: `filesDir/listen_models/vosk/<langTag>/<modelFolder>/...`
 * Uninstall clears filesDir (decision 0005).
 */
class VoskModelInstaller(
    context: Context,
) {
    private val root = File(context.applicationContext.filesDir, "listen_models/vosk").also {
        it.mkdirs()
    }

    fun modelDir(langTag: String): File? {
        val lang = langTag.lowercase()
        val spec = SPECS[lang] ?: return null
        val dir = File(root, "$lang/${spec.folderName}")
        return if (isReady(dir)) dir else null
    }

    fun isInstalled(langTag: String): Boolean = modelDir(langTag) != null

    fun approximateBytes(): Long = dirSize(root)

    suspend fun ensureInstalled(
        langTag: String,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val lang = langTag.lowercase()
        val spec = SPECS[lang]
            ?: error("暂不支持源语言「$lang」的离线 ASR（Vosk small 包）")
        modelDir(lang)?.let {
            onProgress(1f)
            return@withContext it
        }

        val langRoot = File(root, lang).also { it.mkdirs() }
        val zipFile = File(langRoot, "${spec.folderName}.zip")
        val unpackDir = File(langRoot, spec.folderName)

        try {
            onProgress(0.02f)
            download(spec.url, zipFile) { read, total ->
                val p = if (total > 0) {
                    (read.toDouble() / total.toDouble() * 0.85).toFloat()
                } else {
                    0.4f
                }
                onProgress(p.coerceIn(0.02f, 0.87f))
            }
            onProgress(0.88f)
            if (unpackDir.exists()) unpackDir.deleteRecursively()
            unzip(zipFile, langRoot)
            // Zip usually contains top-level folderName/
            val ready = File(langRoot, spec.folderName)
            if (!isReady(ready)) {
                error("Vosk 模型解压后校验失败（$lang）")
            }
            onProgress(1f)
            ready
        } finally {
            runCatching { if (zipFile.exists()) zipFile.delete() }
        }
    }

    fun deleteAll() {
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
    }

    private fun isReady(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // Typical Vosk small model markers.
        return File(dir, "am/final.mdl").isFile ||
            File(dir, "conf/model.conf").isFile ||
            File(dir, "ivector").isDirectory
    }

    private fun download(
        url: String,
        dest: File,
        onBytes: (read: Long, total: Long) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("下载 Vosk 模型失败 HTTP $code")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".tmp")
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            val n = bis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            onBytes(readTotal, total)
                        }
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    data class Spec(val folderName: String, val url: String, val approxBytes: Long)

    companion object {
        private const val BASE = "https://alphacephei.com/vosk/models"

        /**
         * Small offline packs only (size ~40–100MB). Keys are UI language tags.
         */
        val SPECS: Map<String, Spec> = mapOf(
            "en" to Spec(
                "vosk-model-small-en-us-0.15",
                "$BASE/vosk-model-small-en-us-0.15.zip",
                40L * 1024 * 1024,
            ),
            "zh" to Spec(
                "vosk-model-small-cn-0.22",
                "$BASE/vosk-model-small-cn-0.22.zip",
                42L * 1024 * 1024,
            ),
            "ja" to Spec(
                "vosk-model-small-ja-0.22",
                "$BASE/vosk-model-small-ja-0.22.zip",
                48L * 1024 * 1024,
            ),
            "ko" to Spec(
                "vosk-model-small-ko-0.22",
                "$BASE/vosk-model-small-ko-0.22.zip",
                82L * 1024 * 1024,
            ),
            "fr" to Spec(
                "vosk-model-small-fr-0.22",
                "$BASE/vosk-model-small-fr-0.22.zip",
                41L * 1024 * 1024,
            ),
            "de" to Spec(
                "vosk-model-small-de-0.15",
                "$BASE/vosk-model-small-de-0.15.zip",
                45L * 1024 * 1024,
            ),
            "es" to Spec(
                "vosk-model-small-es-0.42",
                "$BASE/vosk-model-small-es-0.42.zip",
                39L * 1024 * 1024,
            ),
        )

        fun supportedSourceLanguages(): Set<String> = SPECS.keys
    }
}
