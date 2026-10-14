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
