package com.framenest.feature.listen_translate.mt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTranslationModelCleanerTest {

    @Test
    fun constructor_defersRemoteModelManagerInitialization() {
        var providerCalls = 0

        MlKitTranslationModelCleaner(
            remoteModelManagerProvider = {
                providerCalls += 1
                error("provider must not run during construction")
            },
        )

        assertEquals(0, providerCalls)
    }

    @Test
    fun deleteEachModel_deletesEveryDownloadedPack() = runBlocking {
        val deleted = mutableListOf<String>()

        val count = deleteEachModel(linkedSetOf("en", "zh", "ja")) { model ->
            deleted += model
        }

        assertEquals(3, count)
        assertEquals(listOf("en", "zh", "ja"), deleted)
    }
}
