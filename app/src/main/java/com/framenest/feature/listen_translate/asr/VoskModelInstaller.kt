package com.framenest.feature.listen_translate.asr

import android.content.Context
import com.framenest.feature.listen_translate.ModelDownloadNetworkPolicy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads and unpacks small Vosk models into app-private storage.
 *
 * Layout: `filesDir/listen_models/vosk/<langTag>/<modelFolder>/...`
 * Uninstall clears filesDir (decision 0005).
 */
class VoskModelInstaller(
    context: Context,
) {
    private val appContext = context.applicationContext
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

    /** Snapshot of every catalogued Vosk language for Settings UI. */
    fun languageStatuses(): List<VoskLanguageStatus> =
        SPECS.keys.sorted().map { lang ->
            val spec = SPECS.getValue(lang)
            VoskLanguageStatus(
                langTag = lang,
                installed = isInstalled(lang),
                approxBytes = spec.approxBytes,
                folderName = spec.folderName,
            )
        }

    fun approximateBytes(): Long = dirSize(root)

    suspend fun ensureInstalled(
        langTag: String,
        allowMeteredDownloads: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): File = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
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
            val stagingDir = File(langRoot, ".${spec.folderName}.staging")
            var installed = false

            try {
                onProgress(0.02f)
                download(spec, zipFile, allowMeteredDownloads) { read, total ->
                    val p = if (total > 0) {
                        (read.toDouble() / total.toDouble() * 0.85).toFloat()
                    } else {
                        0.4f
                    }
                    onProgress(p.coerceIn(0.02f, 0.87f))
                }
                onProgress(0.88f)
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                unzip(zipFile, stagingDir)
                val stagedModel = File(stagingDir, spec.folderName)
                if (!isCompleteVoskModel(stagedModel)) {
                    error("Vosk 模型解压后校验失败（$lang）")
                }
                if (unpackDir.exists()) unpackDir.deleteRecursively()
                if (!stagedModel.renameTo(unpackDir)) {
                    stagedModel.copyRecursively(unpackDir, overwrite = true)
                }
                check(isCompleteVoskModel(unpackDir)) {
                    "Vosk 模型安装未完整落盘（$lang）"
                }
                File(unpackDir, READY_MARKER).writeText("${spec.folderName}\n${spec.sha256}\n")
                installed = true
                onProgress(1f)
                unpackDir
            } finally {
                if (installed) {
                    runCatching { if (zipFile.exists()) zipFile.delete() }
                    runCatching { File(zipFile.absolutePath + PART_SUFFIX).delete() }
                }
                runCatching { if (stagingDir.exists()) stagingDir.deleteRecursively() }
            }
        }
    }

    suspend fun deleteAll() = INSTALL_MUTEX.withLock {
        withContext(Dispatchers.IO) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
        }
    }

    private fun isReady(dir: File): Boolean {
        return isCompleteVoskModel(dir)
    }

    private suspend fun download(
        spec: Spec,
        dest: File,
        allowMeteredDownloads: Boolean,
        onBytes: (read: Long, total: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + PART_SUFFIX)

        if (dest.isFile && verifyArchive(dest, spec)) {
            onBytes(spec.archiveBytes, spec.archiveBytes)
            return
        }
        if (dest.exists()) dest.delete()
        if (part.length() > spec.archiveBytes) part.delete()
        if (part.length() == spec.archiveBytes) {
            if (verifyArchive(part, spec)) {
                promote(part, dest)
                onBytes(spec.archiveBytes, spec.archiveBytes)
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
            if (code !in 200..299) error("下载 Vosk 模型失败 HTTP $code")
            val plan = resumeDownloadPlan(requestedOffset, code)
            if (plan.append) {
                val contentRange = conn.getHeaderField("Content-Range").orEmpty()
                check(contentRange.startsWith("bytes $requestedOffset-")) {
                    "Vosk 模型服务器未返回预期的续传范围"
                }
            }
            val contentLength = conn.contentLengthLong
            val expectedFromResponse = if (contentLength > 0L) {
                plan.startBytes + contentLength
            } else {
                spec.archiveBytes
            }
            check(expectedFromResponse == spec.archiveBytes) {
                "Vosk 模型大小与固定清单不一致"
            }
            conn.inputStream.use { input ->
                BufferedInputStream(input).use { bis ->
                    FileOutputStream(part, plan.append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = plan.startBytes
                        onBytes(readTotal, spec.archiveBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = bis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            onBytes(readTotal, spec.archiveBytes)
                        }
                        out.fd.sync()
                        if (readTotal != spec.archiveBytes) {
                            error("Vosk 模型下载不完整：$readTotal/${spec.archiveBytes} bytes")
                        }
                    }
                }
            }
            if (!verifyArchive(part, spec)) {
                part.delete()
                error("Vosk 模型 SHA-256 校验失败")
            }
            promote(part, dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun verifyArchive(file: File, spec: Spec): Boolean =
        file.length() == spec.archiveBytes && sha256(file).equals(spec.sha256, ignoreCase = true)

    private fun promote(source: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
    }

    private suspend fun unzip(zipFile: File, destDir: File) {
        val destinationRoot = destDir.canonicalFile
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(64 * 1024)
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                val outFile = File(destinationRoot, entry.name).canonicalFile
                check(outFile.path.startsWith(destinationRoot.path + File.separator)) {
                    "Vosk 模型压缩包包含非法路径"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
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

    data class Spec(
        val folderName: String,
        val url: String,
        val archiveBytes: Long,
        val sha256: String,
    ) {
        val approxBytes: Long get() = archiveBytes
    }

    companion object {
        private const val BASE = "https://alphacephei.com/vosk/models"
        private const val READY_MARKER = ".ready"
        private const val PART_SUFFIX = ".part"
        private val INSTALL_MUTEX = Mutex()

        /**
         * Recommended for Settings one-tap install (covers common 中↔英 listening).
         * Each pack is ~40MB; install needs network.
         */
        val RECOMMENDED_LANGS: List<String> = listOf("zh", "en")

        /**
         * Small offline packs only (size ~40–100MB). Keys are UI language tags.
         */
        val SPECS: Map<String, Spec> = mapOf(
            "en" to Spec(
                "vosk-model-small-en-us-0.15",
                "$BASE/vosk-model-small-en-us-0.15.zip",
                41_205_931L,
                "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            ),
            "zh" to Spec(
                "vosk-model-small-cn-0.22",
                "$BASE/vosk-model-small-cn-0.22.zip",
                43_898_754L,
                "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba",
            ),
            "ja" to Spec(
                "vosk-model-small-ja-0.22",
                "$BASE/vosk-model-small-ja-0.22.zip",
                49_704_573L,
                "efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac",
            ),
            "ko" to Spec(
                "vosk-model-small-ko-0.22",
                "$BASE/vosk-model-small-ko-0.22.zip",
                86_914_329L,
                "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989",
            ),
            "fr" to Spec(
                "vosk-model-small-fr-0.22",
                "$BASE/vosk-model-small-fr-0.22.zip",
                42_233_323L,
                "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad",
            ),
            "de" to Spec(
                "vosk-model-small-de-0.15",
                "$BASE/vosk-model-small-de-0.15.zip",
                46_499_967L,
                "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43",
            ),
            "es" to Spec(
                "vosk-model-small-es-0.42",
                "$BASE/vosk-model-small-es-0.42.zip",
                39_817_833L,
                "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f",
            ),
        )

        fun supportedSourceLanguages(): Set<String> = SPECS.keys

        fun modelVersionTag(langTag: String): String? =
            SPECS[langTag.lowercase()]?.folderName

        fun isRecommendedReady(statuses: List<VoskLanguageStatus>): Boolean {
            val installed = statuses.filter { it.installed }.map { it.langTag }.toSet()
            return RECOMMENDED_LANGS.all { it in installed }
        }
    }
}

/** Per-language Vosk pack status for Settings. */
data class VoskLanguageStatus(
    val langTag: String,
    val installed: Boolean,
    val approxBytes: Long,
    val folderName: String,
)

internal fun isCompleteVoskModel(dir: File): Boolean =
    dir.isDirectory &&
        File(dir, "am/final.mdl").isFile &&
        File(dir, "conf/model.conf").isFile &&
        File(dir, "graph").isDirectory

internal data class ResumeDownloadPlan(
    val startBytes: Long,
    val append: Boolean,
)

/** A server may ignore Range and return 200; in that case safely restart the part file. */
internal fun resumeDownloadPlan(existingBytes: Long, responseCode: Int): ResumeDownloadPlan =
    if (existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL) {
        ResumeDownloadPlan(startBytes = existingBytes, append = true)
    } else {
        ResumeDownloadPlan(startBytes = 0L, append = false)
    }

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}
