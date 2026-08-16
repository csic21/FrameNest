package com.framenest.data.thumbnail

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDiskSizeIndexTest {

    @Test
    fun writesAndDeletes_useCachedBytes_withoutRepeatedScans() {
        val index = ThumbnailDiskSizeIndex()
        var scans = 0
        val scan = {
            scans++
            20L
        }

        assertEquals(20L, index.recordWrite(replacedBytes = 0L, writtenBytes = 20L, scan))
        assertEquals(1, scans)
        assertEquals(28L, index.recordWrite(replacedBytes = 5L, writtenBytes = 13L, scan))
        assertEquals(1, scans)

        index.recordDelete(8L)
        assertEquals(20L, index.bytes(scan))
        assertEquals(1, scans)
    }

    @Test
    fun invalidation_scansOnceThenCachesAgain() {
        val index = ThumbnailDiskSizeIndex()
        var scans = 0
        val scan = {
            scans++
            42L
        }

        assertEquals(42L, index.bytes(scan))
        assertEquals(42L, index.bytes(scan))
        assertEquals(1, scans)

        index.invalidate()
        assertEquals(42L, index.bytes(scan))
        assertEquals(2, scans)
        index.set(7L)
        assertEquals(7L, index.bytes(scan))
        assertEquals(2, scans)
    }

    @Test
    fun diskCache_createsDirectoryLazily_andTracksOverwriteAndRemove() {
        val root = Files.createTempDirectory("framenest-thumbnail-cache").toFile()
        try {
            val cache = ThumbnailDiskCache(rootDir = root, maxBytes = 1_024L)
            val first = key("first.mkv")
            val second = key("second.mkv")
            val cacheDirectory = File(root, ThumbnailDiskCache.SUBDIR)
            assertFalse(cacheDirectory.exists())

            cache.put(first, ByteArray(3))
            cache.put(second, ByteArray(5))
            assertTrue(cacheDirectory.isDirectory)
            assertEquals(8L, cache.approximateSizeBytes())

            cache.put(first, ByteArray(7))
            assertEquals(12L, cache.approximateSizeBytes())
            cache.remove(second)
            assertEquals(7L, cache.approximateSizeBytes())
            cache.clear()
            assertEquals(0L, cache.approximateSizeBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diskCache_trimsJpegsOnlyAfterConfiguredLimitIsExceeded() {
        val root = Files.createTempDirectory("framenest-thumbnail-trim").toFile()
        try {
            val cache = ThumbnailDiskCache(rootDir = root, maxBytes = 8L)
            cache.put(key("first.mkv"), ByteArray(5))
            assertEquals(5L, cache.approximateSizeBytes())

            cache.put(key("second.mkv"), ByteArray(5))

            assertEquals(5L, cache.approximateSizeBytes())
            assertEquals(
                1,
                File(root, ThumbnailDiskCache.SUBDIR)
                    .listFiles()
                    .orEmpty()
                    .count { it.extension == "jpg" },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun key(path: String): ThumbnailKey =
        ThumbnailKey(
            serverId = "server",
            share = "media",
            path = path,
            sizeBytes = 1L,
            modifiedTimeMs = 1L,
        )
}
