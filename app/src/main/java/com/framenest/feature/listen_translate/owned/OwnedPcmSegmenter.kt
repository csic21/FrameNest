package com.framenest.feature.listen_translate.owned

import kotlin.math.sqrt

/** Acoustic decisions only: none of these reasons establishes sentence, ASR, or MT coverage. */
internal enum class OwnedPcmCutReason { QUIET_WINDOW, FORCED_LIMIT, EOF }

/** The measured 320 ms quiet window centered on a QUIET_WINDOW cut. */
internal data class OwnedPcmQuietWindow(val startSample: Long, val endSampleExclusive: Long)

/**
 * An immutable, non-overlapping ownership span of 16 kHz mono signed PCM16 samples.
 * [decisionSampleExclusive] is the input cursor when the cut became available, not its end.
 * Neither ownership nor a quiet boundary asserts that speech was recognized or translated.
 */
internal class OwnedPcmSpan private constructor(
    val generation: Long,
    val startSample: Long,
    val endSampleExclusive: Long,
    val reason: OwnedPcmCutReason,
    val decisionSampleExclusive: Long,
    val quietWindow: OwnedPcmQuietWindow?,
    private val pcm: ShortArray,
) {
    companion object {
        /** Copy the selected ring prefix exactly once; even the constructor cannot expose an alias. */
        internal fun copyFromRing(
            generation: Long,
            startSample: Long,
            length: Int,
            reason: OwnedPcmCutReason,
            decisionSampleExclusive: Long,
            quietWindow: OwnedPcmQuietWindow?,
            ring: ShortArray,
            head: Int,
        ): OwnedPcmSpan {
            require(length > 0 && length <= ring.size && head in ring.indices)
            require(startSample >= 0 && length.toLong() <= Long.MAX_VALUE - startSample)
            val owned = ShortArray(length)
            val firstPart = minOf(length, ring.size - head)
            ring.copyInto(owned, 0, head, head + firstPart)
            if (firstPart < length) ring.copyInto(owned, firstPart, 0, length - firstPart)
            return OwnedPcmSpan(generation, startSample, startSample + length, reason,
                decisionSampleExclusive, quietWindow, owned)
        }
    }

    val sampleCount: Int get() = pcm.size
    fun sampleAt(index: Int): Short = pcm[index]
    fun copyPcm(): ShortArray = pcm.copyOf()
    fun copyPcmInto(destination: ShortArray, destinationOffset: Int = 0) {
        require(destinationOffset >= 0 && destinationOffset <= destination.size - pcm.size)
        pcm.copyInto(destination, destinationOffset)
    }
}

/** Only this prefix was accepted. Re-offer the caller-owned suffix at the new input cursor. */
internal data class OwnedPcmOffer(val consumedSamples: Int, val span: OwnedPcmSpan?)

/** Seek/reset cancellation, deliberately not an ASR-ready [OwnedPcmSpan]. */
internal data class DiscardedOwnedPcmOnReset(
    val generation: Long,
    val startSample: Long,
    val endSampleExclusive: Long,
) {
    val sampleCount: Long get() = endSampleExclusive - startSample
}

/**
 * Experimental bounded PCM ownership planner. Not wired into the production pipeline.
 *
 * Fixed policy: 16 kHz mono PCM16, generation-anchored 20 ms RMS frames, <= -35 dBFS quiet,
 * a 320 ms quiet window, >= 2 s owned spans and <= 12 s owned spans (except short EOF).
 * At every complete frame, choose the earliest legal cut centered in an already measured
 * quiet window. A long quiet run may slide that window forward to meet the 2 s minimum.
 * Thus both sides retain >= 160 ms of measured quiet; no speech or silence is trimmed.
 *
 * If no legal cut has appeared, observe exactly 320 ms beyond the 12 s cap, then emit 12 s
 * with FORCED_LIMIT and retain all lookahead. Input chunk size cannot alter that decision.
 * Only complete, generation-aligned frames are classified; EOF partial frames stay owned.
 *
 * [offer] accepts only up to the next emission and returns at most one span. The caller must
 * process that result before re-offering the unconsumed suffix. There is no output queue and
 * no whole-input copy. Internal PCM storage is one fixed [MAX_PENDING_SAMPLES] ring buffer;
 * each emitted span owns a new array of at most [MAX_OWNED_SAMPLES]. Caller-held input/results
 * and copies requested through [OwnedPcmSpan.copyPcm] are outside this storage bound.
 *
 * Drain [finish] until null at EOF. [reset] explicitly discards old-generation pending PCM
 * and reports its range separately, so cancelled audio cannot be mistaken for an ASR span.
 * Single-thread confined; callers must not mutate an input slice during [offer].
 */
internal class OwnedPcmSegmenter(generation: Long = 0, startSample: Long = 0) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 320
        const val QUIET_WINDOW_SAMPLES = 5_120
        const val QUIET_HALF_SAMPLES = QUIET_WINDOW_SAMPLES / 2
        const val MIN_OWNED_SAMPLES = 32_000
        const val MAX_OWNED_SAMPLES = 192_000
        const val LOOKAHEAD_SAMPLES = 5_120
        const val MAX_PENDING_SAMPLES = MAX_OWNED_SAMPLES + LOOKAHEAD_SAMPLES
        private const val QUIET_RMS = 0.017782794 // Same -35 dBFS threshold as RecordedPcmQuietDetector.
    }

    init {
        require(generation >= 0) { "Generation must be nonnegative" }
        require(startSample >= 0) { "Sample cursor must be nonnegative" }
    }

    var generation: Long = generation
        private set
    var nextSample: Long = startSample
        private set
    var pendingStartSample: Long = startSample
        private set
    var pendingSampleCount: Int = 0
        private set
    val bufferCapacitySamples: Int get() = pcm.size
    var inputFinished: Boolean = false
        private set

    private val pcm = ShortArray(MAX_PENDING_SAMPLES)
    private var head = 0
    private var frameSamples = 0
    private var frameEnergy = 0.0
    private var quietStart: Long? = null

    /**
     * Validates the whole requested slice and cursor before accepting any sample. Empty input
     * is a no-op, not EOF. A gap, overlap, stale generation, overflow, or invalid slice fails
     * without changing state. Only [OwnedPcmOffer.consumedSamples] advance [nextSample].
     */
    fun offer(
        generation: Long,
        startSample: Long,
        input: ShortArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ): OwnedPcmOffer {
        validateCursor(generation, startSample)
        check(!inputFinished) { "Input already finished; reset for a new generation" }
        require(offset >= 0 && length >= 0 && offset <= input.size && length <= input.size - offset) {
            "Invalid PCM slice"
        }
        require(length.toLong() <= Long.MAX_VALUE - nextSample) { "Sample cursor overflow" }
        var consumed = 0
        while (consumed < length) {
            val sample = input[offset + consumed]
            pcm[(head + pendingSampleCount) % pcm.size] = sample
            pendingSampleCount++
            nextSample++
            consumed++
            frameEnergy += sample.toDouble() * sample
            frameSamples++
            if (frameSamples == FRAME_SAMPLES) {
                val quiet = sqrt(frameEnergy / FRAME_SAMPLES) / 32768.0 <= QUIET_RMS
                if (quiet) {
                    if (quietStart == null) quietStart = nextSample - FRAME_SAMPLES
                } else {
                    quietStart = null
                }
                frameSamples = 0
                frameEnergy = 0.0
                val runStart = quietStart
                if (runStart != null) {
                    val cutLength = maxOf(
                        MIN_OWNED_SAMPLES.toLong(),
                        runStart - pendingStartSample + QUIET_HALF_SAMPLES,
                    )
                    if (cutLength <= MAX_OWNED_SAMPLES &&
                        cutLength + QUIET_HALF_SAMPLES <= pendingSampleCount
                    ) {
                        val cut = pendingStartSample + cutLength
                        val window = OwnedPcmQuietWindow(cut - QUIET_HALF_SAMPLES, cut + QUIET_HALF_SAMPLES)
                        return OwnedPcmOffer(consumed, emit(cutLength.toInt(), OwnedPcmCutReason.QUIET_WINDOW, window))
                    }
                }
            }
            if (pendingSampleCount == MAX_PENDING_SAMPLES) {
                return OwnedPcmOffer(consumed, emit(MAX_OWNED_SAMPLES, OwnedPcmCutReason.FORCED_LIMIT))
            }
        }
        return OwnedPcmOffer(consumed, null)
    }

    /**
     * Mark EOF at the exact accepted cursor and call repeatedly until null. Usually emits one
     * EOF span, including a sub-minimum remainder. If > 12 s is pending while waiting for the
     * decision lookahead, first emit 12 s FORCED_LIMIT, then the exact EOF remainder. Empty EOF
     * emits nothing. Repeated finish is safe; input is closed from the first valid call onward.
     */
    fun finish(generation: Long, endSampleExclusive: Long): OwnedPcmSpan? {
        validateCursor(generation, endSampleExclusive)
        inputFinished = true
        return when {
            pendingSampleCount > MAX_OWNED_SAMPLES -> emit(MAX_OWNED_SAMPLES, OwnedPcmCutReason.FORCED_LIMIT)
            pendingSampleCount > 0 -> emit(pendingSampleCount, OwnedPcmCutReason.EOF)
            else -> null
        }
    }

    /** Reset frame alignment and cursor. Generation IDs must strictly increase to reject stale work. */
    fun reset(newGeneration: Long, startSample: Long): DiscardedOwnedPcmOnReset? {
        require(newGeneration > generation) { "Reset generation must strictly increase" }
        require(startSample >= 0) { "Sample cursor must be nonnegative" }
        val discarded = if (pendingSampleCount == 0) null else
            DiscardedOwnedPcmOnReset(generation, pendingStartSample, nextSample)
        generation = newGeneration
        nextSample = startSample
        pendingStartSample = startSample
        pendingSampleCount = 0
        head = 0
        frameSamples = 0
        frameEnergy = 0.0
        quietStart = null
        inputFinished = false
        return discarded
    }

    private fun validateCursor(generation: Long, sample: Long) {
        require(generation == this.generation) { "Stale or unexpected generation" }
        require(sample == nextSample) { "PCM must be contiguous at the exact sample cursor" }
    }

    private fun emit(
        length: Int,
        reason: OwnedPcmCutReason,
        window: OwnedPcmQuietWindow? = null,
    ): OwnedPcmSpan {
        val span = OwnedPcmSpan.copyFromRing(generation, pendingStartSample, length,
            reason, nextSample, window, pcm, head)
        head = (head + length) % pcm.size
        pendingSampleCount -= length
        pendingStartSample += length
        // Complete-frame evidence in the retained suffix remains valid without re-reading PCM.
        quietStart = quietStart?.coerceAtLeast(pendingStartSample)
        return span
    }
}
