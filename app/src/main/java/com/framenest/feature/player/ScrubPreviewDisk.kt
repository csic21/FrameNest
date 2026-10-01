package com.framenest.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * JPEG strip for scrub previews. Created on first write, not at process start.
 * A replaced file gets a new digest because size and mtime are part of the key.
 */
internal class ScrubPreviewDisk(
    private val root: File,
    private val maxBytes: Long = ScrubPreviewPlan.MAX_DISK_BYTES,
) {
    fun read(digest: String, bucketStartMs: Long): Bitmap? {
        val file = file(digest, bucketStartMs)
        if (!file.isFile) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        if (decoded == null) {
            file.delete()
            return null
        }
        return decoded
    }

    fun write(digest: String, bucketStartMs: Long, bitmap: Bitmap) {
        val bytes = ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return
            out.toByteArray()
        }
        val dir = File(root, digest)
        if (!dir.exists() && !dir.mkdirs()) return
        evict(bytes.size.toLong())
        val dest = file(digest, bucketStartMs)
        val tmp = File(dest.absolutePath + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun evict(incomingBytes: Long) {
        if (!root.exists()) return
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "jpg" }
            .toList()
        val victims = ScrubPreviewPlan.filesToEvict(
            files = files.map { file ->
                ScrubPreviewPlan.CacheFile(
                    name = file.absolutePath,
                    bytes = file.length(),
                    modifiedAtMs = file.lastModified(),
                )
            },
            incomingBytes = incomingBytes,
            maxBytes = maxBytes,
        )
        victims.forEach { path -> File(path).delete() }
    }

    private fun file(digest: String, bucketStartMs: Long): File =
        File(File(root, digest), "$bucketStartMs.jpg")

    companion object {
        const val JPEG_QUALITY: Int = 72
    }
}
