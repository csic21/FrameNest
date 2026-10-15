package com.framenest.feature.listen_translate.streaming

import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrEngine
import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrEvent
import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrRequest
import com.framenest.feature.listen_translate.asr.streaming.StreamingPcmChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Bridge for the existing 16 kHz mono audio.pcmWindow read seam. It requests at most
 * three seconds at a time and emits <=200 ms blocks under downstream backpressure.
 * The delegate may retain its existing bounded PCM/read cache. No PCM is persisted.
 * A short read is EOF, never fabricated silence; Finished will be short and therefore
 * cannot complete the requested coverage transaction.
 */
internal fun streamingPcmChunks(
    window: StreamingSubtitleWindow,
    readPcm: suspend (startMs: Long, endMs: Long) -> ShortArray,
): Flow<StreamingPcmChunk> = flow {
    var positionMs = window.startMs
    var offsetSamples = 0L
    while (positionMs < window.endMs) {
        currentCoroutineContext().ensureActive()
        val endMs = positionMs + minOf(3_000L, window.endMs - positionMs)
        val requestedSamples = ((endMs - positionMs) * 16L).toInt()
        val pcm = readPcm(positionMs, endMs)
        currentCoroutineContext().ensureActive()
        require(pcm.size <= requestedSamples) { "PCM reader returned more than its requested media window" }
        var cursor = 0
        while (cursor < pcm.size) {
            val next = minOf(cursor + 3_200, pcm.size)
            emit(StreamingPcmChunk(offsetSamples, pcm.copyOfRange(cursor, next)))
            offsetSamples += next - cursor
            cursor = next
        }
        if (pcm.size < requestedSamples) break
        positionMs = endMs
    }
}

/**
 * Explicit experimental invocation only: does not change the default engine/settings.
 * Use a new increasing request generation per tranche; seek/track/language changes must
 * invalidate the active generation and its cache lease before starting another request.
 */
internal fun streamingEventsForWindow(
    engine: StreamingAsrEngine,
    window: StreamingSubtitleWindow,
    readPcm: suspend (startMs: Long, endMs: Long) -> ShortArray,
): Flow<StreamingAsrEvent> = engine.recognize(
    StreamingAsrRequest(
        generation = window.generation,
        windowStartMs = window.startMs,
        maxWindowSamples = ((window.endMs - window.startMs) * 16L).toInt(),
    ),
    streamingPcmChunks(window, readPcm),
)
