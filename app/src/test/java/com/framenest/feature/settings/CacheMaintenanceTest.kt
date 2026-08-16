package com.framenest.feature.settings

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheMaintenanceTest {

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
