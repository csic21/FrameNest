package com.framenest.data.listen_translate.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListenModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun install_fromAssetBytes_verifiesAndLoadsPhraseTable() = runBlocking {
        val root = tmp.newFolder("mgr")
        val store = ListenModelStore(rootOverride = root)
        val asrBytes = """{"id":"asr-tiny","version":"1","kind":"asr"}""".toByteArray()
        val mtJson = (
            """{"id":"mt-base","version":"1","kind":"mt",""" +
                """"phraseEntries":[{"pair":"ja|zh","src":"hello","tgt":"你好"}]}"""
            ).toByteArray()
        val asrSpec = ListenModelCatalog.ASR_TINY.copy(
            sha256 = ListenModelStore.sha256Hex(asrBytes),
            approxBytes = asrBytes.size.toLong(),
        )
        val mtSpec = ListenModelCatalog.MT_BASE.copy(
            sha256 = ListenModelStore.sha256Hex(mtJson),
            approxBytes = mtJson.size.toLong(),
        )
        val assets = mapOf(
            asrSpec.assetPath to asrBytes,
            mtSpec.assetPath to mtJson,
        )
        val manager = ListenModelManager(
            store = store,
            catalog = listOf(asrSpec, mtSpec),
            assetReader = { path -> assets[path] ?: error("missing $path") },
        )

        assertFalse(manager.areCoreModelsReady())
        val asrResult = manager.install(asrSpec)
        assertTrue("asr: ${asrResult.exceptionOrNull()}", asrResult.isSuccess)
        val mtResult = manager.install(mtSpec)
        assertTrue("mt: ${mtResult.exceptionOrNull()}", mtResult.isSuccess)
        assertTrue(manager.areCoreModelsReady())
        assertEquals("你好", manager.loadMtPhraseTable()["ja|zh"]?.get("hello"))
        assertEquals(asrSpec.modelIdTag, manager.asrModelId())

        manager.deleteAll()
        assertFalse(manager.areCoreModelsReady())
        assertEquals(0L, manager.approximateBytes())
    }

    @Test
    fun install_checksumMismatch_failsCleanly() = runBlocking {
        val root = tmp.newFolder("mgr2")
        val store = ListenModelStore(rootOverride = root)
        val manager = ListenModelManager(
            store = store,
            catalog = listOf(ListenModelCatalog.ASR_TINY),
            assetReader = { byteArrayOf(9, 9, 9) },
        )
        val result = manager.install(ListenModelCatalog.ASR_TINY)
        assertTrue(result.isFailure)
        assertFalse(store.isInstalled(ListenModelCatalog.ASR_TINY))
    }
}
