package com.framenest.data.listen_translate.model

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-private layout for listen-translate models (decision 0005 uninstall-clears).
 *
 * ```
 * filesDir/listen_models/<id>/v<version>/<id>-v<version>.json
 * filesDir/listen_models/<id>/v<version>/.ready
 * ```
 */
class ListenModelStore(
    context: Context? = null,
    rootOverride: File? = null,
) {
    val rootDir: File = when {
        rootOverride != null -> rootOverride.also { it.mkdirs() }
        context != null -> File(context.applicationContext.filesDir, ROOT_NAME).also { it.mkdirs() }
        else -> error("context or rootOverride required")
    }

    fun packDir(spec: ListenModelSpec): File =
        File(rootDir, "${spec.id}/v${spec.version}").also { it.mkdirs() }

    fun packFile(spec: ListenModelSpec): File =
        File(packDir(spec), spec.packFileName)

    fun readyMarker(spec: ListenModelSpec): File =
        File(packDir(spec), READY_NAME)

    fun isInstalled(spec: ListenModelSpec): Boolean {
        val file = packFile(spec)
        val marker = readyMarker(spec)
        if (!file.isFile || !marker.isFile) return false
        val hash = sha256Hex(file) ?: return false
        return hash.equals(spec.sha256, ignoreCase = true)
    }

    fun readPackBytes(spec: ListenModelSpec): ByteArray? {
        val file = packFile(spec)
        if (!isInstalled(spec)) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun deletePack(spec: ListenModelSpec) {
        val dir = File(rootDir, "${spec.id}/v${spec.version}")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        // Drop empty id dir.
        val idDir = File(rootDir, spec.id)
        if (idDir.isDirectory && idDir.list().isNullOrEmpty()) {
            idDir.delete()
        }
    }

    fun deleteAll() {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        rootDir.mkdirs()
    }

    fun approximateBytes(): Long = dirSize(rootDir)

    fun markReady(spec: ListenModelSpec, fileBytes: ByteArray) {
        val dir = packDir(spec)
        val out = packFile(spec)
        val tmp = File(dir, "${spec.packFileName}.tmp")
        tmp.writeBytes(fileBytes)
        val hash = sha256Hex(tmp) ?: error("checksum failed")
        if (!hash.equals(spec.sha256, ignoreCase = true)) {
            tmp.delete()
            error("checksum mismatch for ${spec.id}")
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        readyMarker(spec).writeText("ok\n")
    }

    companion object {
        const val ROOT_NAME: String = "listen_models"
        private const val READY_NAME: String = ".ready"

        fun sha256Hex(file: File): String? =
            runCatching { sha256Hex(file.readBytes()) }.getOrNull()

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }

        private fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
    }
}
