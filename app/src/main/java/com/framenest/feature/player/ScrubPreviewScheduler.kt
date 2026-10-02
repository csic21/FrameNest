package com.framenest.feature.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
            if (starting) failed.clear()
        }
        wake.trySend(Unit)
    }

    fun readyBuckets(): Set<Long> = synchronized(lock) { ready.toSet() }

    fun close() {
        synchronized(lock) { closed = true }
        job.cancel()
        wake.close()
    }

    private suspend fun drain() {
        var abandonedStreak: Long? = null
        while (true) {
            val next = synchronized(lock) {
                if (closed) return
                selectNextLocked()
            } ?: return
            val outcome = try {
                loadFrame(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ScrubLoadResult.Failed
            }
            val stop = synchronized(lock) {
                if (closed) return
                when (outcome) {
                    ScrubLoadResult.Ready -> {
                        ready += next
                        abandonedStreak = null
                        false
                    }
                    ScrubLoadResult.Failed -> {
                        failed += next
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

    private fun selectNextLocked(): Long? = ScrubPreviewPlan.nextExtractMs(
        durationMs = durationMs,
        anchorMs = anchorMs,
        scrubTargetMs = if (scrubbing) scrubTargetMs else null,
        buffering = buffering,
        active = active,
        ready = ready,
        failed = failed,
    )
}
