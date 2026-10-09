package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/** Disk work is serialized independently of the short memory-only UI lookup lock. */
class ThumbnailDiskCache(
    rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    private val decodeBitmap: (File) -> Bitmap? = { BitmapFactory.decodeFile(it.absolutePath) },
) {
    // Construction and memory getters never touch disk.
    private val dir = File(rootDir, SUBDIR)
    private val diskLock = Any()
    private val memoryLock = Any()
    private val memory = BoundedLruCache<String, Bitmap>(
        maxEntries = maxMemoryEntries,
        maxWeight = maxMemoryBytes,
        weightOf = { bitmap -> bitmap.byteCount.toLong() },
    )
    private val diskSize = ThumbnailDiskSizeIndex()
    private val durations = HashMap<String, Long>()
    // Accessed under memoryLock; disk work must recheck before publishing a decode.
    private var generation = 0L

    fun fileFor(key: ThumbnailKey): File = File(dir, jpegName(key.digest()))
    fun legacyJpegFile(key: ThumbnailKey): File = File(dir, "${key.digest()}.jpg")

    fun has(key: ThumbnailKey): Boolean = synchronized(diskLock) { fileFor(key).isFile }

    fun readLegacyBitmap(key: ThumbnailKey): Bitmap? {
        val expected = synchronized(memoryLock) { generation }
        return synchronized(diskLock) {
            if (!isCurrent(expected) || fileFor(key).isFile) return@synchronized null
            val legacy = legacyJpegFile(key)
            if (!legacy.isFile) return@synchronized null
            val decoded = decodeBitmap(legacy) ?: return@synchronized null
            if (decoded.width <= 0 || decoded.height <= 0) {
                if (!decoded.isRecycled) decoded.recycle()
                return@synchronized null
            }
            decoded
        }
    }

    fun getMemoryBitmap(key: ThumbnailKey): Bitmap? = synchronized(memoryLock) {
        memoryBitmap(key.digest())
    }

    /** Caller holds memoryLock. Evictions never recycle an image a row may still display. */
    private fun memoryBitmap(digest: String): Bitmap? {
        memory[digest]?.let { cached ->
            if (!cached.isRecycled) return cached
            memory.remove(digest)
        }
        return null
    }

    fun getBitmap(key: ThumbnailKey): Bitmap? {
        val digest = key.digest()
        val expected = synchronized(memoryLock) {
            memoryBitmap(digest)?.let { return it }
            generation
        }
        return synchronized(diskLock) {
            if (!isCurrent(expected)) return@synchronized null
            synchronized(memoryLock) { memoryBitmap(digest) }?.let { return@synchronized it }
            val file = fileFor(key)
            if (!file.isFile) return@synchronized null
            val decoded = decodeBitmap(file)
            if (decoded == null) {
                val corruptBytes = file.length()
                if (deleteJpeg(file)) diskSize.recordDelete(corruptBytes) else diskSize.invalidate()
                synchronized(memoryLock) {
                    memory.remove(digest)
                    durations.remove(digest)
                }
                return@synchronized null
            }
            val duration = readDuration(digest)
            synchronized(memoryLock) {
                memory.put(digest, decoded)
                durations[digest] = duration
            }
            decoded
        }
    }

    @Throws(IOException::class)
    fun put(key: ThumbnailKey, jpegBytes: ByteArray, bitmap: Bitmap? = null, durationMs: Long = 0L) {
        val expected = synchronized(memoryLock) { generation }
        synchronized(diskLock) {
            if (!isCurrent(expected)) return
            ensureDirectory()
            val digest = key.digest()
            val file = fileFor(key)
            val replacedBytes = if (file.isFile) file.length() else 0L
            val tmp = File(file.absolutePath + ".tmp")
            try {
                tmp.outputStream().use { it.write(jpegBytes) }
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            } catch (failure: Throwable) {
                diskSize.invalidate()
                throw failure
            } finally {
                tmp.delete()
            }
            diskSize.recordWrite(replacedBytes, file.length()) { scanJpegBytes() }
            // Invalidate before fallible sidecar I/O: a failed replacement must
            // never keep the previous bitmap/duration for the new JPEG bytes.
            synchronized(memoryLock) {
                memory.remove(digest)
                durations.remove(digest)
            }
            val duration = durationMs.coerceAtLeast(0L)
            writeDuration(digest, duration)
            synchronized(memoryLock) {
                if (bitmap != null && !bitmap.isRecycled) memory.put(digest, bitmap)
                durations[digest] = duration
            }
            trimIfNeeded()
        }
    }

    /** Memory-only: safe even while decode/write/trim/clear is blocked on disk. */
    fun cachedDurationMs(key: ThumbnailKey): Long = synchronized(memoryLock) {
        durations[key.digest()] ?: 0L
    }

    fun readDurationMs(key: ThumbnailKey): Long {
        val digest = key.digest()
        val expected = synchronized(memoryLock) {
            durations[digest]?.let { return it }
            generation
        }
        return synchronized(diskLock) {
            if (!isCurrent(expected)) return@synchronized 0L
            val value = readDuration(digest)
            synchronized(memoryLock) { durations[digest] = value }
            value
        }
    }

    fun remove(key: ThumbnailKey) = synchronized(diskLock) {
        val digest = key.digest()
        synchronized(memoryLock) {
            memory.remove(digest)
            durations.remove(digest)
        }
        val file = fileFor(key)
        val removedBytes = if (file.isFile) file.length() else 0L
        if (deleteJpeg(file)) diskSize.recordDelete(removedBytes)
    }

    /** Disk serialization makes clear atomic with decode/put, without blocking UI getters. */
    fun clear() = synchronized(diskLock) {
        synchronized(memoryLock) {
            generation++
            memory.clear()
            durations.clear()
        }
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        // Failed deletions remain accounted for.
        diskSize.set(scanJpegBytes())
    }

    fun approximateSizeBytes(): Long = synchronized(diskLock) { diskSize.bytes { scanJpegBytes() } }
    private fun isCurrent(expected: Long): Boolean = synchronized(memoryLock) { generation == expected }

    /** Caller holds diskLock. Never acquire diskLock while holding memoryLock. */
    private fun trimIfNeeded() {
        var total = diskSize.bytes { scanJpegBytes() }
        if (total <= maxBytes) return
        for (file in jpegFiles().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = file.length()
            if (deleteJpeg(file)) {
                total -= len
                val digest = digestOfJpeg(file)
                if (file.name == jpegName(digest)) synchronized(memoryLock) {
                    memory.remove(digest)
                    durations.remove(digest)
                }
            }
        }
        diskSize.set(total)
    }

    private fun ensureDirectory() {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Unable to create thumbnail cache directory")
        }
    }

    private fun scanJpegBytes(): Long = jpegFiles().sumOf { it.length() }
    private fun jpegFiles(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()

    private fun readDuration(digest: String): Long {
        val file = File(dir, durationName(digest))
        return if (file.isFile) file.readText().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L
    }

    private fun writeDuration(digest: String, durationMs: Long) {
        val file = File(dir, durationName(digest))
        val tmp = File(file.absolutePath + ".tmp")
        try {
            tmp.writeText(durationMs.toString())
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        } finally {
            tmp.delete()
        }
    }

    private fun deleteJpeg(file: File): Boolean {
        if (!file.isFile) return false
        return file.delete().also { deleted ->
            if (deleted) File(file.parentFile, file.name.removeSuffix(".jpg") + ".dur").delete()
        }
    }

    private fun digestOfJpeg(file: File): String =
        file.name.removeSuffix(".$FILE_REVISION.jpg").removeSuffix(".jpg")

    companion object {
        const val FILE_REVISION: String = "c3"
        const val SUBDIR: String = "thumbnails"
        fun jpegName(digest: String): String = "$digest.$FILE_REVISION.jpg"
        fun durationName(digest: String): String = "$digest.$FILE_REVISION.dur"
        const val DEFAULT_MAX_BYTES: Long = 80L * 1024L * 1024L
        const val DEFAULT_MAX_MEMORY_BYTES: Long = 16L * 1024L * 1024L
        const val DEFAULT_MAX_MEMORY_ENTRIES: Int = 64
        fun fromContext(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES): ThumbnailDiskCache =
            ThumbnailDiskCache(rootDir = context.applicationContext.cacheDir, maxBytes = maxBytes)
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
