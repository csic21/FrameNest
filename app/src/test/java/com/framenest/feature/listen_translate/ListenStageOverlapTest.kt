package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.audio.ListenRemoteReadStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenStageOverlapTest {
    @Test fun parallelStagesKeepDurationsWithoutSummingCriticalPath() = runTest {
        val timer = ListenStageTimer { testScheduler.currentTime }
        coroutineScope {
            val prep = async { timer.measure(ListenPipelineStage.TranslationPreparation) { delay(1_000L) } }
            timer.measure(ListenPipelineStage.Asr) { delay(800L) }
            prep.await()
        }
        assertEquals(800L, timer.timings.asrMs)
        assertEquals(1_000L, timer.timings.translationPreparationMs)
        assertEquals(1_000L, testScheduler.currentTime)
        assertTrue(formatListenDiagnostics(ListenPipelineDiagnostics(stages = timer.timings)).contains("不相加"))
    }

    @Test fun coldPreparationRemainsInsideWallClockPlaybackBudget() = runTest {
        val started = testScheduler.currentTime
        processListenWindowStages(
            { delay(4_000L) },
            { start -> start(); delay(800L); ListenWindowResult("source", "") },
            {}, { delay(100L); "target" }, { ListenStageTimings() },
        )
        val elapsed = testScheduler.currentTime - started
        assertEquals(4_100L, elapsed)
        val factor = ListenPrefetchPolicy.updateRealtimeFactor(null, elapsed, 3_000L)
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(true, false, factor))
    }

    @Test fun exposedWaitExcludesAsrAndSourceCommitOverlap() = runTest {
        val timer = ListenStageTimer { testScheduler.currentTime }
        val result = processListenWindowStages(
            { timer.measure(ListenPipelineStage.TranslationPreparation) { delay(4_000L) } },
            { start -> start(); timer.measure(ListenPipelineStage.Asr) { delay(800L) }; ListenWindowResult("s", "") },
            { delay(200L) }, { delay(100L); "t" }, { timer.timings },
            { await -> timer.measure(ListenPipelineStage.TranslationPreparationWait) { await() } },
        )
        assertEquals(4_100L, testScheduler.currentTime)
        assertEquals(4_000L, result.stageTimings!!.translationPreparationMs)
        assertEquals(3_000L, result.stageTimings.translationPreparationWaitMs)
        val full = ListenPrefetchPolicy.updateRealtimeFactor(null, 4_100L, 3_000L)
        val adaptation = ListenPrefetchPolicy.updateRealtimeFactor(null,
            ListenPrefetchPolicy.adaptationElapsedMs(4_100L, result.stageTimings.translationPreparationWaitMs), 3_000L)
        assertEquals(0L, ListenPrefetchPolicy.lookAheadMs(true, false, full))
        assertEquals(30_000L, ListenPrefetchPolicy.lookAheadMs(true, false, adaptation))
    }

    @Test fun fullyOverlappedPreparationDoesNotRemoveRealRecognitionWork() = runTest {
        val timer = ListenStageTimer { testScheduler.currentTime }
        processListenWindowStages(
            { delay(100L) },
            { start -> start(); delay(800L); ListenWindowResult("s", "") },
            {}, { "t" }, { timer.timings },
            { await -> timer.measure(ListenPipelineStage.TranslationPreparationWait) { await() } },
        )
        assertEquals(0L, timer.timings.translationPreparationWaitMs)
        assertEquals(800L, ListenPrefetchPolicy.adaptationElapsedMs(800L, timer.timings.translationPreparationWaitMs))
    }

    @Test fun concurrentStageAndRemoteReadUpdatesNeverEraseEachOther() = runBlocking {
        repeat(200) {
            val timer = ListenStageTimer { 10L }
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { timer.measure(ListenPipelineStage.Asr) {} },
                    async(Dispatchers.Default) { timer.measure(ListenPipelineStage.TranslationPreparation) {} },
                    async(Dispatchers.Default) { timer.measure(ListenPipelineStage.PcmRead) {} },
                    async(Dispatchers.Default) { timer.recordRemoteReads(ListenRemoteReadStats(3, 1024L)) },
                ).awaitAll()
            }
            assertEquals(0L, timer.timings.asrMs)
            assertEquals(0L, timer.timings.translationPreparationMs)
            assertEquals(0L, timer.timings.pcmReadMs)
            assertEquals(3, timer.timings.remoteReads)
        }
    }
}
