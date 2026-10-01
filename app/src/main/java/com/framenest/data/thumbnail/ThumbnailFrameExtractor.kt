package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.HandlerThread
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
    fun extract(
        randomAccess: SmbRandomAccess,
        debugLabel: String = "thumb",
        ioThread: HandlerThread? = null,
    ): ExtractResult? {
        val opened = SmbSeekableMedia.open(
            context = appContext,
            randomAccess = randomAccess,
            debugLabel = debugLabel,
            ioThread = ioThread,
        )
        val afd = opened.assetFileDescriptor
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            // Fast path: embedded cover art needs no video decode at all.
            embeddedCover(retriever, durationMs)?.let { return it }
            val videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val dst = scaledDstSize(videoW, videoH, targetMaxEdgePx)
            val candidates = ThumbnailCandidatePolicy.candidateTimestampsMs(durationMs)
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
            // OPTION_CLOSEST_SYNC is cheap and good enough for list covers.
            // API 27+ decodes directly at thumbnail size; older devices fall
            // back to full-res decode + scaleDown below.
            if (dst != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dst.first,
                        dst.second,
                    )
                } catch (t: Throwable) {
                    Log.d(TAG, "scaled frame failed, fallback full-res: ${t.javaClass.simpleName}")
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
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
