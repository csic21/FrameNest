package com.framenest.player.audio

/**
 * PCM plus optional media-timeline information. Sample count is not a media clock: each codec
 * buffer can lose a fractional sample while resampling to 16 kHz.
 *
 * [decodedThroughMs] is the exclusive media end covered by this window, rounded up to a whole
 * millisecond and capped at the requested end. Null means the source cannot provide that clock.
 * [reachedEndOfStream] is true only after real extractor EOF and decoder drainage, with no audio
 * clipped off the requested end. A synthetic window-end codec EOS must never set this flag.
 */
data class PcmWindowResult(
    val samples: ShortArray,
    val decodedThroughMs: Long? = null,
    val reachedEndOfStream: Boolean = false,
)

/** The legacy decode APIs still reject a successful but empty decode at source EOF. */
internal fun PcmWindowResult.requirePcmSamples(): ShortArray {
    check(samples.isNotEmpty()) { "系统解码器已打开音轨，但未输出 PCM" }
    return samples
}

/** Tracks source PCM timestamps before clipping/resampling, independently of output sample count. */
internal class PcmWindowTimeline(startMs: Long, endMs: Long) {
    private val startUs = startMs.coerceAtLeast(0L) * 1_000L
    private val endUs = endMs * 1_000L
    private var decodedThroughUs = startUs
    private var extractorEnded = false
    private var decoderEnded = false
    private var clippedTail = false

    fun onExtractorEndOfStream() {
        extractorEnded = true
    }

    fun onOutputBuffer(ptsUs: Long, frameCount: Int, sampleRateHz: Int) {
        if (frameCount <= 0 || sampleRateHz <= 0) return
        val bufferEndUs = ptsUs + frameCount.toLong() * 1_000_000L / sampleRateHz
        decodedThroughUs = maxOf(decodedThroughUs, minOf(bufferEndUs, endUs))
        if (bufferEndUs > endUs) clippedTail = true
    }

    fun onOutputEndOfStream() {
        decoderEnded = true
    }

    fun result(samples: ShortArray): PcmWindowResult = PcmWindowResult(
        samples = samples,
        decodedThroughMs = (decodedThroughUs + 999L) / 1_000L,
        reachedEndOfStream = extractorEnded && decoderEnded && !clippedTail,
    )
}
