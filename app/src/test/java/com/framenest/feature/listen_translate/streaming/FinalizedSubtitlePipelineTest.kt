package com.framenest.feature.listen_translate.streaming

import com.framenest.feature.listen_translate.asr.streaming.StreamingAsrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalizedSubtitlePipelineTest {
    private val window = StreamingSubtitleWindow(1L, 0L, 3_000L)
    private fun partial(revision: Long, text: String = "hello") =
        StreamingAsrEvent.Partial(1L, 0L, revision, 0L, text)
    private fun final(id: Long = 0L, start: Long = 0L, end: Long = 1_000L, text: String = "hello") =
        StreamingAsrEvent.Final(1L, id, start, end, text)
    private fun done() = StreamingAsrEvent.Finished(1L, 3_000L)

    @Test fun manyPartialsTranslateOnlyOnceWhenFinalAndCompleteAfterTranslation() = runTest {
        var translations = 0
        var published = 0
        var completed = false
        val events = flow {
            repeat(100) { emit(partial(it.toLong(), "hypothesis $it")) }
            emit(final())
            emit(done())
        }
        FinalizedSubtitlePipeline().run(window, events, { true }, {}, {}, {
            translations++; delay(50L); "你好"
        }, { published++; assertFalse(completed) }, {
            completed = true
            assertEquals(1, it.speechClauses)
        })
        assertEquals(1, translations)
        assertEquals(1, published)
        assertTrue(completed)
    }

    @Test fun partialOrAbruptFlowEndCannotMarkWindowCovered() = runTest {
        for (events in listOf(flowOf(partial(1L), done()), flowOf(final()))) {
            var covered = false
            val failure = runCatching {
                FinalizedSubtitlePipeline().run(window, events, { true }, {}, {}, { "target" }, {}, { covered = true })
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(covered)
        }
    }

    @Test fun queueBackpressuresNasProducerAndSerializesMt() = runTest {
        var produced = 0
        var active = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val work = async {
            FinalizedSubtitlePipeline().run(window, flow {
                repeat(6) {
                    emit(final(it.toLong(), it * 400L, (it + 1L) * 400L))
                    produced++
                }
                emit(done())
            }, { true }, {}, {}, {
                active++; peak = maxOf(peak, active)
                release.await(); delay(1L); active--; "target"
            }, {}, {})
        }
        runCurrent()
        assertEquals(1, active)
        assertTrue("only in-flight + two queued can pass send", produced <= 3)
        release.complete(Unit)
        work.await()
        assertEquals(6, produced)
        assertEquals(1, peak)
    }

    @Test fun staleGenerationAndCancelledTranslationCannotPublishOrCover() = runTest {
        var current = true
        var sourcePublished = false
        var targetPublished = false
        var covered = false
        val release = CompletableDeferred<Unit>()
        val work = launch {
            FinalizedSubtitlePipeline().run(window, flowOf(final(), done()), { current }, {},
                { sourcePublished = true }, { release.await(); "late" },
                { targetPublished = true }, { covered = true })
        }
        runCurrent()
        assertTrue(sourcePublished)
        current = false
        release.complete(Unit)
        work.join()
        assertFalse(targetPublished)
        assertFalse(covered)
    }

    @Test fun cancelledConsumerWakesBackpressuredProducer() = runTest {
        for (staleGeneration in listOf(false, true)) {
            var current = true
            var covered = false
            val release = CompletableDeferred<Unit>()
            val work = async {
                runCatching {
                    FinalizedSubtitlePipeline().run(window, flow {
                        repeat(6) { emit(final(it.toLong(), it * 400L, (it + 1L) * 400L)) }
                        emit(done())
                    }, { current }, {}, {}, {
                        release.await()
                        if (!staleGeneration) throw CancellationException("MT cancelled")
                        "target"
                    }, {}, { covered = true })
                }
            }
            runCurrent()
            if (staleGeneration) current = false
            release.complete(Unit)
            runCurrent()
            assertTrue("producer must wake after consumer cancellation", work.isCompleted)
            assertTrue(work.await().exceptionOrNull() is CancellationException)
            assertFalse(covered)
        }
    }

    @Test fun cancelledConsumerAlsoStopsIdleUpstreamFlow() = runTest {
        var upstreamReleased = false
        val release = CompletableDeferred<Unit>()
        val work = async {
            runCatching {
                FinalizedSubtitlePipeline().run(window, flow {
                    try { emit(final()); awaitCancellation() }
                    finally { upstreamReleased = true }
                }, { true }, {}, {}, {
                    release.await(); throw CancellationException("MT cancelled")
                }, {}, { fail("cancelled flow must not cover") })
            }
        }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(work.isCompleted)
        assertTrue(upstreamReleased)
        assertTrue(work.await().exceptionOrNull() is CancellationException)
    }

    @Test fun translationFailureRetainsFinalSourceButCannotCoverWindow() = runTest {
        var sourcePublished = false
        var covered = false
        val failure = runCatching {
            FinalizedSubtitlePipeline().run(window, flowOf(final(), done()), { true }, {},
                { sourcePublished = true }, { error("offline MT unavailable") },
                { fail("target must not publish") }, { covered = true })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(sourcePublished)
        assertFalse(covered)
    }

    @Test fun cancelClearsPartialAndStopsInFlightConsumer() = runTest {
        var activePartial: StreamingAsrEvent.Partial? = null
        var released = false
        val work = launch {
            FinalizedSubtitlePipeline().run(window, flow {
                emit(final())
                emit(StreamingAsrEvent.Partial(1L, 1L, 1L, 1_000L, "next"))
                awaitCancellation()
            }, { true }, { activePartial = it }, {}, {
                try { awaitCancellation() } finally { released = true }
            }, {}, { fail("cancelled window must not cover") })
        }
        runCurrent()
        assertNotNull(activePartial)
        work.cancelAndJoin()
        assertTrue(released)
        assertNull(activePartial)
    }

    @Test fun replayedFinalAndStalePartialNeverRetranslate() = runTest {
        var calls = 0
        FinalizedSubtitlePipeline().run(window,
            flowOf(partial(3L), partial(2L), final(), final(), partial(4L), done()),
            { true }, {}, {}, { calls++; "target" }, {}, {})
        assertEquals(1, calls)
    }

    @Test fun foreignGenerationCannotChangeCurrentPartialOrPersist() = runTest {
        var translations = 0
        var seen = 0
        FinalizedSubtitlePipeline().run(window, flowOf(
            StreamingAsrEvent.Partial(0L, 0L, 1L, 0L, "stale"),
            StreamingAsrEvent.Final(0L, 0L, 0L, 1_000L, "stale"), done()),
            { true }, { if (it != null) seen++ }, {}, { translations++; "target" }, {}, {
                assertEquals(0, it.speechClauses)
            })
        assertEquals(0, translations)
        assertEquals(0, seen)
    }

    @Test fun wrongCoverageEndOrOversizedTextFailsClosed() = runTest {
        val invalid = listOf(
            flowOf(StreamingAsrEvent.Finished(1L, 2_999L)),
            flowOf(final(text = "x".repeat(2_049)), done()),
            flowOf(final(end = 0L), done()),
            flowOf(final(), StreamingAsrEvent.Final(1L, 1L, 500L, 2_000L, "overlap"), done()),
        )
        for (events in invalid) {
            var covered = false
            assertNotNull(runCatching {
                FinalizedSubtitlePipeline().run(window, events, { true }, {}, {}, { "target" }, {}, { covered = true })
            }.exceptionOrNull())
            assertFalse(covered)
        }
    }

    @Test fun emptyTranslationCannotPretendBilingualCompletion() = runTest {
        var covered = false
        assertNotNull(runCatching {
            FinalizedSubtitlePipeline().run(window, flowOf(final(), done()), { true }, {}, {}, { " " }, {}, { covered = true })
        }.exceptionOrNull())
        assertFalse(covered)
    }
}
