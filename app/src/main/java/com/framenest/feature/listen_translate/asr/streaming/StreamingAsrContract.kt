package com.framenest.feature.listen_translate.asr.streaming

import kotlinx.coroutines.flow.Flow

/** Experimental, independent of the production offline AsrEngine and its default model. */
interface StreamingAsrEngine {
    /** Cold, single-use generation. Caller supplies contiguous 16 kHz mono PCM with backpressure. */
    fun recognize(request: StreamingAsrRequest, chunks: Flow<StreamingPcmChunk>): Flow<StreamingAsrEvent>
    /** Invalidates results immediately; native cleanup waits for any uninterruptible decode. */
    fun cancel(generation: Long)
    suspend fun close()
}

data class StreamingAsrRequest(
    val generation: Long,
    val windowStartMs: Long,
    val maxWindowSamples: Int = 480_000,
) {
    init {
        require(generation > 0)
        require(windowStartMs in 0..Long.MAX_VALUE - 30_000)
        require(maxWindowSamples in 1..480_000)
    }
}

/** Caller must not mutate samples while the collector consumes this chunk. */
data class StreamingPcmChunk(val offsetSamples: Long, val samples: ShortArray)

/** Exact reported token start mapped onto media time. There is no fabricated token end. */
data class StreamingAsrToken(val text: String, val startMs: Long)
enum class StreamingFinalReason { ENDPOINT, INPUT_FINISHED }

sealed interface StreamingAsrEvent {
    val generation: Long
    data class Partial(
        override val generation: Long,
        val segmentId: Long,
        val revision: Long,
        val startMs: Long,
        val text: String,
        val tokens: List<StreamingAsrToken> = emptyList(),
    ) : StreamingAsrEvent

    /** start/end describe accepted PCM boundaries, not speech/word boundaries. Blank settles a revision. */
    data class Final(
        override val generation: Long,
        val segmentId: Long,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val tokens: List<StreamingAsrToken> = emptyList(),
        val reason: StreamingFinalReason = StreamingFinalReason.INPUT_FINISHED,
    ) : StreamingAsrEvent

    /** Emitted only after inputFinished, decode drain and final emission; not on failure/cancel. */
    data class Finished(override val generation: Long, val processedThroughMs: Long) : StreamingAsrEvent
}
