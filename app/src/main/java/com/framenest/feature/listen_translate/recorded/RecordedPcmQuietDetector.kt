package com.framenest.feature.listen_translate.recorded

import kotlin.math.sqrt

/** Measured PCM RMS quiet runs. This is an acoustic heuristic, not a learned speech detector. */
internal object RecordedPcmQuietDetector {
    fun detect(pcm: FloatArray, sampleRate: Int, startMs: Long): List<RecordedQuietSpan> {
        require(sampleRate > 0 && startMs >= 0)
        val frame = maxOf(1, sampleRate / 50) // 20 ms
        val threshold = 0.017782794 // -35 dBFS
        val out = mutableListOf<RecordedQuietSpan>()
        var quietStart = -1
        var index = 0
        fun close(end: Int) {
            if (quietStart >= 0 && (end - quietStart) * 1000L >= 160L * sampleRate)
                out += RecordedQuietSpan(startMs + quietStart * 1000L / sampleRate, startMs + end * 1000L / sampleRate)
            quietStart = -1
        }
        while (index < pcm.size) {
            val end = minOf(index + frame, pcm.size)
            var sum = 0.0
            for (i in index until end) sum += pcm[i].toDouble() * pcm[i]
            val rms = sqrt(sum / (end - index))
            if (rms.isFinite() && rms <= threshold) { if (quietStart < 0) quietStart = index }
            else close(index)
            index = end
        }
        close(pcm.size)
        return out.take(256)
    }
}
