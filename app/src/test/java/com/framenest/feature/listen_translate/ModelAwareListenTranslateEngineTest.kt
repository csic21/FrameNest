package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.model.ListenModelCatalog
import com.framenest.data.listen_translate.model.ListenModelManager
import com.framenest.data.listen_translate.model.ListenModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelAwareListenTranslateEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun processWindow_requiresModels_orUsesFallback() = runBlocking {
        val root = tmp.newFolder("eng")
        val store = ListenModelStore(rootOverride = root)
        val manager = ListenModelManager(
            store = store,
            catalog = emptyList(),
            assetReader = { error("no") },
        )
        val strict = ModelAwareListenTranslateEngine(manager, fallbackWhenMissing = null)
        try {
            strict.processWindow(0, 3000, "ja", "zh")
            throw AssertionError("expected ModelsNotReadyException")
        } catch (e: ModelsNotReadyException) {
            assertTrue(e.message!!.contains("模型"))
        }

        val soft = ModelAwareListenTranslateEngine(
            manager,
            fallbackWhenMissing = StubListenTranslateEngine(),
        )
        val r = soft.processWindow(0, 3000, "ja", "zh")
        assertTrue(r.textSrc.contains("ja"))
    }

    @Test
    fun processWindow_withInstalledPacks_usesModelIds() = runBlocking {
        val root = tmp.newFolder("eng2")
        val store = ListenModelStore(rootOverride = root)
        val asrBytes = """{"id":"asr-tiny","version":"1","kind":"asr"}""".toByteArray()
        val mtBytes = (
            """{"id":"mt-base","version":"1","kind":"mt",""" +
                """"phraseEntries":[{"pair":"ja|zh","src":"x","tgt":"y"}]}"""
            ).toByteArray()
        val asr = ListenModelCatalog.ASR_TINY.copy(sha256 = ListenModelStore.sha256Hex(asrBytes))
        val mt = ListenModelCatalog.MT_BASE.copy(sha256 = ListenModelStore.sha256Hex(mtBytes))
        val assets = mapOf(asr.assetPath to asrBytes, mt.assetPath to mtBytes)
        val manager = ListenModelManager(
            store = store,
            catalog = listOf(asr, mt),
            assetReader = { assets.getValue(it) },
        )
        assertTrue(manager.install(asr).isSuccess)
        assertTrue(manager.install(mt).isSuccess)
        val engine = ModelAwareListenTranslateEngine(manager)
        assertEquals(asr.modelIdTag, engine.asrModelId)
        val out = engine.processWindow(0, 3000, "ja", "zh")
        assertTrue(out.textSrc.isNotBlank())
        assertTrue(out.textTgt.isNotBlank())
    }
}
