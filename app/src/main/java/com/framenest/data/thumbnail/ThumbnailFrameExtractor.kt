package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.framenest.player.SmbSeekableMedia
import com.framenest.smb.SmbRandomAccess
import java.io.ByteArrayOutputStream

/**
 * Extracts a single list thumbnail frame from seekable SMB data using
 * [MediaMetadataRetriever] + [SmbSeekableMedia] proxy FD (decision 0002 path B).
 *
 * Does **not** create a libVLC player instance.
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
     * Try candidate timestamps until a non-black frame is obtained.
     * @return null when all candidates fail
     */
    fun extract(randomAccess: SmbRandomAccess, debugLabel: String = "thumb"): ExtractResult? {
        val opened = SmbSeekableMedia.open(
            context = appContext,
            randomAccess = randomAccess,
            debugLabel = debugLabel,
        )
        val afd = opened.assetFileDescriptor
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val candidates = ThumbnailCandidatePolicy.candidateTimestampsMs(durationMs)
            for (timeMs in candidates) {
                val frame = getFrameAt(retriever, timeMs) ?: continue
                if (isBlack(frame)) {
                    Log.d(TAG, "black frame at ${timeMs}ms label=$debugLabel")
                    if (!frame.isRecycled) frame.recycle()
                    continue
                }
                val scaled = scaleDown(frame)
                if (scaled !== frame && !frame.isRecycled) {
                    frame.recycle()
                }
                val jpeg = compressJpeg(scaled)
                return ExtractResult(
                    bitmap = scaled,
                    jpegBytes = jpeg,
                    usedTimestampMs = timeMs,
                    durationMs = durationMs,
                )
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed label=$debugLabel: ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { afd.close() }
        }
    }

    private fun getFrameAt(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val timeUs = timeMs.coerceAtLeast(0L) * 1000L
        return try {
            // OPTION_CLOSEST_SYNC is cheap and good enough for list covers.
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
            Log.d(TAG, "getFrameAtTime failed t=${timeMs}ms: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun isBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val samples = ThumbnailBlackFrame.sampleGrid(w, h, samplesPerSide = 8) { x, y ->
            bitmap.getPixel(x, y)
        }
        return ThumbnailBlackFrame.isBlackFrame(samples)
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
        const val DEFAULT_MAX_EDGE_PX: Int = 320
        const val DEFAULT_JPEG_QUALITY: Int = 80
    }
}
