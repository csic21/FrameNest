package com.framenest.feature.listen_translate.asr.streaming

import java.io.File
import java.security.MessageDigest

internal enum class ZipformerFile { ENCODER, DECODER, JOINER, TOKENS }
internal data class ZipformerFileDigest(val relativePath: String, val bytes: Long, val sha256: String)

/** Trusted metadata must come from a reviewed immutable checkpoint manifest, never the download itself. */
internal data class ZipformerPackDescriptor(
    val id: String,
    val immutableRevision: String,
    val licenseEvidenceUrl: String,
    val files: Map<ZipformerFile, ZipformerFileDigest>,
)

/**
 * Unforgeable public handle. No bundled checkpoint is enabled in FN-72. A future reviewed installer
 * must call verify under Context.filesDir (not shared/external storage), after user-approved install.
 * Construction and verification stay internal so arbitrary external paths cannot reach native code.
 */
class VerifiedZipformerPack private constructor(
    private val privateRoot: File,
    private val packDirectory: File,
    private val descriptor: ZipformerPackDescriptor,
) {
    internal fun verifiedPaths(): Map<ZipformerFile, File> = verifyPaths(privateRoot, packDirectory, descriptor)

    internal companion object {
        fun verify(privateFilesDir: File, packDirectory: File, descriptor: ZipformerPackDescriptor): VerifiedZipformerPack {
            verifyPaths(privateFilesDir, packDirectory, descriptor)
            return VerifiedZipformerPack(privateFilesDir, packDirectory, descriptor.copy(files = descriptor.files.toMap()))
        }

        private fun verifyPaths(root: File, directory: File, descriptor: ZipformerPackDescriptor): Map<ZipformerFile, File> {
            require(descriptor.id.isNotBlank() && descriptor.immutableRevision.isNotBlank())
            require(descriptor.licenseEvidenceUrl.startsWith("https://"))
            require(descriptor.files.keys == ZipformerFile.entries.toSet())
            val rootPath = root.canonicalFile.toPath()
            val packPath = directory.canonicalFile.toPath()
            require(packPath != rootPath && packPath.startsWith(rootPath)) { "Pack must be in private storage" }
            return descriptor.files.mapValues { (_, expected) ->
                require(expected.bytes > 0 && expected.sha256.matches(Regex("[0-9a-f]{64}")))
                require(expected.relativePath.isNotBlank() && !File(expected.relativePath).isAbsolute)
                require(expected.relativePath.split('/', '\\').none { it == ".." || it == "." })
                val file = File(directory, expected.relativePath).canonicalFile
                require(file.toPath().startsWith(packPath) && file.toPath() != packPath)
                require(file.isFile && file.length() == expected.bytes) { "Model size mismatch" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= expected.bytes) { "Model size changed" }
                        digest.update(buffer, 0, count)
                    }
                    require(total == expected.bytes)
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) { "Model hash mismatch" }
                file
            }.also { require(it.values.toSet().size == ZipformerFile.entries.size) }
        }
    }
}
