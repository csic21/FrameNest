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
internal class ScrubPreviewScheduler(
    scope: CoroutineScope,
    private val loadFrame: suspend (bucketStartMs: Long) -> Boolean,
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
            scrubbing = active && targetMs != null
            scrubTargetMs = if (scrubbing) targetMs else null
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
        while (true) {
            val next = synchronized(lock) {
                if (closed) return
                selectNextLocked()
            } ?: return
            val ok = try {
                loadFrame(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            synchronized(lock) {
                if (closed) return
                if (ok) ready += next else failed += next
            }
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
