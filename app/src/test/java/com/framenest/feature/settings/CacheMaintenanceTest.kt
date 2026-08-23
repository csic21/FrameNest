package com.framenest.feature.settings

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMaintenanceTest {

    @Test
    fun clearPolicy_keepsGeneralListenAndModelsIndependent() {
        val general = CacheClearPolicy.domainsFor(CacheClearTarget.General)
        assertTrue(CacheDomain.Thumbnails in general)
        assertTrue(CacheDomain.SubtitleFiles in general)
        assertTrue(CacheDomain.Diagnostics in general)
        assertFalse(CacheDomain.ListenTranslate in general)
        assertFalse(CacheDomain.Models in general)

        assertEquals(
            setOf(CacheDomain.ListenTranslate),
            CacheClearPolicy.domainsFor(CacheClearTarget.ListenTranslate),
        )
        assertEquals(
            setOf(CacheDomain.Models),
            CacheClearPolicy.domainsFor(CacheClearTarget.Models),
        )
    }

    @Test
    fun directorySizeBytes_countsNestedFilesOnly_andHandlesMissingDirectory() {
        val root = Files.createTempDirectory("framenest-cache-size").toFile()
        try {
            File(root, "top.bin").writeBytes(ByteArray(3))
            val nested = File(root, "nested").also { it.mkdirs() }
            File(nested, "child.bin").writeBytes(ByteArray(5))

            assertEquals(8L, directorySizeBytes(root))
            assertEquals(0L, directorySizeBytes(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }

}
