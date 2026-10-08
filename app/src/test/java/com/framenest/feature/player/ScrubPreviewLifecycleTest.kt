package com.framenest.feature.player

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrubPreviewLifecycleTest {
    @Test
    fun evictedFrames_reloadAfterMoreThanThirtySixBuckets() = runTest {
        val loaded = mutableListOf<Long>()
        var memory = emptySet<Long>()
        var focus = 0L
        lateinit var scheduler: ScrubPreviewScheduler
        scheduler = ScrubPreviewScheduler(this) { bucket ->
            loaded += bucket
            val merged = memory + bucket
            memory = ScrubPreviewPlan.retain(
                keys = merged,
                anchorMs = 0L,
                scrubTargetMs = focus,
                maxEntries = ScrubPreviewPlan.MAX_MEMORY_FRAMES,
            )
            scheduler.invalidateReadyBuckets(merged - memory)
            ScrubLoadResult.Ready
        }
        try {
            scheduler.updatePlayback(600_000L, 0L, buffering = false, active = false)
            for (index in 0L..50L) {
                focus = index * ScrubPreviewPlan.BUCKET_MS
                scheduler.setScrubbing(true, focus)
                advanceUntilIdle()
                assertEquals(memory, scheduler.readyBuckets())
                assertTrue(memory.size <= ScrubPreviewPlan.MAX_MEMORY_FRAMES)
            }
            assertEquals(ScrubPreviewPlan.MAX_MEMORY_FRAMES, memory.size)
            assertFalse(0L in memory)
            val beforeReturn = loaded.size
            focus = 0L
            scheduler.setScrubbing(true, focus)
            advanceUntilIdle()
            assertEquals(0L, loaded[beforeReturn])
            assertTrue(0L in scheduler.readyBuckets())
            assertEquals(memory, scheduler.readyBuckets())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun evictingTheInFlightWarmFrame_doesNotRestoreReadinessOrLoop() = runTest {
        val loaded = mutableListOf<Long>()
        lateinit var scheduler: ScrubPreviewScheduler
        scheduler = ScrubPreviewScheduler(this) { bucket ->
            loaded += bucket
            // Fail with a finite count even if a regression starts reloading
            // an evicted frame synchronously without ever suspending.
            if (loaded.size > ScrubPreviewPlan.SPREAD_COUNT + 5) scheduler.close()
            // A spread bucket can be farther than all 36 retained frames.
            scheduler.invalidateReadyBuckets(listOf(bucket))
            ScrubLoadResult.Ready
        }
        try {
            scheduler.updatePlayback(600_000L, 0L, buffering = false, active = true)
            advanceUntilIdle()
            assertEquals(ScrubPreviewPlan.playbackOrder(600_000L, 0L), loaded)
            assertTrue(scheduler.readyBuckets().isEmpty())

            loaded.clear()
            scheduler.setScrubbing(true, 305_000L)
            advanceUntilIdle()
            assertEquals(ScrubPreviewPlan.neighborhood(600_000L, 305_000L), loaded)
            assertTrue(scheduler.readyBuckets().isEmpty())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun close_cancelsSuspendedRead_andIsIdempotent() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = ScrubPreviewScheduler(this) {
            calls += 1
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        scheduler.updatePlayback(30_000L, 0L, buffering = false, active = true)
        runCurrent()
        assertEquals(1, calls)
        scheduler.close()
        scheduler.close()
        scheduler.updatePlayback(60_000L, 30_000L, buffering = false, active = true)
        scheduler.setScrubbing(true, 40_000L)
        advanceUntilIdle()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, calls)
        assertTrue(scheduler.readyBuckets().isEmpty())
    }

    @Test
    fun close_rejectsNativeResultThatIgnoresCoroutineCancellation() = runTest {
        lateinit var completion: Continuation<ScrubLoadResult>
        var calls = 0
        val scheduler = ScrubPreviewScheduler(this) {
            calls += 1
            suspendCoroutine { completion = it }
        }
        scheduler.updatePlayback(30_000L, 0L, buffering = false, active = true)
        runCurrent()
        scheduler.close()
        completion.resume(ScrubLoadResult.Ready)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(scheduler.readyBuckets().isEmpty())
    }

    @Test
    fun ownerCancellation_rejectsLateResultEvenBeforeExplicitClose() = runTest {
        val owner = Job()
        lateinit var completion: Continuation<ScrubLoadResult>
        var calls = 0
        val scheduler = ScrubPreviewScheduler(CoroutineScope(coroutineContext + owner)) {
            calls += 1
            suspendCoroutine { completion = it }
        }
        try {
            scheduler.updatePlayback(30_000L, 0L, buffering = false, active = true)
            runCurrent()
            owner.cancel()
            completion.resume(ScrubLoadResult.Ready)
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(scheduler.readyBuckets().isEmpty())
        } finally {
            scheduler.close()
            owner.cancel()
        }
    }

    @Test
    fun newGesture_retriesFailureFromPreviousInFlightGesture() = runTest {
        lateinit var completion: Continuation<ScrubLoadResult>
        val loaded = mutableListOf<Long>()
        val scheduler = ScrubPreviewScheduler(this) { bucket ->
            loaded += bucket
            if (loaded.size == 1) suspendCoroutine { completion = it } else ScrubLoadResult.Ready
        }
        try {
            scheduler.updatePlayback(5_000L, 0L, buffering = false, active = false)
            scheduler.setScrubbing(true, 0L)
            runCurrent()
            scheduler.setScrubbing(false, null)
            scheduler.setScrubbing(true, 0L)
            completion.resume(ScrubLoadResult.Failed)
            advanceUntilIdle()
            assertEquals(listOf(0L, 0L), loaded)
            assertEquals(setOf(0L), scheduler.readyBuckets())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun close_discardsReadyBuckets_andIgnoresLaterInvalidation() = runTest {
        var calls = 0
        val scheduler = ScrubPreviewScheduler(this) {
            calls += 1
            ScrubLoadResult.Ready
        }
        scheduler.updatePlayback(5_000L, 0L, buffering = false, active = true)
        advanceUntilIdle()
        assertEquals(setOf(0L), scheduler.readyBuckets())
        scheduler.close()
        scheduler.invalidateReadyBuckets(listOf(0L))
        scheduler.setScrubbing(true, 0L)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(scheduler.readyBuckets().isEmpty())
    }
}
