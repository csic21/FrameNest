package com.framenest.player.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure PCM helpers for listen-translate pre-process (FN-10+).
 * 16-bit little-endian interleaved PCM in, mono 16 kHz shorts out.
 */
object PcmAudioMath {

    const val TARGET_SAMPLE_RATE_HZ: Int = 16_000

    /**
     * Downmix interleaved 16-bit LE PCM to mono samples (one Short per frame).
     */
    fun toMonoSamples(pcmLe: ByteArray, channelCount: Int): ShortArray =
        toMonoSamples(ByteBuffer.wrap(pcmLe), channelCount)

    /**
     * Downmix the remaining bytes of [pcmLe] without copying the buffer or changing its position.
     */
    fun toMonoSamples(pcmLe: ByteBuffer, channelCount: Int): ShortArray {
        require(channelCount >= 1) { "channelCount >= 1" }
        val view = pcmLe.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = view.remaining() / (2 * channelCount)
        val out = ShortArray(frameCount)
        var byteIndex = view.position()
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) {
                sum += view.getShort(byteIndex).toInt()
                byteIndex += 2
            }
            out[i] = (sum / channelCount).toShort()
        }
        return out
    }

    /**
     * Downmix interleaved little-endian float PCM without copying or moving [pcmLe].
     */
    fun floatToMono16(pcmLe: ByteBuffer, channelCount: Int): ShortArray {
        require(channelCount >= 1) { "channelCount >= 1" }
        val view = pcmLe.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = view.remaining() / (4 * channelCount)
        val out = ShortArray(frameCount)
        var byteIndex = view.position()
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channelCount) {
                sum += view.getFloat(byteIndex)
                byteIndex += 4
            }
            val average = (sum / channelCount).coerceIn(-1f, 1f)
            out[i] = (average * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * Linear-interpolation resample mono shorts to [TARGET_SAMPLE_RATE_HZ].
     * If [sourceRateHz] already matches, returns [mono] unchanged.
     *
     * Uses one incremental accumulator instead of a per-sample
     * `i * sourceRate / target` multiply+divide, which roughly halves the
     * floating-point work on the 44.1 kHz → 16 kHz path (~48k samples/window).
     */
    fun resampleMonoTo16k(mono: ShortArray, sourceRateHz: Int): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        if (sourceRateHz == TARGET_SAMPLE_RATE_HZ) return mono
        require(sourceRateHz > 0) { "sourceRateHz > 0" }
        val outLen = ((mono.size.toLong() * TARGET_SAMPLE_RATE_HZ) / sourceRateHz)
            .toInt()
            .coerceAtLeast(0)
        if (outLen == 0) return ShortArray(0)
        val out = ShortArray(outLen)
        val lastIndex = mono.lastIndex
        val step = sourceRateHz.toDouble() / TARGET_SAMPLE_RATE_HZ
        var srcPos = 0.0
        for (i in 0 until outLen) {
            val idx = srcPos.toInt().coerceIn(0, lastIndex)
            val frac = srcPos - idx
            val s0 = mono[idx].toInt()
            val s1 = mono[if (idx < lastIndex) idx + 1 else lastIndex].toInt()
            out[i] = (s0 + ((s1 - s0) * frac).toInt()).toShort()
            srcPos += step
        }
        return out
    }

    /** Root-mean-square of 16-bit samples, roughly 0f..1f. */
    fun rmsNormalized(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var acc = 0.0
        for (s in samples) {
            val v = s.toDouble() / Short.MAX_VALUE
            acc += v * v
        }
        return sqrt(acc / samples.size).toFloat()
    }
}
