package com.framenest.feature.listen_translate.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoskModelLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readinessRequiresAllCoreModelParts() {
        val model = temp.newFolder("vosk")
        model.resolve("am").mkdirs()
        model.resolve("am/final.mdl").writeBytes(byteArrayOf(1))
        assertFalse(isCompleteVoskModel(model))

        model.resolve("conf").mkdirs()
        model.resolve("conf/model.conf").writeText("sample-frequency=16000")
        model.resolve("graph").mkdirs()
        assertTrue(isCompleteVoskModel(model))
        assertEquals(
            "vosk-model-small-en-us-0.15",
            VoskModelInstaller.modelVersionTag("EN"),
        )
    }

    @Test
    fun resumePlan_appendsOnlyWhenServerHonorsRange() {
        assertEquals(
            ResumeDownloadPlan(startBytes = 12_345L, append = true),
            resumeDownloadPlan(existingBytes = 12_345L, responseCode = 206),
        )
        assertEquals(
            ResumeDownloadPlan(startBytes = 0L, append = false),
            resumeDownloadPlan(existingBytes = 12_345L, responseCode = 200),
        )
    }

    @Test
    fun sha256_matchesKnownDigest() {
        val archive = temp.newFile("archive.zip").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223" +
                "b00361a396177a9cb410ff61f20015ad",
            sha256(archive),
        )
    }
}
