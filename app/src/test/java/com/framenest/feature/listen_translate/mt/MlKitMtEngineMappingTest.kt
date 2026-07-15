package com.framenest.feature.listen_translate.mt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitMtEngineMappingTest {

    @Test
    fun mapsCommonLangTags() {
        assertEquals("zh", MlKitMtEngine.toMlKit("zh"))
        assertEquals("en", MlKitMtEngine.toMlKit("en"))
        assertEquals("ja", MlKitMtEngine.toMlKit("ja"))
        assertTrue(MlKitMtEngine.isSupported("ko"))
        assertFalse(MlKitMtEngine.isSupported("xx-fake"))
    }

    @Test
    fun sameLanguageDoesNotTryToDownloadTranslationModel() = runBlocking {
        val engine = MlKitMtEngine()
        try {
            engine.ensureModel("en", "EN")
        } finally {
            engine.close()
        }
    }
}
