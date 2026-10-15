package com.framenest.feature.listen_translate

import org.junit.Assert.*
import org.junit.Test

class JapaneseOwnedPrefixPolicyTest {
    private fun token(text: String, start: Long) = JapaneseTimedToken(text, start)
    private fun pause(start: Long, end: Long) = JapaneseQuietRun(start, end)
    private fun select(tokens: List<JapaneseTimedToken>, quiet: List<JapaneseQuietRun> = emptyList(),
        start: Long = 0L, end: Long = 15_000L, eof: Boolean = false, limit: Long = minOf(end, start + 12_000L)) =
        JapaneseOwnedPrefixPolicy.select(tokens, quiet, start, end, eof, limit)

    // Short machine-transcribed excerpts/timings from Emoji.webm, Simpleshow Japan,
    // CC-BY-SA-4.0: https://commons.wikimedia.org/wiki/File:Emoji.webm .
    // Modified into synthetic regression inputs; not an independent golden transcript.
    @Test fun earliestQuestionWithoutPunctuationOwnsCompletePrefix() {
        val result = select(listOf(token("このイラストの意味わかり", 2_880L), token("ま", 4_560L),
            token(" ", 4_620L), token("す", 4_740L), token("か", 4_800L), token(" ", 4_980L),
            token("今", 6_300L), token("何語でしょうか", 9_300L), token("英語", 10_080L)),
            listOf(pause(4_960L, 5_860L), pause(9_560L, 9_740L)))!!
        assertEquals(5_410L, result.processedThroughMs)
        assertEquals(6, result.tokenEndExclusive)
        assertEquals("このイラストの意味わかりま すか", result.text)
        assertEquals(JapanesePrefixBoundaryReason.TerminalWithAcousticPause, result.reason)
    }

    @Test fun nonGridRestartFindsNextQuestionWithoutLosingChinaLater() {
        val result = select(listOf(token("今世界中で使われているのは何語でしょうか", 9_300L),
            token("英語", 10_080L), token("、", 10_500L), token("中国語", 11_820L)),
            listOf(pause(9_560L, 9_740L)), 5_410L, 20_410L)!!
        assertEquals(9_650L, result.processedThroughMs)
        assertFalse(result.text.contains("中国語"))
        assertEquals(1, result.tokenEndExclusive)
    }

    @Test fun terminalWithLongEstimatedGapIsExplicitlyWeakerEvidence() {
        val result = select(listOf(token("使われない言葉です", 16_200L), token(" ", 16_320L),
            token("正解は絵文字", 18_540L)), start = 9_650L, end = 24_650L)!!
        assertEquals(JapanesePrefixBoundaryReason.TerminalWithEstimatedTokenGap, result.reason)
        assertEquals(18_340L, result.processedThroughMs)
    }

    @Test fun realEstimatedGapBeforeCorrectAnswerUsesNextOnset() {
        val result = select(listOf(token("言葉です", 16_200L), token("正", 17_280L),
            token("解は絵文字", 18_540L)), start = 9_650L, end = 24_650L)!!
        assertEquals(17_080L, result.processedThroughMs)
    }

    @Test fun falsePeriodAfterDewaIsNotSemanticCompletion() {
        val result = select(listOf(token("それはビジネス文章では", 10_000L), token("。", 10_100L),
            token("使われない", 11_000L)), listOf(pause(10_300L, 10_600L)))!!
        assertEquals(JapanesePrefixBoundaryReason.ForcedTokenBoundary, result.reason)
        assertFalse(result.reason.name.startsWith("Terminal"))
    }

    @Test fun falsePeriodAfterYoungIsNotSemanticCompletion() {
        val result = select(listOf(token("特に若い", 10_000L), token("。", 10_100L),
            token("世代", 11_000L)))!!
        assertEquals(JapanesePrefixBoundaryReason.ForcedDelimiter, result.reason)
    }

    @Test fun connectiveCommaAndCaseParticleCannotBePauseFinals() {
        for (ending in listOf("使われ", "映画が", "文章では", "雨なので", "行くけど")) {
            val result = select(listOf(token(ending, 5_000L), token("、", 5_100L), token("続き", 6_000L)),
                listOf(pause(5_300L, 5_620L)))!!
            assertEquals(ending, JapanesePrefixBoundaryReason.ForcedTokenBoundary, result.reason)
        }
    }

    @Test fun rightContextContinuationBlocksTerminalInterpretation() {
        val result = select(listOf(token("そうです", 5_000L), token("が", 6_000L)),
            listOf(pause(5_300L, 5_620L)))!!
        assertFalse(result.reason.name.startsWith("Terminal"))
    }

    @Test fun nounAnswerIsNotGivenFalseSemanticGuaranteeOrDelayedPunctuationPts() {
        val result = select(listOf(token("正解は絵文字", 18_540L), token("！", 20_880L),
            token("絵文字は", 21_600L)), listOf(pause(19_180L, 19_420L)), 17_080L, 32_080L)!!
        assertEquals(19_300L, result.processedThroughMs)
        assertEquals("正解は絵文字", result.text)
        assertEquals(JapanesePrefixBoundaryReason.ForcedAcousticPause, result.reason)
    }

    @Test fun strongLaterTerminalCanKeepNounAnswerWithFollowingSentence() {
        val result = select(listOf(token("正解は絵文字", 18_540L), token("絵文字は若い世代に人気です", 27_960L),
            token("絵文字の映画", 28_740L)), listOf(pause(19_180L, 19_420L), pause(28_180L, 28_420L)),
            17_080L, 32_080L)!!
        assertEquals(28_300L, result.processedThroughMs)
        assertTrue(result.text.contains("正解は絵文字"))
        assertEquals(JapanesePrefixBoundaryReason.TerminalWithAcousticPause, result.reason)
    }

    @Test fun delimiterFallbackDoesNotSplitChinaAtTwelveSeconds() {
        val result = select(listOf(token("英語", 10_080L), token("、", 10_500L), token("スペイン語", 11_340L),
            token("、", 11_520L), token("中", 11_820L), token("国", 12_000L), token("語", 12_240L)))!!
        assertEquals(11_670L, result.processedThroughMs)
        assertEquals("英語、スペイン語、", result.text)
        assertEquals(4, result.tokenEndExclusive)
    }

    @Test fun forceTokenBoundaryKeepsWholeTokensAndLeavesTailUnowned() {
        val tokens = listOf(token("前半", 1_000L), token("一単語", 11_000L), token("残り", 12_500L))
        val result = select(tokens)!!
        assertEquals(11_000L, result.processedThroughMs)
        assertEquals("前半", result.text)
        assertEquals(listOf("一単語", "残り"), tokens.drop(result.tokenEndExclusive).map { it.text })
    }

    @Test fun guardRejectsEndOfDecodePunctuationAndNearEdgeCandidate() {
        val result = select(listOf(token("です", 13_900L), token("。", 14_100L), token("次", 14_900L)),
            listOf(pause(14_100L, 14_500L)))
        assertNull(result)
    }

    @Test fun futureCompletedBoundaryIsAHardOwnershipLimit() {
        val result = select(listOf(token("前", 1_000L), token("部分", 4_000L), token("です", 6_000L),
            token("次", 7_000L)), listOf(pause(6_200L, 6_500L)), limit = 5_000L)!!
        assertTrue(result.processedThroughMs <= 5_000L)
    }

    @Test fun explicitEofFlushesTailButMissingNextTokenDoesNotImplyEof() {
        val tokens = listOf(token("最後の言葉", 1_000L))
        assertNull(select(tokens, end = 3_000L))
        val final = select(tokens, end = 3_000L, eof = true)!!
        assertEquals(3_000L, final.processedThroughMs)
        assertEquals(JapanesePrefixBoundaryReason.EndOfMedia, final.reason)
    }

    @Test fun identicalDialogueAtDifferentTimesIsNotTextDeduplicated() {
        val result = select(listOf(token("はい", 1_000L), token("はい", 2_000L)), end = 3_000L, eof = true)!!
        assertEquals("はいはい", result.text)
    }

    @Test fun whitespaceDoesNotBecomeWordBoundaryAndBlankDoesNotComplete() {
        assertNull(select(listOf(token(" ", 1_000L))))
        assertNull(select(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPcmRead() { select(emptyList(), end = 15_001L) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfOrderTimestamps() { select(listOf(token("前", 2L), token("後", 1L))) }
    @Test fun stablePrefixLimitBlocksUnstableTerminalAndCoverage() {
        val tokens = listOf(token("前半", 1_000L), token("です", 5_000L), token("次", 6_000L))
        val result = JapaneseOwnedPrefixPolicy.select(tokens, listOf(pause(5_200L, 5_500L)),
            0L, 15_000L, stableTokenEndExclusive = 1)!!
        assertEquals("前半", result.text)
        assertEquals(1, result.tokenEndExclusive)
        assertEquals(5_000L, result.processedThroughMs)
        assertEquals(JapanesePrefixBoundaryReason.ForcedTokenBoundary, result.reason)
    }

    @Test fun zeroStablePrefixCannotCompleteEvenAtEof() {
        assertNull(JapaneseOwnedPrefixPolicy.select(listOf(token("未安定", 1_000L)), emptyList(),
            0L, 3_000L, endOfMedia = true, stableTokenEndExclusive = 0))
    }

    @Test fun stablePrefixCountsWhitespaceAndDoesNotAppendUnstablePunctuation() {
        val result = JapaneseOwnedPrefixPolicy.select(listOf(token("言葉です", 5_000L), token(" ", 5_100L),
            token("。", 5_900L), token("次", 6_500L)), listOf(pause(5_300L, 5_700L)),
            0L, 15_000L, stableTokenEndExclusive = 2)!!
        assertEquals(2, result.tokenEndExclusive)
        assertEquals("言葉です", result.text)
        assertEquals(5_500L, result.processedThroughMs)
    }

    @Test fun endOfMediaDoesNotFlushAnUnstableTail() {
        val result = JapaneseOwnedPrefixPolicy.select(listOf(token("前半", 1_000L), token("未安定", 2_000L)),
            emptyList(), 0L, 3_000L, endOfMedia = true, stableTokenEndExclusive = 1)!!
        assertEquals("前半", result.text)
        assertEquals(2_000L, result.processedThroughMs)
        assertNotEquals(JapanesePrefixBoundaryReason.EndOfMedia, result.reason)
    }

}
