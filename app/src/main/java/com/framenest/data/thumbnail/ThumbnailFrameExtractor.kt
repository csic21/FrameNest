package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.framenest.smb.SmbRandomAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Bounded SMB prefix copied to a local file, then decoded with
 * [MediaMetadataRetriever].
 *
 * The folder list does not call this. A short prefix still produced no frame
 * for the files that stayed on the placeholder, and the copy made every miss
 * slow. Scrub preview does not use it either.
 *
 * Does **not** create a libVLC player instance, and does **not** open a proxy
 * file descriptor.
 */
class ThumbnailFrameExtractor(
    private val appContext: Context,
    private val targetMaxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {
    data class ExtractResult(
        val bitmap: Bitmap,
        val jpegBytes: ByteArray,
        val usedTimestampMs: Long,
        val durationMs: Long,
    )

    /**
     * Copy a prefix, then try early timestamps until a usable frame is obtained.
     * @return null when the prefix is empty or every candidate fails
     */
    fun extract(randomAccess: SmbRandomAccess): ExtractResult? {
        val fileBytes = randomAccess.size.coerceAtLeast(0L)
        val prefix = File.createTempFile("thumb-prefix-", ".bin", appContext.cacheDir)
        return try {
            val written = copyPrefix(randomAccess, prefix)
            if (written <= 0L) {
                Log.w(TAG, "prefix empty fileBytes=$fileBytes")
                return null
            }
            val segmentUnknown = markMkvSegmentUnknown(prefix)
            Log.i(
                TAG,
                "prefix ready fileBytes=$fileBytes prefixBytes=$written segmentUnknown=$segmentUnknown",
            )
            readPrefix(prefix, fileBytes, written)
        } catch (t: Throwable) {
            if (t is java.util.concurrent.CancellationException) throw t
            Log.w(TAG, "extract failed: ${t.javaClass.simpleName}")
            null
        } finally {
            prefix.delete()
        }
    }

    private fun readPrefix(prefix: File, fileBytes: Long, written: Long): ExtractResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(prefix.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            embeddedCover(retriever, durationMs)?.let { cover ->
                Log.i(TAG, "prefix embedded fileBytes=$fileBytes prefixBytes=$written")
                return cover
            }
            val videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val dst = scaledDstSize(videoW, videoH, targetMaxEdgePx)
            val candidates = ThumbnailPrefixPlan.timestampsMs(durationMs)
            for (timeMs in candidates) {
                val frame = getFrameAt(retriever, timeMs, dst) ?: continue
                if (rejectFrame(frame)) {
                    Log.d(TAG, "unusable frame at ${timeMs}ms")
                    if (!frame.isRecycled) frame.recycle()
                    continue
                }
                val scaled = scaleDown(frame)
                if (scaled !== frame && !frame.isRecycled) {
                    frame.recycle()
                }
                val jpeg = compressJpeg(scaled)
                Log.i(
                    TAG,
                    "prefix frame t=${timeMs}ms dur=${durationMs}ms " +
                        "fileBytes=$fileBytes prefixBytes=$written",
                )
                return ExtractResult(
                    bitmap = scaled,
                    jpegBytes = jpeg,
                    usedTimestampMs = timeMs,
                    durationMs = durationMs,
                )
            }
            Log.w(TAG, "prefix no frame fileBytes=$fileBytes prefixBytes=$written dur=${durationMs}ms")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun copyPrefix(randomAccess: SmbRandomAccess, dest: File): Long {
        val want = ThumbnailPrefixPlan.prefixBytes(randomAccess.size)
        if (want <= 0L) return 0L
        val buffer = ByteArray(COPY_CHUNK_BYTES)
        var written = 0L
        dest.outputStream().use { out ->
            while (written < want) {
                val request = minOf(buffer.size.toLong(), want - written).toInt()
                val n = randomAccess.readAt(written, buffer, 0, request)
                if (n <= 0) break
                out.write(buffer, 0, n)
                written += n
            }
        }
        return written
    }

    private fun markMkvSegmentUnknown(prefix: File): Boolean {
        RandomAccessFile(prefix, "rw").use { file ->
            val header = ByteArray(MKV_HEADER_SCAN_BYTES)
            val read = file.read(header)
            if (read <= 0) return false
            val field = ThumbnailPrefixPlan.mkvSegmentSizeField(header.copyOf(read)) ?: return false
            file.seek(field.first.toLong())
            file.write(ByteArray(field.last - field.first + 1) { 0xFF.toByte() })
            return true
        }
    }

    private fun embeddedCover(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
    ): ExtractResult? {
        val bytes = try {
            retriever.embeddedPicture
        } catch (t: Throwable) {
            Log.d(TAG, "embeddedPicture failed: ${t.javaClass.simpleName}")
            null
        } ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            Log.d(TAG, "embedded decode failed: ${t.javaClass.simpleName}")
            null
        } ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        if (rejectFrame(bitmap)) {
            Log.d(TAG, "embedded cover unusable, fall back to frame decode")
            if (!bitmap.isRecycled) bitmap.recycle()
            return null
        }
        val scaled = scaleDown(bitmap)
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return ExtractResult(
            bitmap = scaled,
            jpegBytes = compressJpeg(scaled),
            usedTimestampMs = 0L,
            durationMs = durationMs,
        )
    }

    private fun getFrameAt(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        dst: Pair<Int, Int>? = null,
    ): Bitmap? {
        val timeUs = timeMs.coerceAtLeast(0L) * 1000L
        return try {
            // Scaled decode first. A null scaled frame falls back to a full frame,
            // then to the nearest decoded frame.
            val scaled = if (dst != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dst.first,
                        dst.second,
                    )
                }.getOrNull()
            } else {
                null
            }
            scaled
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (t: Throwable) {
            Log.d(TAG, "getFrameAtTime failed t=${timeMs}ms: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun rejectFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val samples = ThumbnailBlackFrame.sampleGrid(w, h, samplesPerSide = 8) { x, y ->
            bitmap.getPixel(x, y)
        }
        return ThumbnailBlackFrame.isBlackFrame(samples) ||
            ThumbnailBlackFrame.isLowInformation(samples)
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= targetMaxEdgePx) return source
        val scale = targetMaxEdgePx.toFloat() / maxEdge.toFloat()
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun compressJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "FrameNestThumb"
        private const val COPY_CHUNK_BYTES: Int = 1024 * 1024
        private const val MKV_HEADER_SCAN_BYTES: Int = 4096
        const val DEFAULT_MAX_EDGE_PX: Int = 320
        const val DEFAULT_JPEG_QUALITY: Int = 80

        /**
         * Target size for [MediaMetadataRetriever.getScaledFrameAtTime] that
         * preserves aspect ratio with the longest edge at [maxEdgePx].
         * Returns null when source dimensions are unknown (caller falls back
         * to full-res decode + [scaleDown] equivalent).
         */
        fun scaledDstSize(videoWidth: Int, videoHeight: Int, maxEdgePx: Int): Pair<Int, Int>? {
            if (videoWidth <= 0 || videoHeight <= 0 || maxEdgePx <= 0) return null
            val maxEdge = maxOf(videoWidth, videoHeight)
            if (maxEdge <= maxEdgePx) return Pair(videoWidth, videoHeight)
            val scale = maxEdgePx.toDouble() / maxEdge.toDouble()
            val w = (videoWidth * scale).toInt().coerceAtLeast(1)
            val h = (videoHeight * scale).toInt().coerceAtLeast(1)
            return Pair(w, h)
        }
    }
}
