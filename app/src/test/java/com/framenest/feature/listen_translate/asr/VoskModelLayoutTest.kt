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
}
