package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for list thumbnails under the app cache directory.
 *
 * Layout: `[cacheDir]/thumbnails/<digest>.jpg`
 *
 * Clearable for settings (FN-09) via [clear].
 */
class ThumbnailDiskCache(
    private val rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val dir: File = File(rootDir, SUBDIR).also { it.mkdirs() }
    private val memory = ConcurrentHashMap<String, Bitmap>()

    fun fileFor(key: ThumbnailKey): File = File(dir, "${key.digest()}.jpg")

    fun has(key: ThumbnailKey): Boolean = fileFor(key).isFile

    fun getBitmap(key: ThumbnailKey): Bitmap? {
        val digest = key.digest()
        memory[digest]?.let { cached ->
            if (!cached.isRecycled) return cached
            memory.remove(digest)
        }
        val file = fileFor(key)
        if (!file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        memory[digest] = decoded
        return decoded
    }

    /**
     * Persist JPEG bytes and update the in-memory map when [bitmap] is provided.
     */
    @Throws(IOException::class)
    fun put(key: ThumbnailKey, jpegBytes: ByteArray, bitmap: Bitmap? = null) {
        val file = fileFor(key)
        val tmp = File(file.absolutePath + ".tmp")
        tmp.outputStream().use { it.write(jpegBytes) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        if (bitmap != null && !bitmap.isRecycled) {
            memory[key.digest()] = bitmap
        }
        trimIfNeeded()
    }

    fun remove(key: ThumbnailKey) {
        memory.remove(key.digest())
        fileFor(key).delete()
    }

    /** Delete all cached thumbnails (disk + memory). */
    fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    fun approximateSizeBytes(): Long {
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun trimIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val ordered = files.sortedBy { it.lastModified() }
        for (file in ordered) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) {
                total -= len
                val digest = file.name.removeSuffix(".jpg")
                memory.remove(digest)
            }
        }
    }

    companion object {
        const val SUBDIR: String = "thumbnails"
        const val DEFAULT_MAX_BYTES: Long = 80L * 1024L * 1024L // 80 MiB

        fun fromContext(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES): ThumbnailDiskCache =
            ThumbnailDiskCache(
                rootDir = context.applicationContext.cacheDir,
                maxBytes = maxBytes,
            )
    }
}
