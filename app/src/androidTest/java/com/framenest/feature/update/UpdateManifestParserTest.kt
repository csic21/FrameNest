package com.framenest.feature.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateManifestParserTest {
    private val valid = """{"schemaVersion":1,"applicationId":"com.framenest","versionName":"0.6.0-internal","versionCode":11,"tag":"v0.6.0-internal","notes":"更新说明","minSdk":26,"assets":[{"abi":"arm64-v8a","size":100,"sha256":"${"a".repeat(64)}","url":"https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-arm64-v8a.apk"}]}"""

    @Test fun parsesPublisherContractAndRejectsCoercionsOrMissingFields() {
        assertEquals(11L, parseUpdateManifest(valid).versionCode)
        listOf(
            "{}", "[]", "invalid",
            valid.replace("\"versionCode\":11", "\"versionCode\":\"11\""),
            valid.replace("\"versionCode\":11", "\"versionCode\":11.5"),
            valid.replace("\"notes\":\"更新说明\"", "\"notes\":null"),
            valid.replace("\"minSdk\":26", "\"minSdk\":4294967322"),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
        ).forEach { assertThrows(Exception::class.java) { parseUpdateManifest(it) } }
    }

    @Test fun verifierRejectsWrongDigestAndInvalidArchiveWithoutStartingInstaller() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = AndroidUpdateBackend(context)
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val file = File(directory, "verification-test.apk").apply { writeBytes(ByteArray(100)) }
        val manifest = parseUpdateManifest(valid)
        try {
            try {
                backend.verify(file, manifest, manifest.assets.single())
                fail("A bad digest must never be installable")
            } catch (error: UpdateProblem) {
                assertTrue(error.message.orEmpty().contains("摘要"))
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            try {
                backend.verify(file, manifest, manifest.assets.single().copy(sha256 = digest))
                fail("Matching hash cannot make a non-APK installable")
            } catch (error: UpdateProblem) {
                assertTrue(error.message.orEmpty().contains("无法识别"))
            }
        } finally {
            file.delete()
        }
    }
}
