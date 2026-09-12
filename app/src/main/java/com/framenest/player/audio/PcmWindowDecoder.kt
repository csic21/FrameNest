package com.framenest.player.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RawRes
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs, preferredAudioTrackOrdinal) { it.setDataSource(path) }
    }

    suspend fun decodeRaw(
        context: Context,
        @RawRes resId: Int,
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        val afd = context.resources.openRawResourceFd(resId)
        try {
            decode(startMs, endMs, preferredAudioTrackOrdinal) {
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
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs, preferredAudioTrackOrdinal) {
            // The framework overload handles UNKNOWN_LENGTH by using the whole FD.
            it.setDataSource(afd)
        }
    }

    suspend fun decodeFd(
        fd: FileDescriptor,
        offset: Long,
        length: Long,
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs, preferredAudioTrackOrdinal) {
            if (length < 0L) {
                it.setDataSource(fd)
            } else {
                it.setDataSource(fd, offset, length)
            }
        }
    }

    /** Decode from an app-provided random-access source (for example SMB). */
    suspend fun decodeMediaDataSource(
        source: MediaDataSource,
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int? = null,
    ): ShortArray = withContext(Dispatchers.IO) {
        decode(startMs, endMs, preferredAudioTrackOrdinal) { it.setDataSource(source) }
    }

    private suspend fun decode(
        startMs: Long,
        endMs: Long,
        preferredAudioTrackOrdinal: Int?,
        configure: (MediaExtractor) -> Unit,
    ): ShortArray {
        require(endMs > startMs) { "endMs > startMs" }
        val startUs = startMs.coerceAtLeast(0L) * 1000L
        val endUs = endMs.coerceAtLeast(startMs + 1L) * 1000L

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            configure(extractor)
            val track = selectAudioTrack(extractor, preferredAudioTrackOrdinal)
                ?: error("系统解码器未从当前容器识别到音轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("系统解码器识别到音轨，但没有返回编码格式")
            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)

            val codecName = cachedDecoderName(
                mime = mime,
                lookup = {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                },
            ) ?: throw UnsupportedOperationException(
                "设备不支持听译音轨编码 $mime（视频仍可继续播放）",
            )
            codec = try {
                MediaCodec.createByCodecName(codecName)
            } catch (t: Throwable) {
                // Cached entry went stale (e.g. codec disabled); drop it and
                // re-query once before surfacing the failure.
                decoderNameCache.remove(mime, codecName)
                val fresh = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(format)
                    ?.also { decoderNameCache[mime] = it }
                    ?: throw t
                MediaCodec.createByCodecName(fresh)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            // Seek near start (closest previous sync).
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val pcmOutput = PcmSampleAccumulator(
                initialCapacity = expectedPcm16kSamples(endMs - startMs),
            )
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = format.getIntegerOr(
                MediaFormat.KEY_PCM_ENCODING,
                /* AudioFormat.ENCODING_PCM_16BIT */ 2,
            )
            val maxDecodeMs = (10_000L + (endMs - startMs) * 5L).coerceAtMost(60_000L)
            val deadlineNs = System.nanoTime() + maxDecodeMs * 1_000_000L

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() <= deadlineNs) {
                    "PCM decoder timed out for ${startMs}ms..${endMs}ms"
                }
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
                        pcmEncoding = of.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    }
                    else -> if (outIndex >= 0) {
                        val pts = info.presentationTimeUs
                        if (info.size > 0 &&
                            pts + infoSizeUs(info, sampleRate, channels, pcmEncoding) >= startUs
                        ) {
                            if (pts < endUs) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                val chunk = outBuf.duplicate().apply {
                                    position(info.offset)
                                    limit(info.offset + info.size)
                                }
                                pcmEncoding = codec.outputFormat.getIntegerOr(
                                    MediaFormat.KEY_PCM_ENCODING,
                                    pcmEncoding,
                                )
                                val mono = when (pcmEncoding) {
                                    // AudioFormat.ENCODING_PCM_FLOAT = 4
                                    4 -> PcmAudioMath.floatToMono16(
                                        chunk,
                                        channels.coerceAtLeast(1),
                                    )
                                    else -> PcmAudioMath.toMonoSamples(
                                        chunk,
                                        channels.coerceAtLeast(1),
                                    )
                                }
                                // Trim samples before startMs / after endMs roughly by pts.
                                val clipped = clipPcmByPts(
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
                                if (r16.isNotEmpty()) {
                                    pcmOutput.append(r16)
                                }
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
            val mono16k = pcmOutput.toShortArray()
            check(mono16k.isNotEmpty()) {
                "系统解码器已打开音轨 $mime，但未输出 PCM"
            }
            return mono16k
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(
        extractor: MediaExtractor,
        preferredAudioTrackOrdinal: Int?,
    ): Int? {
        val audioTracks = mutableListOf<Int>()
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) audioTracks += i
        }
        return chooseAudioTrack(audioTracks, preferredAudioTrackOrdinal)
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun infoSizeUs(
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
    ): Long {
        val bytesPerChannel = if (pcmEncoding == 4) 4 else 2
        val bytesPerFrame = bytesPerChannel * channels.coerceAtLeast(1)
        if (bytesPerFrame <= 0 || sampleRate <= 0 || info.size <= 0) return 0L
        val frames = info.size / bytesPerFrame
        return frames * 1_000_000L / sampleRate
    }
}

internal fun clipPcmByPts(
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
    if (dropFront == 0 && keepUntil == mono.size) return mono
    return mono.copyOfRange(dropFront, keepUntil)
}

internal fun expectedPcm16kSamples(durationMs: Long): Int =
    durationMs
        .coerceIn(0L, MAX_PCM_PREALLOC_DURATION_MS)
        .toInt() * (PcmAudioMath.TARGET_SAMPLE_RATE_HZ / 1_000)

/**
 * Contiguous PCM builder. The normal 3–9 second path is pre-sized and returns its backing array;
 * longer or codec-rounded output grows safely and is trimmed once at completion.
 */
internal class PcmSampleAccumulator(initialCapacity: Int) {
    private var samples = ShortArray(initialCapacity.coerceAtLeast(0))
    private var size = 0

    fun append(incoming: ShortArray) {
        if (incoming.isEmpty()) return
        check(incoming.size <= Int.MAX_VALUE - size) { "PCM window is too large" }
        val required = size + incoming.size
        ensureCapacity(required)
        incoming.copyInto(samples, destinationOffset = size)
        size = required
    }

    fun toShortArray(): ShortArray =
        if (size == samples.size) samples else samples.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= samples.size) return
        val doubled = if (samples.size <= Int.MAX_VALUE / 2) {
            (samples.size * 2).coerceAtLeast(MIN_PCM_GROWTH_SAMPLES)
        } else {
            Int.MAX_VALUE
        }
        samples = samples.copyOf(maxOf(required, doubled))
    }
}

private const val MAX_PCM_PREALLOC_DURATION_MS: Long = 10_000L
private const val MIN_PCM_GROWTH_SAMPLES: Int = 1_024

internal fun chooseAudioTrack(
    extractorTrackIndices: List<Int>,
    preferredAudioTrackOrdinal: Int?,
): Int? {
    if (extractorTrackIndices.isEmpty()) return null
    val ordinal = preferredAudioTrackOrdinal ?: 0
    return extractorTrackIndices.getOrNull(ordinal) ?: extractorTrackIndices.first()
}

/**
 * Decoder-name cache keyed by MIME.
 *
 * Building [MediaCodecList] and calling `findDecoderForFormat` enumerates
 * every codec on the device; listen-translate previously paid that cost once
 * per 3-second window. The audio MIME → decoder mapping is stable per boot,
 * so cache it and only re-query when `createByCodecName` rejects the entry.
 */
private val decoderNameCache = ConcurrentHashMap<String, String>()

internal fun cachedDecoderName(mime: String, lookup: () -> String?): String? {
    decoderNameCache[mime]?.let { return it }
    val fresh = lookup() ?: return null
    decoderNameCache[mime] = fresh
    return fresh
}

internal fun clearCachedDecoderNamesForTest() {
    decoderNameCache.clear()
}
