package com.framenest.feature.listen_translate.asr

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedModelDownloadTest {
    private fun temp(): File = Files.createTempDirectory("framenest-model-test-").toFile()
    @Test fun rangeValidationIsExactAndOverflowSafe() {
        assertEquals(ResumeDownloadPlan(5, true), modelResponsePlan(5, 10, 206, 5, "bytes 5-9/10"))
        assertEquals(ResumeDownloadPlan(0, false), modelResponsePlan(5, 10, 200, -1, null))
        for (range in listOf("bytes 5-8/10", "bytes 5-9/11", "bytes 50-59/10", "bytes 5-9/*", "bytes 5-999999999999999999999/10")) {
            assertTrue(runCatching { modelResponsePlan(5, 10, 206, -1, range) }.exceptionOrNull() is InvalidModelDownload)
        }
        assertTrue(runCatching { modelResponsePlan(5, 10, 206, Long.MAX_VALUE, "bytes 5-9/10") }.isFailure)
        assertTrue(runCatching { modelResponsePlan(0, 10, 201, -1, null) }.isFailure)
    }
    @Test fun chunkedOversizeNeverWritesBeyondBound() = runBlocking {
        val root = temp()
        try {
            val part = File(root, "test.part")
            val failure = runCatching {
                FileOutputStream(part).use { copyModelBytes(ByteArrayInputStream(ByteArray(128)), it, 0, 64) { _, _ -> } }
            }.exceptionOrNull()
            assertTrue(failure is InvalidModelDownload)
            assertTrue(part.length() <= 64L)
        } finally { root.deleteRecursively() }
    }
    @Test fun freshInstallPublishesMarkerLastAndRejectsCorruption() {
        val root = temp()
        try {
            val model = File(root, "model").apply { writeText("verified") }
            val hash = sha256(model)
            assertFalse(File(root, ".ready").exists())
            publishModelReady(root, "revision\n") { verifiedModelFile(model, 8, hash) }
            assertEquals("revision\n", File(root, ".ready").readText())
            assertFalse(File(root, ".ready.part").exists())
            model.writeText("corrupt!") // same byte length
            assertTrue(runCatching { publishModelReady(root, "revision\n") { verifiedModelFile(model, 8, hash) } }.isFailure)
            assertFalse(File(root, ".ready").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun downloadResumesAndHashesEntirePrefixOrDeletesCorruptPart() = runBlocking {
        val root = temp()
        try {
            val source = File(root, "source").apply { writeText("abcdefghij") }
            val dest = File(root, "download")
            val part = File(dest.path + ".part")
            part.writeText("abcde")
            var disconnected = false
            val connection = object : HttpURLConnection(URL("https://huggingface.co/test")) {
                override fun disconnect() { disconnected = true }
                override fun usingProxy() = false
                override fun connect() = Unit
                override fun getResponseCode() = 206
                override fun getContentLengthLong() = -1L
                override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes 5-9/10" else null
                override fun getInputStream() = ByteArrayInputStream("fghij".toByteArray())
            }
            downloadVerifiedModel("unused", dest, 10, sha256(source), {}, { _, _ -> }) { _, offset -> assertEquals(5L, offset); connection }
            assertEquals(source.readText(), dest.readText())
            assertTrue(disconnected)
            dest.delete()
            part.writeText("wrong")
            assertTrue(runCatching { downloadVerifiedModel("unused", dest, 10, sha256(source), {}, { _, _ -> }) { _, _ -> connection } }.exceptionOrNull() is InvalidModelDownload)
            assertFalse(part.exists())
            assertFalse(dest.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun redirectPolicyRejectsDowngradeCredentialsAndLookalikes() {
        assertTrue(allowedModelUrl(URL("https://cas-bridge.xethub.hf.co/model")))
        assertTrue(allowedModelUrl(URL("https://alphacephei.com/vosk/models/a.zip")))
        for (url in listOf("http://huggingface.co/model", "https://huggingface.co.evil.test/model", "https://user:pass@huggingface.co/model", "https://huggingface.co/model#fragment", "https://huggingface.co:444/model")) {
            assertFalse(allowedModelUrl(URL(url)))
        }
    }
    @Test fun tokenSidecarHasVerifiedPinnedSha() {
        assertEquals("f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", SherpaModelInstaller.FILES.single { it.name == "tokens.txt" }.sha256)
    }
}
