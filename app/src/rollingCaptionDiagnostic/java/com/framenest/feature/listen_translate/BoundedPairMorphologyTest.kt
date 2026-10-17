package com.framenest.feature.listen_translate

import com.atilika.kuromoji.ipadic.Tokenizer
import com.framenest.feature.listen_translate.experimental.BaselineCue
import com.framenest.feature.listen_translate.experimental.BaselinePairPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPairMorphologyTest {
    private val tokenizer by lazy { Tokenizer() }

    @Test fun whitespaceIsAnalysisOnlyAndRawOffsetsRemainExact() {
        val raw = " \t日本\n語　です。 "
        val words = boundedPairMorphemes(tokenizer, raw)
        assertTrue(words.isNotEmpty())
        assertEquals(raw.filterNot { it.isWhitespace() }, words.joinToString("") { it.surface })
        for (word in words) assertEquals(word.surface,
            raw.substring(word.rawStart, word.rawEnd).filterNot { it.isWhitespace() })
    }

    @Test fun supplementaryCharactersKeepUtf16Offsets() {
        val raw = "😀 中 国です"
        val words = boundedPairMorphemes(tokenizer, raw)
        assertEquals(raw.filterNot { it.isWhitespace() }, words.joinToString("") { it.surface })
        for (word in words) assertEquals(word.surface,
            raw.substring(word.rawStart, word.rawEnd).filterNot { it.isWhitespace() })
        assertTrue(words.any { it.surface == "中国" && it.rawStart == 3 && it.rawEnd == 6 })
    }

    @Test fun analysisCanJoinASeamWithoutChangingAnyOutputByteOrCue() {
        val cues = listOf(BaselineCue(0, 0, 3_000, 800, 2_800, "中 "),
            BaselineCue(1, 3_000, 6_000, 3_100, 5_600, "国です。"))
        val units = BaselinePairPlanner { boundedPairMorphemes(tokenizer, it) }.plan(cues)
        assertEquals(1, units.size)
        assertEquals(cues, units.single().cues)
        assertEquals("中 国です。", units.single().textSrc)
        assertEquals("中国", units.single().crossingMorpheme?.surface)
        assertEquals(6_000L, units.single().requiredAudioHorizonMs)
    }

    @Test fun blankAnalysisProducesNoMorphemes() {
        assertTrue(boundedPairMorphemes(tokenizer, " \t\r\n　").isEmpty())
    }
}
