package com.framenest.feature.listen_translate.asr

/**
 * Experimental, fail-open energy gate for signed PCM16 at 16 kHz, mono.
 * This is not semantic/model VAD: music, noise and uncertain audio go to ASR.
 *
 * A window is silent only if every 20 ms frame, including the final partial
 * frame, stays within one PCM16 quantization step of zero. This deliberately
 * tiny bound (1 / 32768 of full scale) avoids the quiet/sparse-speech loss of a
 * whole-window RMS threshold. A single stronger sample keeps the entire window;
 * there is no minimum onset duration that could discard a short utterance.
 *
 * The gate never copies, trims, compacts or changes samples. Recognizers must
 * receive the original window and its original PTS, retaining all pre-roll and
 * trailing context. There is no cross-window state to reset on seek, track
 * change, cancellation or lifecycle changes. Short or unsupported input fails
 * open. Callers remain responsible for handling an empty decoder result.
 */
internal object ConservativeSpeechGate {
    private const val SAMPLE_RATE_HZ = 16_000
    private const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 50 // 20 ms
    private const val MIN_WINDOW_SAMPLES = FRAME_SAMPLES * 3 // 60 ms
    private const val SILENCE_PEAK = 1

    enum class Reason {
        NearDigitalSilence,
        SignalPresent,
        EmptyInput,
        UnsupportedFormat,
        InsufficientContext,
    }

    /** Counts and a reason only: no audio content or sample positions. */
    data class Decision(
        val reason: Reason,
        /** Number of inspected frames; zero when input fails open before inspection. */
        val frameCount: Int = 0,
        /** Frames with at least one sample outside the near-digital-silence bound. */
        val signalFrameCount: Int = 0,
    ) {
        val shouldRecognize: Boolean
            get() = reason != Reason.NearDigitalSilence
    }

    fun evaluate(
        pcm16kMono: ShortArray,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        channelCount: Int = 1,
    ): Decision {
        if (sampleRateHz != SAMPLE_RATE_HZ || channelCount != 1) {
            return Decision(Reason.UnsupportedFormat)
        }
        if (pcm16kMono.isEmpty()) return Decision(Reason.EmptyInput)
        if (pcm16kMono.size < MIN_WINDOW_SAMPLES) {
            return Decision(Reason.InsufficientContext)
        }

        var frameCount = 0
        var signalFrameCount = 0
        var index = 0
        while (index < pcm16kMono.size) {
            val frameEnd = index + minOf(FRAME_SAMPLES, pcm16kMono.size - index)
            var containsSignal = false
            while (index < frameEnd) {
                // Compare as Int rather than abs(Short), including Short.MIN_VALUE.
                val sample = pcm16kMono[index++].toInt()
                if (sample < -SILENCE_PEAK || sample > SILENCE_PEAK) {
                    containsSignal = true
                }
            }
            frameCount++
            if (containsSignal) signalFrameCount++
        }
        return Decision(
            reason = if (signalFrameCount == 0) Reason.NearDigitalSilence else Reason.SignalPresent,
            frameCount = frameCount,
            signalFrameCount = signalFrameCount,
        )
    }
}
