package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/**
 * Disk cache for list thumbnails under the app cache directory.
 *
 * Layout: `[cacheDir]/thumbnails/<digest>.c3.jpg`, plus a sibling duration file.
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
    private val durations = HashMap<String, Long>()

    fun fileFor(key: ThumbnailKey): File = File(dir, jpegName(key.digest()))

    /** Pre-revision cover. Not a hit: generation of [fileFor] still has to run. */
    fun legacyJpegFile(key: ThumbnailKey): File = File(dir, "${key.digest()}.jpg")

    @Synchronized
    fun has(key: ThumbnailKey): Boolean = fileFor(key).isFile

    /**
     * Decode an older `<digest>.jpg` while the revised cover is missing.
     * The bitmap is not stored in the memory cache, so a later revised file
     * can replace it.
     */
    @Synchronized
    fun readLegacyBitmap(key: ThumbnailKey): Bitmap? {
        if (fileFor(key).isFile) return null
        val legacy = legacyJpegFile(key)
        if (!legacy.isFile) return null
        val decoded = BitmapFactory.decodeFile(legacy.absolutePath) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) {
            if (!decoded.isRecycled) decoded.recycle()
            return null
        }
        return decoded
    }

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
        val file = fileFor(key)
        if (!file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        if (decoded == null) {
            val corruptBytes = file.length()
            if (deleteJpeg(file)) diskSize.recordDelete(corruptBytes) else diskSize.invalidate()
            return null
        }
        memory.put(digest, decoded)
        rememberDuration(digest)
        return decoded
    }

    /**
     * Persist JPEG bytes and update the in-memory map when [bitmap] is provided.
     */
    @Throws(IOException::class)
    @Synchronized
    fun put(
        key: ThumbnailKey,
        jpegBytes: ByteArray,
        bitmap: Bitmap? = null,
        durationMs: Long = 0L,
    ) {
        ensureDirectory()
        val digest = key.digest()
        val file = fileFor(key)
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
        writeDuration(digest, durationMs)
        trimIfNeeded()
    }

    /** Duration already loaded with a memory bitmap. Does not touch disk. */
    @Synchronized
    fun cachedDurationMs(key: ThumbnailKey): Long = durations[key.digest()] ?: 0L

    /** Duration stored beside the JPEG. Safe on the extraction worker. */
    @Synchronized
    fun readDurationMs(key: ThumbnailKey): Long {
        val digest = key.digest()
        durations[digest]?.let { return it }
        rememberDuration(digest)
        return durations[digest] ?: 0L
    }

    @Synchronized
    fun remove(key: ThumbnailKey) {
        val digest = key.digest()
        memory.remove(digest)
        durations.remove(digest)
        val file = fileFor(key)
        val removedBytes = if (file.isFile) file.length() else 0L
        if (deleteJpeg(file)) diskSize.recordDelete(removedBytes)
    }

    /** Delete all cached thumbnails (disk + memory). */
    @Synchronized
    fun clear() {
        memory.clear()
        durations.clear()
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
            if (deleteJpeg(file)) {
                total -= len
                val digest = digestOfJpeg(file)
                if (file.name == jpegName(digest)) {
                    memory.remove(digest)
                    durations.remove(digest)
                }
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

    private fun rememberDuration(digest: String) {
        val file = File(dir, durationName(digest))
        val value = if (file.isFile) {
            file.readText().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        durations[digest] = value
    }

    private fun writeDuration(digest: String, durationMs: Long) {
        val stored = durationMs.coerceAtLeast(0L)
        durations[digest] = stored
        val file = File(dir, durationName(digest))
        val tmp = File(file.absolutePath + ".tmp")
        try {
            tmp.writeText(stored.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun deleteJpeg(file: File): Boolean {
        if (!file.isFile) return false
        val deleted = file.delete()
        if (deleted) {
            File(file.parentFile, file.name.removeSuffix(".jpg") + ".dur").delete()
        }
        return deleted
    }

    private fun digestOfJpeg(file: File): String =
        file.name.removeSuffix(".$FILE_REVISION.jpg").removeSuffix(".jpg")

    companion object {
        /** New covers use this suffix so older 10s frames are not reused. */
        const val FILE_REVISION: String = "c3"
        const val SUBDIR: String = "thumbnails"

        fun jpegName(digest: String): String = "$digest.$FILE_REVISION.jpg"

        fun durationName(digest: String): String = "$digest.$FILE_REVISION.dur"
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
