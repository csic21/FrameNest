package com.framenest.player.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RawRes
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes one audio time window to 16 kHz mono PCM (product path for FN-14 ASR).
 * Uses MediaExtractor + MediaCodec only (no libVLC audio callbacks).
 */
class PcmWindowDecoder {

    suspend fun decodeFile(
        path: String,
        startMs: Long,
        endMs: Long,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs) { it.setDataSource(path) }
    }

    suspend fun decodeRaw(
        context: Context,
        @RawRes resId: Int,
        startMs: Long,
        endMs: Long,
    ): ShortArray = withContext(Dispatchers.IO) {
        val afd = context.resources.openRawResourceFd(resId)
        try {
            decode(startMs, endMs) {
                it.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
        } finally {
            runCatching { afd.close() }
        }
    }

    /**
     * Decode using an already-open [AssetFileDescriptor]. Does not close [afd].
     */
    suspend fun decodeAfd(
        afd: AssetFileDescriptor,
        startMs: Long,
        endMs: Long,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs) {
            it.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
    }

    suspend fun decodeFd(
        fd: FileDescriptor,
        offset: Long,
        length: Long,
        startMs: Long,
        endMs: Long,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs) {
            it.setDataSource(fd, offset, length)
        }
    }

    private fun decode(
        startMs: Long,
        endMs: Long,
        configure: (MediaExtractor) -> Unit,
    ): ShortArray {
        require(endMs > startMs) { "endMs > startMs" }
        val startUs = startMs.coerceAtLeast(0L) * 1000L
        val endUs = endMs.coerceAtLeast(startMs + 1L) * 1000L

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            configure(extractor)
            val track = selectAudioTrack(extractor) ?: return ShortArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Seek near start (closest previous sync).
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mono16k = ArrayList<Short>(
                (((endMs - startMs).coerceAtLeast(1L) * PcmAudioMath.TARGET_SAMPLE_RATE_HZ) / 1000L)
                    .toInt()
                    .coerceAtLeast(1),
            )
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > endUs + 200_000L) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = of.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                    else -> if (outIndex >= 0) {
                        val pts = info.presentationTimeUs
                        if (info.size > 0 && pts + infoSizeUs(info, sampleRate, channels) >= startUs) {
                            if (pts < endUs) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                val chunk = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.get(chunk)
                                val pcmEncoding = codec.outputFormat.getIntegerOr(
                                    MediaFormat.KEY_PCM_ENCODING,
                                    /* ENCODING_PCM_16BIT */ 2,
                                )
                                val mono = when (pcmEncoding) {
                                    // AudioFormat.ENCODING_PCM_FLOAT = 4
                                    4 -> floatLeToMono16(chunk, channels)
                                    else -> PcmAudioMath.toMonoSamples(chunk, channels.coerceAtLeast(1))
                                }
                                // Trim samples before startMs / after endMs roughly by pts.
                                val clipped = clipByPts(
                                    mono = mono,
                                    ptsUs = pts,
                                    startUs = startUs,
                                    endUs = endUs,
                                    sourceRateHz = sampleRate.coerceAtLeast(1),
                                )
                                val r16 = PcmAudioMath.resampleMonoTo16k(
                                    clipped,
                                    sampleRate.coerceAtLeast(1),
                                )
                                for (s in r16) mono16k.add(s)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (pts >= endUs) {
                            outputDone = true
                        }
                    }
                }
            }
            return mono16k.toShortArray()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun infoSizeUs(info: MediaCodec.BufferInfo, sampleRate: Int, channels: Int): Long {
        // Rough duration of this buffer for 16-bit PCM.
        val bytesPerSample = 2 * channels.coerceAtLeast(1)
        if (bytesPerSample <= 0 || sampleRate <= 0 || info.size <= 0) return 0L
        val frames = info.size / bytesPerSample
        return frames * 1_000_000L / sampleRate
    }

    private fun clipByPts(
        mono: ShortArray,
        ptsUs: Long,
        startUs: Long,
        endUs: Long,
        sourceRateHz: Int,
    ): ShortArray {
        if (mono.isEmpty()) return mono
        val frameUs = 1_000_000.0 / sourceRateHz.coerceAtLeast(1)
        val dropFront = if (ptsUs >= startUs) {
            0
        } else {
            (((startUs - ptsUs) / frameUs).toInt()).coerceIn(0, mono.size)
        }
        val keepUntil = if (ptsUs >= endUs) {
            0
        } else {
            val maxFrames = (((endUs - ptsUs) / frameUs).toInt()).coerceAtLeast(0)
            maxFrames.coerceAtMost(mono.size)
        }
        if (dropFront >= keepUntil) return ShortArray(0)
        return mono.copyOfRange(dropFront, keepUntil)
    }

    private fun floatLeToMono16(pcm: ByteArray, channelCount: Int): ShortArray {
        val ch = channelCount.coerceAtLeast(1)
        val floatsPerFrame = ch
        val frameCount = pcm.size / (4 * floatsPerFrame)
        val out = ShortArray(frameCount)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until ch) {
                sum += bb.float
            }
            val avg = (sum / ch).coerceIn(-1f, 1f)
            out[i] = (avg * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
