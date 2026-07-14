package com.framenest.data.listen_translate.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListenModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun markReady_and_isInstalled_checksSha256() {
        val root = tmp.newFolder("models")
        val store = ListenModelStore(rootOverride = root)
        val bytes = """{"id":"asr-tiny","version":"1","kind":"asr"}""".toByteArray()
        val spec = ListenModelSpec(
            id = "asr-tiny",
            version = "1",
            kind = ListenModelKind.Asr,
            displayName = "test",
            assetPath = "x",
            sha256 = ListenModelStore.sha256Hex(bytes),
            approxBytes = bytes.size.toLong(),
        )
        assertFalse(store.isInstalled(spec))
        store.markReady(spec, bytes)
        assertTrue(store.isInstalled(spec))
        assertTrue(store.packFile(spec).isFile)
        assertTrue(store.readyMarker(spec).isFile)
        store.deletePack(spec)
        assertFalse(store.isInstalled(spec))
    }

    @Test
    fun markReady_rejectsBadChecksum() {
        val root = tmp.newFolder("models2")
        val store = ListenModelStore(rootOverride = root)
        val spec = ListenModelCatalog.ASR_TINY
        try {
            store.markReady(spec, byteArrayOf(1, 2, 3))
            throw AssertionError("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("checksum"))
        }
        assertFalse(store.isInstalled(spec))
    }

    @Test
    fun deleteAll_clearsRoot() {
        val root = tmp.newFolder("models3")
        val store = ListenModelStore(rootOverride = root)
        File(root, "junk.txt").writeText("x")
        assertTrue(store.approximateBytes() > 0)
        store.deleteAll()
        assertEquals(0L, store.approximateBytes())
        assertTrue(root.exists())
    }
}
