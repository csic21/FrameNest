package com.framenest.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubPreviewPlanTest {
    @Test
    fun neighborhood_startsAtTheBucketUnderTheFinger() {
        assertEquals(
            listOf(10_000L, 20_000L, 0L, 30_000L),
            ScrubPreviewPlan.neighborhood(120_000L, 15_000L),
        )
        assertEquals(emptyList<Long>(), ScrubPreviewPlan.neighborhood(0L, 10_000L))
        assertEquals(listOf(0L), ScrubPreviewPlan.neighborhood(5_000L, 1_000L))
        assertEquals(0L, ScrubPreviewPlan.bucketStartMs(-20L))
        assertEquals(10_000L, ScrubPreviewPlan.bucketStartMs(19_999L))
    }

    @Test
    fun fingerTarget_preemptsWarmup_andBufferingPausesWarmup() {
        val ready = emptySet<Long>()
        val failed = emptySet<Long>()
        assertEquals(
            90_000L,
            ScrubPreviewPlan.nextExtractMs(
                durationMs = 120_000L,
                anchorMs = 0L,
                scrubTargetMs = 95_000L,
                buffering = true,
                active = true,
                ready = ready,
                failed = failed,
            ),
        )
        assertNull(
            ScrubPreviewPlan.nextExtractMs(
                durationMs = 120_000L,
                anchorMs = 0L,
                scrubTargetMs = null,
                buffering = true,
                active = true,
                ready = ready,
                failed = failed,
            ),
        )
        assertEquals(
            0L,
            ScrubPreviewPlan.nextExtractMs(
                durationMs = 120_000L,
                anchorMs = 4_000L,
                scrubTargetMs = null,
                buffering = false,
                active = true,
                ready = ready,
                failed = failed,
            ),
        )
        assertNull(
            ScrubPreviewPlan.nextExtractMs(
                durationMs = 120_000L,
                anchorMs = 0L,
                scrubTargetMs = null,
                buffering = false,
                active = false,
                ready = ready,
                failed = failed,
            ),
        )
    }

    @Test
    fun nearestReady_prefersCloserThenEarlier() {
        assertNull(ScrubPreviewPlan.nearestReadyMs(40_000L, emptySet()))
        assertEquals(
            30_000L,
            ScrubPreviewPlan.nearestReadyMs(40_000L, setOf(0L, 30_000L, 80_000L)),
        )
        assertEquals(
            10_000L,
            ScrubPreviewPlan.nearestReadyMs(20_000L, setOf(10_000L, 30_000L)),
        )
    }

    @Test
    fun retain_keepsFramesClosestToTheFinger() {
        val keys = (0L..8L).map { it * 10_000L }
        assertEquals(
            setOf(60_000L, 70_000L, 80_000L),
            ScrubPreviewPlan.retain(
                keys = keys,
                anchorMs = 0L,
                scrubTargetMs = 85_000L,
                maxEntries = 3,
            ),
        )
    }

    @Test
    fun cacheDigest_changesWhenTheFileChanges() {
        val first = ScrubPreviewPlan.cacheDigest("srv", "media", "a.mkv", 10L, 1L)
        val replaced = ScrubPreviewPlan.cacheDigest("srv", "media", "a.mkv", 10L, 2L)
        val same = ScrubPreviewPlan.cacheDigest("srv", "media", "a.mkv", 10L, 1L)
        assertEquals(first, same)
        assertTrue(first != replaced)
    }

    @Test
    fun diskEvictsOldestUntilTheCapFits() {
        val files = listOf(
            ScrubPreviewPlan.CacheFile("old", 60L, 1L),
            ScrubPreviewPlan.CacheFile("mid", 30L, 2L),
            ScrubPreviewPlan.CacheFile("new", 30L, 3L),
        )
        assertEquals(emptyList<String>(), ScrubPreviewPlan.filesToEvict(files, 10L, 200L))
        assertEquals(listOf("old"), ScrubPreviewPlan.filesToEvict(files, 40L, 100L))
        assertEquals(listOf("old", "mid"), ScrubPreviewPlan.filesToEvict(files, 50L, 80L))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScrubPreviewSchedulerTest {
    @Test
    fun scrubTarget_isExtractedBeforeWarmFrames() = runTest {
        val loaded = mutableListOf<Long>()
        val scheduler = ScrubPreviewScheduler(this) { bucket ->
            loaded += bucket
            true
        }
        try {
            scheduler.setScrubbing(true, 95_000L)
            scheduler.updatePlayback(
                durationMs = 120_000L,
                anchorMs = 0L,
                buffering = false,
                active = true,
            )
            advanceUntilIdle()
            assertEquals(90_000L, loaded.first())
            assertEquals(
                ScrubPreviewPlan.neighborhood(120_000L, 95_000L),
                loaded,
            )
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun buffering_doesNotWarm_untilPlaybackCanSpareARead() = runTest {
        val loaded = mutableListOf<Long>()
        val scheduler = ScrubPreviewScheduler(this) { bucket ->
            loaded += bucket
            true
        }
        try {
            scheduler.updatePlayback(
                durationMs = 60_000L,
                anchorMs = 0L,
                buffering = true,
                active = true,
            )
            advanceUntilIdle()
            assertTrue(loaded.isEmpty())
            scheduler.updatePlayback(
                durationMs = 60_000L,
                anchorMs = 0L,
                buffering = false,
                active = true,
            )
            advanceUntilIdle()
            assertEquals(0L, loaded.first())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun failedBucket_isNotRetried() = runTest {
        var calls = 0
        val scheduler = ScrubPreviewScheduler(this) {
            calls += 1
            false
        }
        try {
            scheduler.updatePlayback(
                durationMs = 30_000L,
                anchorMs = 0L,
                buffering = false,
                active = true,
            )
            advanceUntilIdle()
            val once = calls
            assertTrue(once > 0)
            scheduler.setScrubbing(true, 5_000L)
            advanceUntilIdle()
            scheduler.setScrubbing(false, null)
            advanceUntilIdle()
            assertEquals(once, calls)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun newerScrubTarget_replacesTheQueueWithoutASecondInFlightRead() = runTest {
        val started = Channel<Long>(capacity = Channel.UNLIMITED)
        val release = Channel<Unit>(capacity = Channel.UNLIMITED)
        val scheduler = ScrubPreviewScheduler(this) { bucket ->
            started.send(bucket)
            release.receive()
            true
        }
        try {
            scheduler.updatePlayback(
                durationMs = 120_000L,
                anchorMs = 0L,
                buffering = false,
                active = true,
            )
            assertEquals(0L, withTimeout(1_000) { started.receive() })
            scheduler.setScrubbing(true, 95_000L)
            release.send(Unit)
            assertEquals(90_000L, withTimeout(1_000) { started.receive() })
            assertTrue(started.tryReceive().isFailure)
        } finally {
            scheduler.close()
        }
    }
}
