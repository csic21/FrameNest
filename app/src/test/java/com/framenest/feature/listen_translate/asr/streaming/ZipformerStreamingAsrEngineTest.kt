package com.framenest.feature.listen_translate.asr.streaming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ZipformerStreamingAsrEngineTest {
    private class FakeRecognizer(val endpoint: Boolean = false) : ZipformerNativeRecognizer {
        var releases = 0
        val streams = mutableListOf<FakeStream>()
        override fun stream() = FakeStream(endpoint).also { streams += it }
        override fun release() { releases++ }
    }
    private class FakeStream(val endpointValue: Boolean) : ZipformerNativeStream {
        var finished = false
        var released = 0
        var pending = false
        var decoded = 0
        var accepted = mutableListOf<Int>()
        var resultOverride: NativeStreamingResult? = null
        var onDecode: (() -> Unit)? = null
        override fun accept(samples: FloatArray) { accepted += samples.size; pending = true }
        override fun finish() { finished = true; pending = true }
        override fun ready() = pending
        override fun decode() { onDecode?.invoke(); pending = false; decoded++ }
        override fun result() = resultOverride ?: NativeStreamingResult(if (finished) "final" else "partial", listOf("a"), listOf(0.025f))
        override fun endpoint() = endpointValue
        override fun release() { released++ }
    }
    private fun chunks(count: Int) = (0 until count).map { StreamingPcmChunk(it * 1600L, ShortArray(1600)) }.asFlow()

    @Test fun partialThenDrainedFinalAndFinishedPreserveMediaTimestamp() = runBlocking {
        val native = FakeRecognizer()
        val engine = ZipformerStreamingAsrEngine { native }
        val events = engine.recognize(StreamingAsrRequest(1, 12000), chunks(2)).toList()
        assertEquals(3, events.size) // unchanged partial is coalesced
        val partial = events[0] as StreamingAsrEvent.Partial
        assertEquals(12025, partial.tokens.single().startMs)
        assertEquals(1, partial.revision)
        val final = events[1] as StreamingAsrEvent.Final
        assertEquals("final", final.text)
        assertEquals(12200, final.endMs)
        assertEquals(StreamingAsrEvent.Finished(1, 12200), events.last())
        assertTrue(native.streams.single().finished)
        assertEquals(listOf(1600, 1600, 10560), native.streams.single().accepted)
        assertEquals(1, native.streams.single().released)
        engine.close()
        assertEquals(1, native.releases)
    }

    @Test fun endpointRotatesAtKnownInputBoundaryWithoutReusingResetTimestamps() = runBlocking {
        val native = FakeRecognizer(endpoint = true)
        val engine = ZipformerStreamingAsrEngine { native }
        val finals = engine.recognize(StreamingAsrRequest(1, 500), chunks(2)).toList().filterIsInstance<StreamingAsrEvent.Final>()
        assertEquals(listOf(0L, 1L), finals.map { it.segmentId })
        assertEquals(listOf(525L, 625L), finals.map { it.tokens.single().startMs })
        assertTrue(native.streams.all { it.finished && it.released == 1 })
        engine.close()
    }

    @Test fun rejectsGapOversizedChunkAndWindowOverflowWithoutFinished() = runBlocking {
        val invalid = listOf(
            listOf(StreamingPcmChunk(1, ShortArray(10))),
            listOf(StreamingPcmChunk(0, ShortArray(3201))),
            listOf(StreamingPcmChunk(0, ShortArray(1600)), StreamingPcmChunk(1600, ShortArray(1600))),
        )
        for (input in invalid) {
            val native = FakeRecognizer()
            val engine = ZipformerStreamingAsrEngine { native }
            val events = mutableListOf<StreamingAsrEvent>()
            try {
                engine.recognize(StreamingAsrRequest(1, 0, 1600), input.asFlow()).collect { events += it }
                fail("Invalid input accepted")
            } catch (_: IllegalArgumentException) { }
            assertFalse(events.any { it is StreamingAsrEvent.Finished })
            assertTrue(native.streams.all { it.released == 1 })
            engine.close()
        }
    }

    @Test fun cancellationDuringNativeDecodeRejectsLateResultAndReleases() = runBlocking {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val stream = FakeStream(false).apply { onDecode = { entered.countDown(); check(leave.await(5, TimeUnit.SECONDS)) } }
        val engine = ZipformerStreamingAsrEngine { object : ZipformerNativeRecognizer {
            override fun stream() = stream
            override fun release() = Unit
        } }
        val events = mutableListOf<StreamingAsrEvent>()
        val collecting = async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { engine.recognize(StreamingAsrRequest(1, 0), chunks(1)).collect { events += it } }
        }
        check(entered.await(5, TimeUnit.SECONDS))
        engine.cancel(1)
        assertEquals(0, stream.released) // no JNI release races decode
        leave.countDown()
        assertTrue(collecting.await().isFailure)
        assertTrue(events.isEmpty())
        assertEquals(1, stream.released)
        engine.close()
    }

    @Test fun generationCannotBeReplayedAndInputFailureDoesNotFinish() = runBlocking {
        val native = FakeRecognizer()
        val engine = ZipformerStreamingAsrEngine { native }
        engine.recognize(StreamingAsrRequest(2, 0), chunks(1)).toList()
        assertTrue(runCatching { engine.recognize(StreamingAsrRequest(2, 0), chunks(1)).toList() }.isFailure)
        val events = mutableListOf<StreamingAsrEvent>()
        assertTrue(runCatching { engine.recognize(StreamingAsrRequest(3, 0), flow {
            emit(StreamingPcmChunk(0, ShortArray(1600)))
            error("input failure")
        }).collect { events += it } }.isFailure)
        assertFalse(events.any { it is StreamingAsrEvent.Finished })
        assertTrue(native.streams.all { it.released == 1 })
        engine.close()
        assertTrue(runCatching { engine.recognize(StreamingAsrRequest(4, 0), chunks(1)).toList() }.isFailure)
    }
    @Test fun mismatchedTokenTimingIsOmittedRatherThanInvented() = runBlocking {
        val stream = FakeStream(false).apply {
            resultOverride = NativeStreamingResult("a", listOf("a"), listOf(0.01f, 0.02f))
        }
        val engine = ZipformerStreamingAsrEngine { object : ZipformerNativeRecognizer {
            override fun stream() = stream
            override fun release() = Unit
        } }
        val result = engine.recognize(StreamingAsrRequest(1, 0), chunks(1)).toList()
        assertTrue(result.filterIsInstance<StreamingAsrEvent.Partial>().single().tokens.isEmpty())
        assertTrue(result.filterIsInstance<StreamingAsrEvent.Final>().single().tokens.isEmpty())
        engine.close()
    }

    @Test fun cancellationUnblocksSuspendedInputAndClose() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val native = FakeRecognizer()
        val engine = ZipformerStreamingAsrEngine { native }
        val collecting = async {
            runCatching { engine.recognize(StreamingAsrRequest(1, 0), flow {
                emit(StreamingPcmChunk(0, ShortArray(1600)))
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }).toList() }
        }
        entered.await()
        engine.cancel(1)
        kotlinx.coroutines.withTimeout(2000) {
            assertTrue(collecting.await().isFailure)
            engine.close()
        }
        assertEquals(1, native.streams.single().released)
        assertEquals(1, native.releases)
    }

    @Test fun finalTokenNeedsTailContextButPaddingDoesNotAdvanceMediaCoverage() = runBlocking {
        var padded = false
        var finished = false
        var ready = false
        var decodedTail = false
        val engine = ZipformerStreamingAsrEngine { object : ZipformerNativeRecognizer {
            override fun stream() = object : ZipformerNativeStream {
                override fun accept(samples: FloatArray) { if (samples.size == 10560) padded = true }
                override fun finish() { finished = true; ready = padded }
                override fun ready() = ready
                override fun decode() { decodedTail = finished && padded; ready = false }
                override fun result() = NativeStreamingResult(if (decodedTail) "tail" else "", emptyList(), emptyList())
                override fun endpoint() = false
                override fun release() = Unit
            }
            override fun release() = Unit
        } }
        val result = engine.recognize(StreamingAsrRequest(1, 300), chunks(1)).toList()
        assertEquals("tail", result.filterIsInstance<StreamingAsrEvent.Final>().single().text)
        assertEquals(StreamingAsrEvent.Finished(1, 400), result.last())
        engine.close()
    }

    @Test fun newerGenerationCancelsIdlePredecessorAndUsesFreshMediaAnchor() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val native = FakeRecognizer()
        val engine = ZipformerStreamingAsrEngine { native }
        val first = async {
            runCatching { engine.recognize(StreamingAsrRequest(1, 0), flow {
                emit(StreamingPcmChunk(0, ShortArray(1600)))
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }).toList() }
        }
        entered.await()
        val second = kotlinx.coroutines.withTimeout(2000) {
            engine.recognize(StreamingAsrRequest(2, 5000), chunks(1)).toList()
        }
        assertTrue(first.await().isFailure)
        assertEquals(StreamingAsrEvent.Finished(2, 5100), second.last())
        assertEquals(5025, second.filterIsInstance<StreamingAsrEvent.Final>().single().tokens.single().startMs)
        assertTrue(native.streams.all { it.released == 1 })
        engine.close()
    }

    @Test fun emptyInputDoesNotClaimFinishedWithoutNativeFlush() = runBlocking {
        val native = FakeRecognizer()
        val engine = ZipformerStreamingAsrEngine { native }
        val events = mutableListOf<StreamingAsrEvent>()
        assertTrue(runCatching {
            engine.recognize(StreamingAsrRequest(1, 0), chunks(0)).collect { events += it }
        }.isFailure)
        assertTrue(events.isEmpty())
        engine.close()
    }

}
