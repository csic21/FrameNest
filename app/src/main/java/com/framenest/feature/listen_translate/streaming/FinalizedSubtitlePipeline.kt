package com.framenest.feature.listen_translate.streaming

import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrEvent
import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow

/** Experimental NAS tranche, not an unbounded microphone session or a default engine switch. */
data class StreamingSubtitleWindow(val generation: Long, val startMs: Long, val endMs: Long) {
    init {
        require(generation > 0L)
        require(startMs in 0..Long.MAX_VALUE - 30_000L && endMs > startMs && endMs - startMs <= 30_000L)
    }
}

data class FinalizedBilingualClause(
    val source: StreamingAsrEvent.Final,
    val translatedText: String,
)

/**
 * A candidate for the existing cache transaction, emitted only after Finished covers
 * the whole requested tranche and every nonblank final has a translation. An empty
 * recognition is not proof of digital silence; the caller must preserve that distinction.
 */
data class FinalizedSubtitleWindow(val window: StreamingSubtitleWindow, val speechClauses: Int)

/**
 * Single MT consumer with bounded backpressure suitable for NAS ahead-of-playback
 * generation. Partial hypotheses never enter this queue, persistence, or coverage.
 * A slow MT step pauses future audio production rather than dropping final sentences.
 *
 * At most two queued finals + one consumer + one producer awaiting send are retained;
 * each is size checked. No detached work outlives this call. The callbacks must enforce
 * the existing repository lease as well as the supplied generation check before writes.
 * This experimental seam is intentionally not wired to the production default.
 */
internal class FinalizedSubtitlePipeline(
    private val maxQueuedClauses: Int = 2,
    private val maxTextCharacters: Int = 2_048,
) {
    init {
        require(maxQueuedClauses in 1..2)
        require(maxTextCharacters in 1..2_048)
    }

    suspend fun run(
        window: StreamingSubtitleWindow,
        events: Flow<StreamingAsrEvent>,
        isCurrent: (Long) -> Boolean,
        onPartial: (StreamingAsrEvent.Partial?) -> Unit,
        onFinalSource: suspend (StreamingAsrEvent.Final) -> Unit,
        translate: suspend (String) -> String,
        onBilingual: suspend (FinalizedBilingualClause) -> Unit,
        onComplete: suspend (FinalizedSubtitleWindow) -> Unit,
    ) = coroutineScope {
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(window.generation)) throw CancellationException("stale streaming subtitle generation")
        }
        fun validateText(text: String, tokens: List<StreamingAsrToken>) {
            require(text.length <= maxTextCharacters) { "Streaming subtitle exceeds bounded text budget" }
            require(tokens.size <= 2_048 && tokens.sumOf { it.text.length.toLong() } <= 8_192L) {
                "Streaming tokens exceed bounded text budget"
            }
        }
        val pipelineJob = currentCoroutineContext().job
        val finals = Channel<StreamingAsrEvent.Final>(maxQueuedClauses)
        val translated = async {
            try {
                var count = 0
                for (source in finals) {
                    ensureCurrent()
                    onFinalSource(source)
                    ensureCurrent()
                    val target = translate(source.text)
                    ensureCurrent()
                    require(target.isNotBlank()) { "Streaming translation returned no text" }
                    require(target.length <= maxTextCharacters) { "Streaming translation exceeds bounded text budget" }
                    onBilingual(FinalizedBilingualClause(source, target))
                    ensureCurrent()
                    count++
                }
                count
            } catch (cancelled: CancellationException) {
                // Cancel idle upstream collection as well as a producer blocked on send.
                pipelineJob.cancel(cancelled)
                throw cancelled
            } finally {
                // A child CancellationException does not cancel coroutineScope. Wake
                // a producer blocked on send even when only this consumer was cancelled.
                finals.cancel()
            }
        }

        var lastFinalId = -1L
        var pendingId: Long? = null
        var pendingRevision = -1L
        var lastFinalEnd = window.startMs
        var finished = false
        try {
            ensureCurrent()
            events.collect { event ->
                ensureCurrent()
                if (event.generation != window.generation) return@collect
                check(!finished) { "Streaming event arrived after Finished" }
                when (event) {
                    is StreamingAsrEvent.Partial -> {
                        if (event.segmentId <= lastFinalId) return@collect
                        require(event.segmentId >= 0L && event.revision >= 0L)
                        require(event.startMs in window.startMs until window.endMs)
                        validateText(event.text, event.tokens)
                        check(pendingId == null || pendingId == event.segmentId) {
                            "Streaming segment advanced without finalizing its hypothesis"
                        }
                        if (pendingId == event.segmentId && event.revision <= pendingRevision) return@collect
                        pendingId = event.segmentId
                        pendingRevision = event.revision
                        onPartial(event)
                    }
                    is StreamingAsrEvent.Final -> {
                        if (event.segmentId <= lastFinalId) return@collect // replay must not retranslate
                        require(event.segmentId >= 0L)
                        require(event.startMs >= lastFinalEnd && event.endMs >= event.startMs &&
                            event.endMs <= window.endMs) { "Streaming final is outside ordered media bounds" }
                        check(pendingId == null || pendingId == event.segmentId) {
                            "Streaming final does not settle the pending hypothesis"
                        }
                        validateText(event.text, event.tokens)
                        require(event.text.isBlank() || event.endMs > event.startMs) {
                            "Nonblank streaming final must have a nonempty media interval"
                        }
                        pendingId = null
                        pendingRevision = -1L
                        lastFinalId = event.segmentId
                        lastFinalEnd = event.endMs
                        onPartial(null)
                        if (event.text.isNotBlank()) finals.send(event)
                    }
                    is StreamingAsrEvent.Finished -> {
                        check(pendingId == null) { "Finished cannot turn a partial hypothesis into coverage" }
                        require(event.processedThroughMs == window.endMs) {
                            "Streaming input did not cover the complete requested media interval"
                        }
                        finished = true
                    }
                }
            }
            check(finished) { "Streaming input ended without an explicit Finished event" }
            finals.close()
            val count = translated.await()
            ensureCurrent()
            onComplete(FinalizedSubtitleWindow(window, count))
            ensureCurrent()
        } finally {
            finals.cancel()
            translated.cancel()
            if (isCurrent(window.generation)) runCatching { onPartial(null) }
        }
    }
}
