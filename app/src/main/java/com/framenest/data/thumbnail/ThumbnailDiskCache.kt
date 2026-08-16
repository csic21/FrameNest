package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * Disk cache for list thumbnails under the app cache directory.
 *
 * Layout: `[cacheDir]/thumbnails/<digest>.jpg`
 *
 * Clearable for settings (FN-09) via [clear].
 */
class ThumbnailDiskCache(
    rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
) {
    // Do not touch disk while AppContainer is created on the main thread.
    private val dir: File = File(rootDir, SUBDIR)
    private val memory = BoundedLruCache<String, Bitmap>(
        maxEntries = maxMemoryEntries,
        maxWeight = maxMemoryBytes,
        weightOf = { bitmap -> bitmap.byteCount.toLong() },
    )
    private val diskSize = ThumbnailDiskSizeIndex()

    fun fileFor(key: ThumbnailKey): File = File(dir, "${key.digest()}.jpg")

    @Synchronized
    fun has(key: ThumbnailKey): Boolean = fileFor(key).isFile

    @Synchronized
    fun getMemoryBitmap(key: ThumbnailKey): Bitmap? {
        val digest = key.digest()
        return getMemoryBitmap(digest)
    }

    private fun getMemoryBitmap(digest: String): Bitmap? {
        memory[digest]?.let { cached ->
            if (!cached.isRecycled) return cached
            memory.remove(digest)
        }
        return null
    }

    @Synchronized
    fun getBitmap(key: ThumbnailKey): Bitmap? {
        val digest = key.digest()
        getMemoryBitmap(digest)?.let { return it }
        val file = File(dir, "$digest.jpg")
        if (!file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        if (decoded == null) {
            val corruptBytes = file.length()
            if (file.delete()) diskSize.recordDelete(corruptBytes) else diskSize.invalidate()
            return null
        }
        memory.put(digest, decoded)
        return decoded
    }

    /**
     * Persist JPEG bytes and update the in-memory map when [bitmap] is provided.
     */
    @Throws(IOException::class)
    @Synchronized
    fun put(key: ThumbnailKey, jpegBytes: ByteArray, bitmap: Bitmap? = null) {
        ensureDirectory()
        val digest = key.digest()
        val file = File(dir, "$digest.jpg")
        val replacedBytes = if (file.isFile) file.length() else 0L
        val tmp = File(file.absolutePath + ".tmp")
        try {
            tmp.outputStream().use { it.write(jpegBytes) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
            }
        } catch (failure: Throwable) {
            diskSize.invalidate()
            throw failure
        } finally {
            tmp.delete()
        }
        diskSize.recordWrite(replacedBytes, file.length()) { scanJpegBytes() }
        if (bitmap != null && !bitmap.isRecycled) {
            memory.put(digest, bitmap)
        }
        trimIfNeeded()
    }

    @Synchronized
    fun remove(key: ThumbnailKey) {
        val digest = key.digest()
        memory.remove(digest)
        val file = File(dir, "$digest.jpg")
        val removedBytes = if (file.isFile) file.length() else 0L
        if (file.delete()) diskSize.recordDelete(removedBytes)
    }

    /** Delete all cached thumbnails (disk + memory). */
    @Synchronized
    fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        diskSize.set(scanJpegBytes())
    }

    @Synchronized
    fun approximateSizeBytes(): Long = diskSize.bytes { scanJpegBytes() }

    private fun trimIfNeeded() {
        var total = diskSize.bytes { scanJpegBytes() }
        if (total <= maxBytes) return
        val ordered = jpegFiles().sortedBy { it.lastModified() }
        for (file in ordered) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) {
                total -= len
                val digest = file.name.removeSuffix(".jpg")
                memory.remove(digest)
            }
        }
        diskSize.set(total)
    }

    @Throws(IOException::class)
    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create thumbnail cache directory")
        }
    }

    private fun scanJpegBytes(): Long = jpegFiles().sumOf { it.length() }

    private fun jpegFiles(): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            .orEmpty()

    companion object {
        const val SUBDIR: String = "thumbnails"
        const val DEFAULT_MAX_BYTES: Long = 80L * 1024L * 1024L // 80 MiB
        const val DEFAULT_MAX_MEMORY_BYTES: Long = 16L * 1024L * 1024L // 16 MiB
        const val DEFAULT_MAX_MEMORY_ENTRIES: Int = 64

        fun fromContext(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES): ThumbnailDiskCache =
            ThumbnailDiskCache(
                rootDir = context.applicationContext.cacheDir,
                maxBytes = maxBytes,
            )
    }
}

/** Cached byte accounting; callers serialize filesystem mutations. */
internal class ThumbnailDiskSizeIndex {
    private var knownBytes: Long? = null

    fun bytes(scan: () -> Long): Long =
        knownBytes ?: scan().coerceAtLeast(0L).also { knownBytes = it }

    fun recordWrite(replacedBytes: Long, writtenBytes: Long, scan: () -> Long): Long {
        val known = knownBytes
        val next = if (known == null) {
            scan().coerceAtLeast(0L)
        } else {
            (known - replacedBytes.coerceAtLeast(0L)).coerceAtLeast(0L) +
                writtenBytes.coerceAtLeast(0L)
        }
        knownBytes = next
        return next
    }

    fun recordDelete(removedBytes: Long) {
        knownBytes = knownBytes?.let { known ->
            (known - removedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        }
    }

    fun set(bytes: Long) {
        knownBytes = bytes.coerceAtLeast(0L)
    }

    fun invalidate() {
        knownBytes = null
    }
}
