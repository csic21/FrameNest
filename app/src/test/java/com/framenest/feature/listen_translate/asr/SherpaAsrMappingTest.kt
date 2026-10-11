package com.framenest.feature.listen_translate.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaAsrMappingTest {

    @Test
    fun supportedLanguages_areExactlyTheSinglePackFive() {
        assertEquals(
            setOf("zh", "yue", "en", "ja", "ko"),
            SherpaAsrEngine.supportedSourceLanguages(),
        )
    }

    @Test
    fun languageMapping_coversUiTagsAndFallsBackToAuto() {
        assertEquals("zh", SherpaAsrEngine.mapLanguage("zh"))
        assertEquals("zh", SherpaAsrEngine.mapLanguage(" ZH "))
        assertEquals("yue", SherpaAsrEngine.mapLanguage("yue"))
        assertEquals("yue", SherpaAsrEngine.mapLanguage("cantonese"))
        assertEquals("en", SherpaAsrEngine.mapLanguage("en"))
        assertEquals("ja", SherpaAsrEngine.mapLanguage("ja"))
        assertEquals("ko", SherpaAsrEngine.mapLanguage("ko"))
        assertEquals("auto", SherpaAsrEngine.mapLanguage("fr"))
        assertEquals("auto", SherpaAsrEngine.mapLanguage(""))
    }

    @Test
    fun chineseTokens_mapToTimedWordsWithChainedEnds() {
        val words = SherpaAsrEngine.mapTokensToWords(
            tokens = arrayOf("开", "饭", "时", "间"),
            timestamps = floatArrayOf(0.72f, 0.96f, 1.26f, 1.44f),
        )

        assertEquals(
            listOf("开", "饭", "时", "间"),
            words.map { it.text },
        )
        assertEquals(720L, words[0].startMs)
        assertEquals(960L, words[0].endMs)
        assertEquals(1440L, words[3].startMs)
        assertEquals(1440L, words[3].endMs)
    }

    @Test
    fun punctuationTokens_areKeptForSubtitleReadability() {
        val words = SherpaAsrEngine.mapTokensToWords(
            tokens = arrayOf("五", "点", "。"),
            timestamps = floatArrayOf(4.20f, 4.56f, 5.46f),
        )

        assertEquals(listOf("五", "点", "。"), words.map { it.text })
    }

    @Test
    fun englishSubwords_keepBoundariesAndStayWholeForWindowSelection() {
        val words = SherpaAsrEngine.mapTokensToWords(
            tokens = arrayOf("▁the", "▁tri", "bal", "  ", "boy"),
            timestamps = floatArrayOf(0.90f, 1.26f, 1.56f, 1.80f, 2.16f),
        )

        // A blank piece preserves the next word boundary; subwords stay together.
        assertEquals(listOf(" the", " tribal", " boy"), words.map { it.text })
        assertEquals(2160L, words[2].startMs)
        assertEquals(1260L, words[1].startMs)
        assertEquals(2160L, words[1].endMs)
        assertEquals("the tribal boy", words.joinToString("") { it.text }.trim())
    }

    @Test
    fun invalidTimestamps_dropOnlyTheirOwnToken() {
        val words = SherpaAsrEngine.mapTokensToWords(
            tokens = arrayOf("甲", "乙", "丙"),
            timestamps = floatArrayOf(0.5f, Float.NaN, 1.5f),
        )

        assertEquals(listOf("甲", "丙"), words.map { it.text })
        assertEquals(500L, words[0].startMs)
        assertEquals(1500L, words[0].endMs)
        assertEquals(1500L, words[1].startMs)
    }

    @Test
    fun missingInputs_yieldNoWords() {
        assertTrue(SherpaAsrEngine.mapTokensToWords(null, floatArrayOf(0.5f)).isEmpty())
        assertTrue(SherpaAsrEngine.mapTokensToWords(arrayOf("a"), null).isEmpty())
        assertTrue(
            SherpaAsrEngine.mapTokensToWords(
                tokens = emptyArray(),
                timestamps = floatArrayOf(),
            ).isEmpty(),
        )
    }
}
