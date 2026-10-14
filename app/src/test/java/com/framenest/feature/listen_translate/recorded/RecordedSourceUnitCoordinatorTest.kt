package com.framenest.feature.listen_translate.recorded

import com.framenest.feature.listen_translate.RecordedHypothesis
import com.framenest.feature.listen_translate.RecordedToken
import org.junit.Assert.*
import org.junit.Test

class RecordedSourceUnitCoordinatorTest {
    companion object { private val morphology = KuromojiJapaneseMorphology() }
    private val noBoundaries = JapaneseMorphology { emptyList() }
    private val question1 = "この イラスト の 意味 わかり ま すか"
    private val question2 = " 今、世界 中 で 使 われ て いる の は 何語 でしょ うか"
    private val tail = " 英語、スペイン語、中国語、それ は ビジネス 文章 で は。"
    private fun letters(text: String, start: Long, end: Long): List<RecordedToken> {
        val points = text.codePoints().toArray()
        return points.mapIndexed { i, cp -> RecordedToken(String(Character.toChars(cp)), start + i * (end - start) / points.size) }
    }
    private fun sample() = RecordedWindowInput(RecordedHypothesis(0, 0, 15_000,
        letters(question1, 1000, 5000) + letters(question2, 6000, 10000) + letters(tail, 11000, 14500)),
        listOf(RecordedQuietSpan(5100, 5500), RecordedQuietSpan(10100, 10500)))
    private val phrase = "昨日の事件について詳しく説明します"
    private fun first(text: String = phrase) = RecordedWindowInput(RecordedHypothesis(0, 0, 15_000,
        listOf(RecordedToken("導入🙂。", 1000)) + letters(text, 9000, 12000)), emptyList())
    private fun second(text: String = phrase) = RecordedWindowInput(RecordedHypothesis(0, 9000, 24_000,
        letters(text, 9000, 12000) + listOf(RecordedToken("次の話です。", 17000))), emptyList())

    @Test fun firstTwoUnpunctuatedQuestionsAreReadyAndFalsePeriodRemainsPending() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val r = c.push(sample())
        assertTrue(r.accepted)
        assertEquals(listOf(question1, question2), r.readyUnits.map { it.unit.text })
        assertTrue(r.readyUnits.all { it.unit.status == RecordedSourceStatus.FINAL })
        assertEquals(tail, r.pendingText)
        assertEquals(sample().hypothesis.text, r.readyUnits.joinToString("") { it.unit.text } + r.pendingText)
        assertEquals(15_000L, r.readThroughMs)
        assertEquals(9_000L, r.nextWindowStartMs)
        assertNull(r.completedCoverage)
        val success = c.translationSucceeded(r.readyUnits[0], "这个插图是什么意思？")
        assertEquals(0L, success.completedCoverage!!.startMs)
        assertEquals(5300L, success.completedCoverage.endMs)
        assertEquals(1000L, r.readyUnits[0].unit.displayStartMs)
        assertEquals(5300L, r.readyUnits[0].unit.displayEndMs)
        assertEquals(9_000L, success.nextWindowStartMs)
    }

    @Test fun originalCharacterAnchorsReconstructEveryUnmodifiedUtf16Slice() {
        val input = sample()
        val r = RecordedSourceUnitCoordinator(morphology).push(input)
        for (ticket in r.readyUnits) for (a in ticket.unit.anchors) {
            assertEquals(input.hypothesis.text.substring(a.hypothesisRawStart, a.hypothesisRawEnd),
                ticket.unit.text.substring(a.unitRawStart, a.unitRawEnd))
            assertTrue(a.estimatedTokenStartMs < 15_000)
        }
    }

    @Test fun frozenStitchSeamIsNeverTranslatedAsItsOwnUnit() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        assertTrue(c.push(first()).readyUnits.isEmpty())
        val r = c.push(second())
        val expected = "導入🙂。" + phrase + "次の話です。"
        assertEquals(expected, r.pendingText)
        assertEquals(expected, r.readyUnits.single().unit.text)
        assertEquals(RecordedSourceStatus.PROVISIONAL, r.readyUnits.single().unit.status)
        assertNull(c.translationSucceeded(r.readyUnits.single(), "临时译文").completedCoverage)
    }

    @Test fun weakAlignmentGetsOneUnionThenRetainedSourceAndContinuedGrid() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        c.push(first())
        val r = c.push(second("これは全く別の認識結果です"))
        assertTrue(r.nextAction is RecordedNextAction.DecodeUnion)
        assertEquals(18_000L, r.nextWindowStartMs)
        assertFalse(c.push(second()).accepted)
        val failed = c.failUnion(0)
        assertEquals(first().hypothesis.text, failed.readyUnits.single().unit.text)
        assertEquals(RecordedSourceStatus.UNCONFIRMED, failed.readyUnits.single().unit.status)
        assertEquals("これは全く別の認識結果です次の話です。", failed.pendingText)
        assertFalse(c.failUnion(0).accepted)
        c.translationSucceeded(failed.readyUnits.single(), "未确认")
        assertEquals(RecordedNextAction.DecodeWindow(18_000), c.snapshot().nextAction)
    }

    @Test fun emptyUnionRetainsOldSourceAndLateWrongRangeCannotReplacePending() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        c.push(first()); c.push(second("違う認識結果になりました"))
        val wrong = RecordedWindowInput(RecordedHypothesis(0,9000,24000,listOf(RecordedToken("遅れた別の返答",9000))),emptyList())
        assertEquals("stale_union_window",c.recoverUnion(wrong).error)
        val r = c.recoverUnion(RecordedWindowInput(RecordedHypothesis(0,0,24000,emptyList()),emptyList()))
        assertEquals(first().hypothesis.text,r.readyUnits.single().unit.text)
        assertEquals(RecordedSourceStatus.UNCONFIRMED,r.readyUnits.single().unit.status)
        assertEquals(18000L,r.nextWindowStartMs)
    }

    @Test fun boundedUnionCanReplaceWholeUncommittedContext() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        c.push(first()); c.push(second("別の認識を確認します"))
        val union = RecordedWindowInput(RecordedHypothesis(0, 0, 24_000,
            listOf(RecordedToken("復元された文脈🙂をそのまま残します", 1000))), emptyList())
        val r = c.recoverUnion(union)
        assertTrue(r.accepted)
        assertEquals(union.hypothesis.text, r.pendingText)
        assertEquals(18_000L, r.nextWindowStartMs)
        assertFalse(c.recoverUnion(union).accepted)
    }

    @Test fun longNoBoundaryContextRotatesWithPersistableProvisionalWithoutDroppingOldText() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        c.push(first()); val at24 = c.push(second())
        c.translationSucceeded(at24.readyUnits.single(), "临时")
        val next = RecordedWindowInput(RecordedHypothesis(0, 18000, 33000,
            listOf(RecordedToken("継続する新しい録音内容", 19000))), emptyList())
        val r = c.push(next)
        assertEquals(27000L, r.nextWindowStartMs)
        assertEquals(next.hypothesis.text, r.pendingText)
        assertTrue(at24.records.any { it.unit.text == at24.pendingText })
        assertTrue("rotation must not translate the same context twice", r.readyUnits.isEmpty())
        assertTrue(r.records.none { it.unit.status == RecordedSourceStatus.FINAL })
        assertTrue(r.pendingText.length <= 1024)
    }

    @Test fun isolatedCompleteSpeechWithMeasuredTrailingSilenceDoesNotWaitForAnotherToken() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val input = RecordedWindowInput(RecordedHypothesis(0,0,15000,listOf(RecordedToken("人気です",0))),
            listOf(RecordedQuietSpan(500,15000)))
        val r = c.push(input)
        assertEquals(RecordedSourceStatus.FINAL,r.readyUnits.single().unit.status)
        assertEquals(580L,r.readyUnits.single().unit.displayEndMs)
        assertEquals(9000L,r.nextWindowStartMs)
        assertNull(r.completedCoverage)
    }

    @Test fun eofIncompleteTailIsProvisionalAndIdempotent() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val input = RecordedWindowInput(RecordedHypothesis(0, 0, 1000, listOf(RecordedToken("中国語", 0))), emptyList())
        c.push(input)
        val eof = c.finish(0, 1000)
        assertEquals("中国語", eof.readyUnits.single().unit.text)
        assertEquals(RecordedSourceReason.INCOMPLETE_EOF, eof.readyUnits.single().unit.reason)
        assertNull(c.translationSucceeded(eof.readyUnits.single(), "中文").completedCoverage)
        assertEquals(RecordedNextAction.Finished, c.finish(0, 1000).nextAction)
        assertTrue(c.finish(0, 1000).readyUnits.isEmpty())
    }

    @Test fun explicitEofAllowsOneMillisecondPcmRoundingWithoutInventingCoveragePastEof() {
        val c = RecordedSourceUnitCoordinator(morphology)
        c.push(RecordedWindowInput(RecordedHypothesis(0,0,1000,listOf(RecordedToken("人気です",0))), emptyList()))
        val r = c.finish(0,999)
        assertTrue(r.accepted)
        assertEquals(999L,r.readThroughMs)
        assertEquals(999L,c.translationSucceeded(r.readyUnits.single(),"很受欢迎").completedCoverage!!.endMs)
    }

    @Test fun eofCompleteMorphologyCanBeFinalButNeedsMtSuccess() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val input = RecordedWindowInput(RecordedHypothesis(0, 0, 1000, listOf(RecordedToken("人気です", 0))), emptyList())
        c.push(input)
        val eof = c.finish(0, 1000)
        assertNull(eof.completedCoverage)
        assertEquals(RecordedSourceStatus.FINAL, eof.readyUnits.single().unit.status)
        assertNotNull(c.translationSucceeded(eof.readyUnits.single(), "很受欢迎").completedCoverage)
    }

    @Test fun deferProvisionalRetainsSourceWithoutFailureRetryOrCoverage() {
        val c = RecordedSourceUnitCoordinator(morphology)
        c.push(RecordedWindowInput(RecordedHypothesis(0,0,1000,listOf(RecordedToken("中国語",0))),emptyList()))
        val ticket = c.finish(0,1000).readyUnits.single()
        val r = c.deferUnconfirmed(ticket)
        assertTrue(r.accepted)
        assertEquals(RecordedResultState.SOURCE_READY,r.records.single().state)
        assertEquals(ticket.unit,r.records.single().unit)
        assertEquals("source_pending_confirmation",r.records.single().deferredReason)
        assertNull(r.records.single().error)
        assertNull(r.retryTranslation)
        assertNull(r.completedCoverage)
        assertEquals(RecordedNextAction.Finished,r.nextAction)
        assertFalse(c.deferUnconfirmed(ticket).accepted)
        assertFalse(c.translationSucceeded(ticket,"late").accepted)
        assertFalse(c.retryTranslation(ticket.copy(attempt=2)).accepted)
    }

    @Test fun deferUnconfirmedAlignmentRetainsSourceAndContinuesReading() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        c.push(first()); c.push(second("異なる認識結果を返しました"))
        val ticket = c.failUnion(0).readyUnits.single()
        assertEquals(RecordedSourceStatus.UNCONFIRMED,ticket.unit.status)
        val r = c.deferUnconfirmed(ticket)
        assertTrue(r.accepted)
        assertEquals(first().hypothesis.text,r.records.single().unit.text)
        assertEquals(RecordedNextAction.DecodeWindow(18000),r.nextAction)
        assertNull(r.completedCoverage)
        assertNull(r.retryTranslation)
    }

    @Test fun deferRejectsFinalAndStaleTicketsWithoutDequeuingFinalWork() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val finalTicket = c.push(sample()).readyUnits.first()
        assertEquals("final_source_cannot_be_deferred",c.deferUnconfirmed(finalTicket).error)
        assertEquals(finalTicket,(c.snapshot().nextAction as RecordedNextAction.Translate).ticket)
        assertNotNull(c.translationSucceeded(finalTicket,"这个插图是什么意思？").completedCoverage)
        c.reset(1,0)
        assertFalse(c.deferUnconfirmed(finalTicket).accepted)
    }

    @Test fun failedAndEmptyTranslationsNeverCompleteAndOnlyOneExplicitRetryExists() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val ticket = c.push(sample()).readyUnits.first()
        val fail = c.translationSucceeded(ticket, "  ")
        assertNull(fail.completedCoverage)
        assertEquals(RecordedResultState.TRANSLATION_FAILED, fail.records.single().state)
        val retry = fail.retryTranslation!!
        assertTrue(c.retryTranslation(retry).accepted)
        assertFalse(c.translationSucceeded(ticket, "late").accepted)
        val twice = c.translationFailed(retry, "offline")
        assertNull(twice.retryTranslation)
        assertFalse(c.retryTranslation(retry).accepted)
        assertNull(twice.completedCoverage)
    }

    @Test fun generationResetInvalidatesTailUnionAndTranslation() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val oldTicket = c.push(sample()).readyUnits.first()
        val reset = c.reset(1, 90000)
        assertEquals("", reset.pendingText)
        assertEquals(RecordedNextAction.DecodeWindow(90000), reset.nextAction)
        assertFalse(c.translationSucceeded(oldTicket, "late").accepted)
        assertFalse(c.push(sample()).accepted)
        assertFalse(c.finish(0, 15000).accepted)
        assertFalse(c.failUnion(0).accepted)
    }

    @Test fun sourceGenerationStartsAwayFromMediaBeginningRemainProvisionalUntilBoundary() {
        val c = RecordedSourceUnitCoordinator(morphology, initialStartMs = 9000)
        val h = sample().hypothesis
        val input = RecordedWindowInput(h.copy(startMs = 9000, endMs = 24000,
            tokens = h.tokens.map { it.copy(startMs = it.startMs + 9000) }),
            sample().quietSpans.map { RecordedQuietSpan(it.startMs + 9000, it.endMs + 9000) })
        val units = c.push(input).readyUnits.map { it.unit }
        assertEquals(RecordedSourceStatus.PROVISIONAL, units[0].status)
        assertEquals(RecordedSourceStatus.FINAL, units[1].status)
    }

    @Test fun queueBackpressureDoesNotConsumeInputOrAdvanceReadCursor() {
        val c = RecordedSourceUnitCoordinator(morphology, maxQueuedUnits = 3)
        val r = c.push(sample())
        assertEquals(2, r.readyUnits.size)
        val blocked = c.push(second())
        assertFalse(blocked.accepted)
        assertEquals(15000L, blocked.readThroughMs)
        assertEquals(9000L, blocked.nextWindowStartMs)
        assertTrue(blocked.nextAction is RecordedNextAction.Translate)
    }

    @Test fun hundredsOfWeakWindowsRemainBoundedAndRetainEveryUnconfirmedSource() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        val retained = mutableListOf<String>()
        for (i in 0 until 200) {
            val start = i * 9000L
            val input = RecordedWindowInput(RecordedHypothesis(0,start,start+15000,
                listOf(RecordedToken("窓${i}の原文を失わず保存します",start+1000))), emptyList())
            var r = c.push(input)
            assertTrue(r.accepted)
            if (r.nextAction is RecordedNextAction.DecodeUnion) r = c.failUnion(0)
            for (t in r.readyUnits) {
                retained += t.unit.text
                assertNull(c.translationSucceeded(t,"確認用訳").completedCoverage)
            }
            assertTrue(c.snapshot().pendingText.length <= 1024)
            assertEquals((i+1)*9000L,c.snapshot().nextWindowStartMs)
        }
        retained += c.snapshot().pendingText
        for (i in 0 until 200) assertTrue("missing source window $i",retained.any { it.contains("窓${i}の原文を失わず保存します") })
    }

    @Test fun consumedWholeHypothesisUsesFrozenAnchorInsteadOfRepeatingOverlap() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val spoken = "この説明はとても人気です"
        val a = RecordedWindowInput(RecordedHypothesis(0,0,15000,letters(spoken,9000,11000)),
            listOf(RecordedQuietSpan(11300,15000)))
        val first = c.push(a)
        assertEquals(spoken,first.readyUnits.single().unit.text)
        c.translationSucceeded(first.readyUnits.single(),"说明很受欢迎")
        val b = RecordedWindowInput(RecordedHypothesis(0,9000,24000,
            letters(spoken,9000,11000)+letters("次の説明を始めます",18000,20000)),listOf(RecordedQuietSpan(20300,24000)))
        val r = c.push(b)
        assertEquals("次の説明を始めます",r.readyUnits.single().unit.text)
        assertTrue(r.readyUnits.none { it.unit.text.contains(spoken) })
    }

    @Test fun blankAsrDoesNotLetLaterTranslationRetroactivelyCompleteUnknownAudio() {
        val c = RecordedSourceUnitCoordinator(morphology)
        val empty = c.push(RecordedWindowInput(RecordedHypothesis(0,0,15000,emptyList()),emptyList()))
        assertNull(empty.completedCoverage)
        val h = sample().hypothesis
        val next = RecordedWindowInput(h.copy(startMs=9000,endMs=24000,tokens=h.tokens.map { it.copy(startMs=it.startMs+9000) }),
            sample().quietSpans.map { RecordedQuietSpan(it.startMs+9000,it.endMs+9000) })
        val r = c.push(next)
        assertEquals(RecordedSourceStatus.PROVISIONAL,r.readyUnits[0].unit.status)
        assertNull(c.translationSucceeded(r.readyUnits[0],"未确认").completedCoverage)
        val completed = c.translationSucceeded(r.readyUnits[1],"什么语言").completedCoverage!!
        assertTrue(completed.startMs>=9000)
    }

    @Test fun noBoundaryNeverYieldsNoneAndSourceInputIsBounded() {
        val c = RecordedSourceUnitCoordinator(noBoundaries)
        val r = c.push(first())
        assertEquals(RecordedNextAction.DecodeWindow(9000), r.nextAction)
        try {
            c.push(RecordedWindowInput(RecordedHypothesis(0,9000,24000,listOf(RecordedToken("字".repeat(1025),9000))),emptyList()))
            fail("must reject oversized input before changing state")
        } catch (_: IllegalArgumentException) { }
        assertEquals(15000L,c.snapshot().readThroughMs)
    }
}
