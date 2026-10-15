package com.framenest.feature.listen_translate.asr.streaming

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class NativeStreamingResult(val text: String, val tokens: List<String>, val timestamps: List<Float>)
internal interface ZipformerNativeStream {
    fun accept(samples: FloatArray)
    fun finish()
    fun ready(): Boolean
    fun decode()
    fun result(): NativeStreamingResult
    fun endpoint(): Boolean
    fun release()
}
internal interface ZipformerNativeRecognizer {
    fun stream(): ZipformerNativeStream
    fun release()
}

/**
 * Optional experimental path. No production wiring, model download, global scope or PCM retention.
 * Endpoint rotation flushes/releases the old stream at a known input boundary. It deliberately does
 * not use native reset: sherpa 1.12.32 Kotlin omits native result.start_time needed after reset.
 * All native calls (including destruction) are serialized off Main. Cancel never frees in-use JNI.
 */
class ZipformerStreamingAsrEngine internal constructor(
    private val tailPaddingSamples: Int = 10_560,
    private val createNative: () -> ZipformerNativeRecognizer,
) : StreamingAsrEngine {
    private val mutex = Mutex()
    private val latest = AtomicLong(0)
    private val cancelledThrough = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private var native: ZipformerNativeRecognizer? = null
    private val registrationLock = Any()
    private var active: Pair<Long, Job>? = null

    init { require(tailPaddingSamples in 1..32_000) }

    override fun recognize(request: StreamingAsrRequest, chunks: Flow<StreamingPcmChunk>): Flow<StreamingAsrEvent> = flow {
        val job = currentCoroutineContext().job
        synchronized(registrationLock) {
            check(!closed.get()) { "Engine closed" }
            require(request.generation > latest.get()) { "Generation must strictly increase" }
            latest.set(request.generation)
            active?.second?.cancel(CancellationException("Superseded ASR generation"))
            active = request.generation to job
        }
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (closed.get() || latest.get() != request.generation || cancelledThrough.get() >= request.generation) {
                throw CancellationException("Stale ASR generation")
            }
        }
        mutex.withLock {
            checkCurrent()
            val recognizer = native ?: createNative().also { native = it }
            checkCurrent()
            var stream: ZipformerNativeStream? = null
            var received = 0L
            var segmentStart = 0L
            var segmentId = 0L
            var revision = 0L
            var last: NativeStreamingResult? = null
            fun media(sample: Long) = request.windowStartMs + sample * 1000 / 16_000
            fun tokens(result: NativeStreamingResult): List<StreamingAsrToken> {
                // Preserve only valid paired timestamps. Never invent missing times or token ends.
                if (result.tokens.size != result.timestamps.size) return emptyList()
                return result.tokens.zip(result.timestamps).mapNotNull { (text, seconds) ->
                    val durationSeconds = (received - segmentStart) / 16_000.0
                    if (!seconds.isFinite() || seconds < 0 || seconds > durationSeconds) null
                    else StreamingAsrToken(text, media(segmentStart) + (seconds.toDouble() * 1000).toLong())
                }
            }
            suspend fun drain(s: ZipformerNativeStream) {
                var iterations = 0
                while (s.ready()) {
                    checkCurrent()
                    check(++iterations <= 4096) { "Native decode failed to drain within bounded window" }
                    s.decode()
                    checkCurrent()
                }
            }
            suspend fun finalizeSegment(reason: StreamingFinalReason) {
                val s = stream ?: return
                // Bounded artificial context only: never advance received/media coverage with padding.
                // The pinned v1.12.32 online-decode-files.py example uses a 0.66 s pad;
                // actual checkpoint tail quality
                // still needs native device validation before this experimental seam is enabled.
                // https://github.com/k2-fsa/sherpa-onnx/blob/master/c-api-examples/streaming-zipformer-with-hr-c-api.c
                s.accept(FloatArray(tailPaddingSamples))
                s.finish()
                drain(s)
                val result = s.result()
                checkCurrent()
                emit(StreamingAsrEvent.Final(request.generation, segmentId, media(segmentStart), media(received), result.text, tokens(result), reason))
                stream = null
                s.release()
                segmentId++
                segmentStart = received
                revision = 0
                last = null
            }
            try {
                chunks.collect { chunk ->
                    checkCurrent()
                    require(chunk.offsetSamples == received) { "PCM must be contiguous" }
                    require(chunk.samples.size in 1..3200) { "PCM chunk must be 1..3200 samples" }
                    require(received + chunk.samples.size <= request.maxWindowSamples) { "ASR window exceeds bound" }
                    val s = stream ?: recognizer.stream().also { stream = it }
                    s.accept(FloatArray(chunk.samples.size) { chunk.samples[it] / 32768f })
                    received += chunk.samples.size
                    drain(s)
                    val result = s.result()
                    checkCurrent()
                    if (s.endpoint()) {
                        finalizeSegment(StreamingFinalReason.ENDPOINT)
                    } else if (last != result) {
                        last = result
                        emit(StreamingAsrEvent.Partial(request.generation, segmentId, ++revision, media(segmentStart), result.text, tokens(result)))
                    }
                }
                checkCurrent()
                require(received > 0) { "Input must contain PCM" }
                finalizeSegment(StreamingFinalReason.INPUT_FINISHED)
                checkCurrent()
                emit(StreamingAsrEvent.Finished(request.generation, media(received)))
            } finally {
                stream?.release()
            }
        }
    }.flowOn(Dispatchers.IO).buffer(0)

    override fun cancel(generation: Long) {
        cancelledThrough.accumulateAndGet(generation, ::maxOf)
        synchronized(registrationLock) {
            active?.takeIf { it.first <= generation }?.second?.cancel(CancellationException("ASR cancelled"))
        }
    }

    override suspend fun close() {
        synchronized(registrationLock) {
            closed.set(true)
            active?.second?.cancel(CancellationException("ASR closed"))
            active = null
        }
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                val old = native
                native = null
                old?.release()
            }
        }
    }
}
