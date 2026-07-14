package com.framenest.player.audio

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
    fun toMonoSamples(pcmLe: ByteArray, channelCount: Int): ShortArray {
        require(channelCount >= 1) { "channelCount >= 1" }
        val frameCount = pcmLe.size / (2 * channelCount)
        val out = ShortArray(frameCount)
        var bi = 0
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) {
                val lo = pcmLe[bi].toInt() and 0xff
                val hi = pcmLe[bi + 1].toInt()
                bi += 2
                sum += ((hi shl 8) or lo).toShort().toInt()
            }
            out[i] = (sum / channelCount).toShort()
        }
        return out
    }

    /**
     * Linear-interpolation resample mono shorts to [TARGET_SAMPLE_RATE_HZ].
     * If [sourceRateHz] already matches, returns a copy of [mono].
     */
    fun resampleMonoTo16k(mono: ShortArray, sourceRateHz: Int): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        if (sourceRateHz == TARGET_SAMPLE_RATE_HZ) return mono.copyOf()
        require(sourceRateHz > 0) { "sourceRateHz > 0" }
        val outLen = ((mono.size.toLong() * TARGET_SAMPLE_RATE_HZ) / sourceRateHz)
            .toInt()
            .coerceAtLeast(0)
        if (outLen == 0) return ShortArray(0)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i.toDouble() * sourceRateHz / TARGET_SAMPLE_RATE_HZ
            val idx = srcPos.toInt().coerceIn(0, mono.lastIndex)
            val frac = srcPos - idx
            val s0 = mono[idx].toInt()
            val s1 = mono[minOf(idx + 1, mono.lastIndex)].toInt()
            out[i] = (s0 + ((s1 - s0) * frac).toInt()).toShort()
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
