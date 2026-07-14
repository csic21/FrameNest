package com.framenest.feature.listen_translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StubListenTranslateEngineTest {

    @Test
    fun processWindow_isDeterministicAndOffline() = runBlocking {
        val engine = StubListenTranslateEngine()
        val a = engine.processWindow(0, 3_000, "ja", "zh")
        val b = engine.processWindow(0, 3_000, "ja", "zh")
        assertEquals(a, b)
        assertTrue(a.textSrc.contains("ja"))
        assertTrue(a.textTgt.contains("秒") || a.textTgt.contains("【"))
        assertEquals("stub-asr-1", engine.asrModelId)
    }
}
