package com.framenest.feature.listen_translate.asr.streaming

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class VerifiedZipformerPackTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(root: File): Pair<File, ZipformerPackDescriptor> {
        val directory = File(root, "pack").apply { mkdir() }
        val files = ZipformerFile.entries.associateWith { role ->
            val bytes = role.name.toByteArray()
            val name = "${role.name}.bin"
            File(directory, name).writeBytes(bytes)
            ZipformerFileDigest(name, bytes.size.toLong(), digest(bytes))
        }
        return directory to ZipformerPackDescriptor("test-only", "immutable-test-revision", "https://example.test/license", files)
    }
    @Test fun hashAndSizeAreCheckedAgainBeforeNativeLoading() {
        val root = Files.createTempDirectory("zipformer-private").toFile()
        try {
            val (dir, descriptor) = fixture(root)
            val verified = VerifiedZipformerPack.verify(root, dir, descriptor)
            assertEquals(4, verified.verifiedPaths().size)
            File(dir, "ENCODER.bin").writeText("XNCODER") // same length, wrong hash
            assertTrue(runCatching { verified.verifiedPaths() }.isFailure)
            File(dir, "ENCODER.bin").writeText("ENCODER-long")
            assertTrue(runCatching { VerifiedZipformerPack.verify(root, dir, descriptor) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTraversalOutsidePrivateRootMissingManifestAndSymlinksEscapingPack() {
        val root = Files.createTempDirectory("zipformer-private").toFile()
        val external = Files.createTempDirectory("zipformer-outside").toFile()
        try {
            val (dir, descriptor) = fixture(root)
            assertTrue(runCatching { VerifiedZipformerPack.verify(external, dir, descriptor) }.isFailure)
            assertTrue(runCatching { VerifiedZipformerPack.verify(root, dir, descriptor.copy(files = descriptor.files - ZipformerFile.TOKENS)) }.isFailure)
            val tokens = descriptor.files.getValue(ZipformerFile.TOKENS)
            val traversal = descriptor.copy(files = descriptor.files + (ZipformerFile.TOKENS to tokens.copy(relativePath = "../tokens")))
            assertTrue(runCatching { VerifiedZipformerPack.verify(root, dir, traversal) }.isFailure)
            val tokenFile = File(dir, tokens.relativePath)
            val outsideFile = File(external, "tokens").apply { writeBytes(tokenFile.readBytes()) }
            tokenFile.delete()
            Files.createSymbolicLink(tokenFile.toPath(), outsideFile.toPath())
            assertTrue(runCatching { VerifiedZipformerPack.verify(root, dir, descriptor) }.isFailure)
        } finally { root.deleteRecursively(); external.deleteRecursively() }
    }
}
