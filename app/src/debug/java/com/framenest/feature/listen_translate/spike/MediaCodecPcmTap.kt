package com.framenest.feature.listen_translate.spike

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RawRes
import com.framenest.player.audio.PcmAudioMath
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * FN-10 spike: decode audio track to PCM with [MediaExtractor] + [MediaCodec]
 * while the UI player (libVLC) runs in parallel. Does not use VLC audio callbacks
 * (unavailable on libvlc-all 3.6.5 Java bindings — decision 0005 path B).
 *
 * Temporary work stays in process memory only (no public storage).
 */
class MediaCodecPcmTap(
    appContext: Context,
) {
    private val appContext = appContext.applicationContext
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val _stats = MutableStateFlow(PcmTapStats())
    val stats: StateFlow<PcmTapStats> = _stats.asStateFlow()

    fun startFromRaw(@RawRes resId: Int) {
        stop()
        val afd = appContext.resources.openRawResourceFd(resId)
        startInternal(
            configure = { extractor ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            },
            closeAfd = { runCatching { afd.close() } },
            label = "raw:$resId",
        )
    }

    fun startFromPath(path: String) {
        stop()
        startInternal(
            configure = { extractor -> extractor.setDataSource(path) },
            closeAfd = {},
            label = "path",
        )
    }

    fun stop() {
        running.set(false)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun startInternal(
        configure: (MediaExtractor) -> Unit,
        closeAfd: () -> Unit,
        label: String,
    ) {
        val ht = HandlerThread("PcmTap-$label").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h
        running.set(true)
        _stats.value = PcmTapStats(phase = PcmTapPhase.Starting, label = label)

        h.post {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                configure(extractor)
                val track = selectAudioTrack(extractor)
                    ?: error("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: error("Missing mime")
                val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
                val channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                _stats.update {
                    it.copy(
                        phase = PcmTapPhase.Running,
                        sourceSampleRateHz = sampleRate,
                        sourceChannelCount = channels,
                        mime = mime,
                    )
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var totalPcmBytes = 0L
                var totalMono16kSamples = 0L
                var peakRms = 0f
                var lastPtsUs = 0L

                while (running.get() && !outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            val sr = of.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            val ch = of.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            _stats.update {
                                it.copy(sourceSampleRateHz = sr, sourceChannelCount = ch)
                            }
                        }
                        outIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.get(chunk)
                                totalPcmBytes += chunk.size.toLong()
                                lastPtsUs = bufferInfo.presentationTimeUs

                                val sr = _stats.value.sourceSampleRateHz.coerceAtLeast(1)
                                val ch = _stats.value.sourceChannelCount.coerceAtLeast(1)
                                val mono = PcmAudioMath.toMonoSamples(chunk, ch)
                                val mono16k = PcmAudioMath.resampleMonoTo16k(mono, sr)
                                totalMono16kSamples += mono16k.size.toLong()
                                val rms = PcmAudioMath.rmsNormalized(mono16k)
                                if (rms > peakRms) peakRms = rms

                                _stats.update {
                                    it.copy(
                                        pcmBytesReceived = totalPcmBytes,
                                        mono16kSamples = totalMono16kSamples,
                                        lastPresentationMs = lastPtsUs / 1000L,
                                        peakRms = peakRms,
                                        lastRms = rms,
                                        chunks = it.chunks + 1,
                                    )
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                _stats.update {
                    it.copy(
                        phase = if (running.get()) PcmTapPhase.Completed else PcmTapPhase.Stopped,
                    )
                }
                Log.i(
                    TAG,
                    "pcm_tap done label=$label bytes=$totalPcmBytes " +
                        "mono16k=$totalMono16kSamples peakRms=$peakRms",
                )
            } catch (t: Throwable) {
                val msg = t.message?.take(200) ?: t.javaClass.simpleName
                Log.w(TAG, "pcm_tap failed: $msg")
                _stats.update {
                    it.copy(phase = PcmTapPhase.Error, errorMessage = msg)
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
                closeAfd()
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val TAG = "FrameNestPcmTap"
    }
}

enum class PcmTapPhase {
    Idle,
    Starting,
    Running,
    Completed,
    Stopped,
    Error,
}

data class PcmTapStats(
    val phase: PcmTapPhase = PcmTapPhase.Idle,
    val label: String = "",
    val mime: String = "",
    val sourceSampleRateHz: Int = 0,
    val sourceChannelCount: Int = 0,
    val pcmBytesReceived: Long = 0L,
    val mono16kSamples: Long = 0L,
    val lastPresentationMs: Long = 0L,
    val peakRms: Float = 0f,
    val lastRms: Float = 0f,
    val chunks: Long = 0L,
    val errorMessage: String = "",
) {
    /** Approximate decoded mono-16k duration from sample count. */
    val coveredMsApprox: Long
        get() = if (mono16kSamples <= 0L) {
            0L
        } else {
            mono16kSamples * 1000L / PcmAudioMath.TARGET_SAMPLE_RATE_HZ
        }
}
