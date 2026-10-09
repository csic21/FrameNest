package com.framenest.data.thumbnail

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailDiskCacheConcurrencyTest {
    @Test fun memoryHitAndDurationDoNotWaitForDecodeOrQueuedClear() {
        val root = temporaryRoot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        val warmBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val decoded = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cache = ThumbnailDiskCache(root, decodeBitmap = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Decode gate not released" }
            decoded
        })
        val warm = key("warm")
        val cold = key("cold")
        try {
            cache.put(warm, byteArrayOf(1, 2, 3), warmBitmap, 42_000L)
            cache.put(cold, byteArrayOf(4, 5))
            val decode = workers.submit<Bitmap?> { cache.getBitmap(cold) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val clearEntered = CountDownLatch(1)
            val clear = workers.submit { clearEntered.countDown(); cache.clear() }
            assertTrue(clearEntered.await(5, TimeUnit.SECONDS))
            // This future must finish BEFORE releasing decode. Shared monitors deadlock here.
            workers.submit {
                assertSame(warmBitmap, cache.getMemoryBitmap(warm))
                assertEquals(42_000L, cache.cachedDurationMs(warm))
            }.get(5, TimeUnit.SECONDS)
            assertFalse(clear.isDone)
            release.countDown()
            assertSame(decoded, decode.get(5, TimeUnit.SECONDS))
            clear.get(5, TimeUnit.SECONDS)
            assertNull(cache.getMemoryBitmap(warm))
            assertNull(cache.getMemoryBitmap(cold))
            assertNull(cache.getBitmap(cold))
            assertEquals(0L, cache.cachedDurationMs(warm))
            assertEquals(0L, cache.approximateSizeBytes())
            // The row may still hold either image after cache clear/eviction.
            assertFalse(warmBitmap.isRecycled)
            assertFalse(decoded.isRecycled)
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test fun replacementTrimAndCorruptDecodeKeepAccountingAndMemoryConsistent() {
        val root = temporaryRoot()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cache = ThumbnailDiskCache(root, maxBytes = 5, decodeBitmap = { null })
        try {
            val first = key("first")
            cache.put(first, byteArrayOf(1, 2, 3), bitmap, 10)
            assertEquals(3L, cache.approximateSizeBytes())
            cache.put(first, byteArrayOf(1, 2), durationMs = 20)
            assertNull(cache.getMemoryBitmap(first))
            assertEquals(2L, cache.approximateSizeBytes())
            assertEquals(20L, cache.cachedDurationMs(first))
            assertNull(cache.getBitmap(first))
            assertEquals(0L, cache.approximateSizeBytes())
            cache.put(key("oversized"), ByteArray(6), bitmap, 30)
            assertEquals(0L, cache.approximateSizeBytes())
            assertNull(cache.getMemoryBitmap(key("oversized")))
            assertEquals(0L, cache.cachedDurationMs(key("oversized")))
            assertFalse(bitmap.isRecycled)
        } finally { root.deleteRecursively() }
    }

    private fun temporaryRoot(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "fn60-thumbnail-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    private fun key(name: String) = ThumbnailKey("test", "media", "$name.mkv", 1L, 1L)
}
