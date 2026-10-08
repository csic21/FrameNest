package com.framenest.feature.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Single-flight preview decoder. The finger's bucket is extracted before any
 * background strip, and a newer target replaces the queue without stacking
 * SMB reads.
 */
internal enum class ScrubLoadResult {
    Ready,
    Failed,
    /** The finger moved; do not remember this bucket as ready or failed. */
    Abandoned,
}

internal class ScrubPreviewScheduler(
    scope: CoroutineScope,
    private val loadFrame: suspend (bucketStartMs: Long) -> ScrubLoadResult,
) {
    private val ready = HashSet<Long>()
    private val failed = HashSet<Long>()
    private val lock = Any()
    private var durationMs: Long = 0L
    private var anchorMs: Long = 0L
    private var anchorBucket: Long = Long.MIN_VALUE
    private var scrubbing: Boolean = false
    private var scrubTargetMs: Long? = null
    private var buffering: Boolean = false
    private var active: Boolean = false
    private var closed: Boolean = false
    private var inFlight: Long? = null
    private var inFlightEvicted: Boolean = false
    private var gestureGeneration: Long = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val job: Job = scope.launch {
        for (ignored in wake) {
            drain()
        }
    }

    fun updatePlayback(
        durationMs: Long,
        anchorMs: Long,
        buffering: Boolean,
        active: Boolean,
    ) {
        val anchorBucket = ScrubPreviewPlan.bucketStartMs(anchorMs)
        synchronized(lock) {
            if (closed) return
            val changed = this.durationMs != durationMs ||
                this.anchorBucket != anchorBucket ||
                this.buffering != buffering ||
                this.active != active
            this.durationMs = durationMs
            this.anchorMs = anchorMs
            this.anchorBucket = anchorBucket
            this.buffering = buffering
            this.active = active
            if (!changed) return
        }
        wake.trySend(Unit)
    }

    fun setScrubbing(active: Boolean, targetMs: Long?) {
        synchronized(lock) {
            if (closed) return
            val starting = active && targetMs != null && !scrubbing
            scrubbing = active && targetMs != null
            scrubTargetMs = if (scrubbing) targetMs else null
            // A new gesture can retry a bucket the previous drag missed.
            if (starting) {
                gestureGeneration += 1L
                failed.clear()
            }
        }
        wake.trySend(Unit)
    }

    fun readyBuckets(): Set<Long> = synchronized(lock) { ready.toSet() }

    /**
     * Memory eviction makes a bucket eligible for a later request, even if it
     * was evicted inside [loadFrame] before its Ready result reached us. Do not
     * wake here: a background strip may be larger than the retained cache.
     */
    fun invalidateReadyBuckets(buckets: Collection<Long>) {
        synchronized(lock) {
            if (closed) return
            ready.removeAll(buckets.toSet())
            if (inFlight in buckets) inFlightEvicted = true
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            ready.clear()
            failed.clear()
            scrubTargetMs = null
        }
        job.cancel()
        wake.close()
    }

    private suspend fun drain() {
        var abandonedStreak: Long? = null
        // A frame evicted while warming must not be reloaded forever in the
        // same pass. A subsequent playback/gesture update can request it again.
        val attempted = HashSet<Long>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val (next, generation) = synchronized(lock) {
                if (closed) return
                val bucket = selectNextLocked(attempted) ?: return
                inFlight = bucket
                inFlightEvicted = false
                bucket to gestureGeneration
            }
            val outcome = try {
                loadFrame(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ScrubLoadResult.Failed
            }
            currentCoroutineContext().ensureActive()
            val stop = synchronized(lock) {
                inFlight = null
                if (closed) return
                when (outcome) {
                    ScrubLoadResult.Ready -> {
                        if (!inFlightEvicted) ready += next
                        attempted += next
                        abandonedStreak = null
                        false
                    }
                    ScrubLoadResult.Failed -> {
                        if (generation == gestureGeneration) failed += next
                        attempted += next
                        abandonedStreak = null
                        false
                    }
                    ScrubLoadResult.Abandoned -> {
                        if (abandonedStreak == next) {
                            true
                        } else {
                            abandonedStreak = next
                            false
                        }
                    }
                }
            }
            if (stop) return
        }
    }

    private fun selectNextLocked(attempted: Set<Long>): Long? = ScrubPreviewPlan.nextExtractMs(
        durationMs = durationMs,
        anchorMs = anchorMs,
        scrubTargetMs = if (scrubbing) scrubTargetMs else null,
        buffering = buffering,
        active = active,
        ready = ready + attempted,
        failed = failed,
    )
}
