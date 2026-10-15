package com.framenest.feature.listen_translate.recorded

import org.junit.Assert.*
import org.junit.Test

class JapaneseSourceBoundaryTest {
    private val real = KuromojiJapaneseMorphology()
    private fun anchors(text: String, starts: List<Long> = text.indices.map { it * 100L }) = text.indices.map {
        RecordedCharacterAnchor(it, it + 1, 0, 15_000, it, it + 1, starts[it])
    }

    @Test fun realTokenizerPreservesUnicodeOffsetsWhileJoiningAsrSpaces() {
        val raw = "🙂 この イラスト の 意味 わかり ま すか 中国語"
        val parsed = real.analyze(raw)
        assertEquals("ます", parsed.first { it.surface == "ます" }.surface)
        assertTrue(parsed.any { it.surface == "中国" })
        parsed.forEach { assertEquals(it.surface, raw.substring(it.rawStart, it.rawEnd).filterNot(Char::isWhitespace)) }
        assertTrue(parsed.all { it.rawStart == 0 || !Character.isLowSurrogate(raw[it.rawStart]) })
    }

    @Test fun realTerminalQuestionUsesMorphologyWithoutPunctuation() {
        val text = "わかりますか次の説明です"
        val cut = JapaneseSourceBoundary(real).first(text, anchors(text), listOf(RecordedQuietSpan(550, 850)), 15_000)
        assertNotNull(cut)
        assertEquals("わかりますか", text.substring(0, cut!!.rawEnd))
        assertEquals(RecordedSourceReason.MORPHOLOGY_AND_PCM_QUIET, cut.reason)
    }

    @Test fun bareNounAndConjunctiveFalsePeriodAreNotCompleteEvenAtEof() {
        for (text in listOf("中国語", "では。", "それ は ビジネス 文章 で は。", "英語、スペイン語、中国語、", "使われているのは")) {
            assertNull(text, JapaneseSourceBoundary(real).first(text, anchors(text), emptyList(), 15_000, eof = true))
        }
    }

    @Test fun eofAcceptsActualCompletePredicateWithoutInventedPunctuation() {
        val text = "世界中で使われています"
        val cut = JapaneseSourceBoundary(real).first(text, anchors(text), emptyList(), 15_000, eof = true)
        assertEquals(text.length, cut!!.rawEnd)
        assertEquals(RecordedSourceReason.MORPHOLOGY_AT_EOF, cut.reason)
    }

    @Test fun tokenGapAloneAndCommaBeforeDependentContinuationAreNotEnough() {
        val text = "人気です次の話です"
        assertNull(JapaneseSourceBoundary(real).first(text, anchors(text), emptyList(), 15_000))
        val comma = "人気です、が続けます"
        assertNull(JapaneseSourceBoundary(real).first(comma, anchors(comma), listOf(RecordedQuietSpan(350, 650)), 15_000))
    }

    @Test fun fakeTokenizerTestsDoNotNeedJapaneseSuffixGrammar() {
        val fake = JapaneseMorphology { listOf(
            JapaneseMorpheme(0, 1, "X", "助動詞", "*", "基本形", true),
            JapaneseMorpheme(1, 2, "Y", "助詞", "終助詞", "*", true),
            JapaneseMorpheme(2, 3, "Z", "名詞", "一般", "*", true),
        ) }
        val cut = JapaneseSourceBoundary(fake).first("XYZ", anchors("XYZ", listOf(0, 100, 800)),
            listOf(RecordedQuietSpan(300, 600)), 2000)
        assertEquals(2, cut!!.rawEnd)
    }

    @Test fun plainPredicateBeforeNominalPhraseIsNotMistakenForIndependentSentence() {
        val text="使う新しい表現スタイルです"
        assertNull(JapaneseSourceBoundary(real).first(text,anchors(text,listOf(0L,100L)+List(text.length-2) { 1200L }),
            listOf(RecordedQuietSpan(300,800)),3000))
        val polite="使います、新しい話をします"
        val times=polite.indices.map { if(it<5) it*100L else 1200L }
        assertNotNull(JapaneseSourceBoundary(real).first(polite,anchors(polite,times),
            listOf(RecordedQuietSpan(500,900)),3000))
    }

    @Test fun onsetToleranceIsBoundedAndDoesNotReplaceActualQuietEvidence() {
        val fake = JapaneseMorphology { listOf(
            JapaneseMorpheme(0,1,"X","助動詞","*","基本形",true),
            JapaneseMorpheme(1,2,"Y","接続詞","*","*",true),
        ) }
        val policy=JapaneseSourceBoundary(fake)
        val mapped=anchors("XY",listOf(800,1200))
        assertNotNull(policy.first("XY",mapped,listOf(RecordedQuietSpan(550,1000)),3000))
        assertNull(policy.first("XY",mapped,listOf(RecordedQuietSpan(549,1000)),3000))
        assertNull(policy.first("XY",mapped,emptyList(),3000))
    }

    @Test fun actualPcmQuietDetectorRejectsTokenGapsAndNonfiniteSamples() {
        val pcm = FloatArray(16_000) { 0.2f }
        for (i in 3200 until 8000) pcm[i] = 0f
        assertEquals(listOf(RecordedQuietSpan(1200, 1500)), RecordedPcmQuietDetector.detect(pcm, 16000, 1000))
        assertTrue(RecordedPcmQuietDetector.detect(FloatArray(16000) { Float.NaN }, 16000, 0).isEmpty())
    }
}
