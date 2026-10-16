package com.framenest.experiment.localagreement

import org.junit.Assert.*
import org.junit.Test

class ReversibleAgreementKeyTest {
    private fun mapping(raw: String): ReversibleAgreementKey {
        val result = ReversibleAgreementKey.create(raw)
        assertNull(result.error)
        return checkNotNull(result.mapping)
    }

    private fun assertLosslessSpans(m: ReversibleAgreementKey) {
        assertEquals(m.comparisonKey.codePointCount(0, m.comparisonKey.length), m.spans.size)
        var previousRawEnd = 0
        var previousKeyEnd = 0
        val fromRaw = StringBuilder()
        for (span in m.spans) {
            assertTrue(span.rawStartUtf16 >= previousRawEnd)
            assertEquals(previousKeyEnd, span.keyStartUtf16)
            val rawSlice = m.rawText.substring(span.rawStartUtf16, span.rawEndUtf16)
            val keySlice = m.comparisonKey.substring(span.keyStartUtf16, span.keyEndUtf16)
            assertEquals(rawSlice, keySlice)
            assertEquals(1, rawSlice.codePointCount(0, rawSlice.length))
            assertEquals(span.codePoint, rawSlice.codePointAt(0))
            assertEquals(Character.charCount(span.codePoint), rawSlice.length)
            fromRaw.append(rawSlice)
            previousRawEnd = span.rawEndUtf16
            previousKeyEnd = span.keyEndUtf16
        }
        assertEquals(m.comparisonKey, fromRaw.toString())
        for (prefixLength in 0..m.retainedCodePointCount) {
            val boundary = m.rawBoundaryForPrefix(prefixLength)
            assertTrue(boundary.beforeIgnoredTextUtf16 <= boundary.afterIgnoredTextUtf16)
            val expected = m.comparisonKey.substring(0, m.comparisonKey.offsetByCodePoints(0, prefixLength))
            for (offset in listOf(boundary.beforeIgnoredTextUtf16, boundary.afterIgnoredTextUtf16)) {
                assertEquals(expected, mapping(m.rawText.substring(0, offset)).comparisonKey)
                if (offset > 0) assertFalse(Character.isHighSurrogate(m.rawText[offset - 1]))
                if (offset < m.rawText.length) assertFalse(Character.isLowSurrogate(m.rawText[offset]))
            }
        }
    }

    @Test fun cjkWhitespaceAndOnlyTheThreeCommasAreIgnoredInTheKey() {
        val raw = " 日本語、字幕，確認, です。 "
        val m = mapping(raw)
        assertEquals(raw, m.rawText)
        assertEquals("日本語字幕確認です。", m.comparisonKey)
        assertTrue(m.hasComparisonEvidence)
        assertLosslessSpans(m)
    }

    @Test fun allOtherPunctuationIncludingQuestionsExclamationsAndStopsIsKept() {
        val raw = "?!！？.。．:：;；・…—-()「」“”'’،﹐﹑"
        val m = mapping(raw)
        assertEquals(raw, m.comparisonKey)
        assertLosslessSpans(m)
        assertNotEquals(mapping("本当?").comparisonKey, mapping("本当!").comparisonKey)
        assertNotEquals(mapping("終わり。").comparisonKey, mapping("終わり").comparisonKey)
    }

    @Test fun unicodeWhitespaceAndSpaceCharUnionIsUsed() {
        val raw = "\t\n\r\u000B\u000C\u001C\u001D\u001E\u001F" +
            " \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007" +
            "\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"
        val m = mapping(raw)
        assertEquals("", m.comparisonKey)
        assertEquals(raw, m.rawText)
        assertFalse(m.hasComparisonEvidence)
        assertTrue(m.spans.isEmpty())
    }

    @Test fun zeroWidthFormatCharactersAndNonWhitespaceControlsAreNotDeleted() {
        val raw = "\u0000\u0085\u200B\u200C\u200D\u2060\uFEFF"
        assertEquals(raw, mapping(raw).comparisonKey)
    }

    @Test fun composedDecomposedFullwidthAndCompatibilityFormsRemainDistinct() {
        val raw = "e\u0301,é，Ａ A、① 1"
        val m = mapping(raw)
        assertEquals("e\u0301éＡA①1", m.comparisonKey)
        assertNotEquals(mapping("e\u0301").comparisonKey, mapping("é").comparisonKey)
        assertNotEquals(mapping("Ａ").comparisonKey, mapping("A").comparisonKey)
        assertNotEquals(mapping("①").comparisonKey, mapping("1").comparisonKey)
        assertLosslessSpans(m)
    }

    @Test fun combiningMarksHaveSeparateCodePointOffsetsWithoutClaimingGraphemeFinality() {
        val m = mapping(" e\u0301, ")
        assertEquals(2, m.retainedCodePointCount)
        assertEquals(1, m.spans[0].rawStartUtf16)
        assertEquals(2, m.spans[0].rawEndUtf16)
        assertEquals(2, m.spans[1].rawStartUtf16)
        assertEquals(3, m.spans[1].rawEndUtf16)
        assertEquals(ReversibleAgreementKey.RawBoundary(2, 2), m.rawBoundaryForPrefix(1))
        assertLosslessSpans(m)
    }

    @Test fun emojiAndSupplementaryCjkPreserveWholeSurrogatePairsAndExactOffsets() {
        val raw = " ,😀、𠮷 ，e\u0301!\t"
        val m = mapping(raw)
        assertEquals("😀𠮷e\u0301!", m.comparisonKey)
        assertEquals(listOf(2, 5, 9, 10, 11), m.spans.map { it.rawStartUtf16 })
        assertEquals(listOf(4, 7, 10, 11, 12), m.spans.map { it.rawEndUtf16 })
        assertEquals(listOf(0, 2, 4, 5, 6), m.spans.map { it.keyStartUtf16 })
        assertEquals(listOf(2, 4, 5, 6, 7), m.spans.map { it.keyEndUtf16 })
        assertLosslessSpans(m)
    }

    @Test fun zwjAndVariationSelectorsRemainPartOfTheKey() {
        val raw = " 👩‍💻, ❤️，🇯🇵 "
        val m = mapping(raw)
        assertEquals("👩‍💻❤️🇯🇵", m.comparisonKey)
        assertLosslessSpans(m)
    }

    @Test fun prefixBoundaryExposesBothSidesOfIgnoredGapWithoutAssigningOwnership() {
        val m = mapping(" ,A， B、 ")
        assertEquals("AB", m.comparisonKey)
        assertEquals(ReversibleAgreementKey.RawBoundary(0, 2), m.rawBoundaryForPrefix(0))
        assertEquals(ReversibleAgreementKey.RawBoundary(3, 5), m.rawBoundaryForPrefix(1))
        assertEquals(ReversibleAgreementKey.RawBoundary(6, 8), m.rawBoundaryForPrefix(2))
        assertLosslessSpans(m)
    }

    @Test fun allIgnoredInputCannotBeAgreementEvidenceAndRawTextIsNotLost() {
        for (raw in listOf("", " ,\t，\n、\u00A0\u3000")) {
            val m = mapping(raw)
            assertEquals(raw, m.rawText)
            assertEquals("", m.comparisonKey)
            assertFalse(m.hasComparisonEvidence)
            assertTrue(m.spans.isEmpty())
            assertEquals(ReversibleAgreementKey.RawBoundary(0, raw.length), m.rawBoundaryForPrefix(0))
        }
    }

    @Test fun invalidPrefixCountsAreRejected() {
        val m = mapping("😀A")
        for (count in listOf(-1, 3, Int.MAX_VALUE)) {
            try {
                m.rawBoundaryForPrefix(count)
                fail("Expected rejection for $count")
            } catch (_: IllegalArgumentException) {
                // Expected: the API consumes code-point counts, not arbitrary offsets.
            }
        }
    }

    @Test fun everyMalformedSurrogateShapeIsRejectedWithoutAPartialMapping() {
        for (raw in listOf("\uD83D", "\uDE00", "A\uD83DB", "A\uDE00B", "\uD83D\uD83D", "\uDE00\uD83D")) {
            val result = ReversibleAgreementKey.create(raw)
            assertEquals(ReversibleAgreementKey.Error.INVALID_UTF16, result.error)
            assertNull(result.mapping)
        }
        assertEquals("valid", mapping("valid").rawText)
    }

    @Test fun rawLimitIncludesIgnoredCharactersAndRejectsRatherThanTruncates() {
        for (raw in listOf("日".repeat(4097), "😀".repeat(4097), " ".repeat(4097), ",".repeat(4097))) {
            val result = ReversibleAgreementKey.create(raw)
            assertEquals(ReversibleAgreementKey.Error.TEXT_TOO_LONG, result.error)
            assertNull(result.mapping)
        }
    }

    @Test fun maximumBmpAndSupplementaryRawLengthsAreAcceptedExactly() {
        for (raw in listOf("日".repeat(4096), "😀".repeat(4096))) {
            val m = mapping(raw)
            assertEquals(raw, m.rawText)
            assertEquals(raw, m.comparisonKey)
            assertEquals(4096, m.retainedCodePointCount)
            assertEquals(raw.length, m.spans.last().rawEndUtf16)
            assertEquals(ReversibleAgreementKey.RawBoundary(raw.length, raw.length), m.rawBoundaryForPrefix(4096))
        }
        val ignored = mapping(" ".repeat(4096))
        assertFalse(ignored.hasComparisonEvidence)
        assertEquals(4096, ignored.rawText.length)
    }

    @Test fun returnedSpanListCannotBeMutated() {
        val m = mapping("A B")
        try {
            (m.spans as MutableList).clear()
            fail("Expected immutable mapping")
        } catch (_: UnsupportedOperationException) {
            assertEquals(2, m.spans.size)
            assertEquals("AB", m.comparisonKey)
            assertEquals("A B", m.rawText)
        }
    }

    @Test fun commaAndWhitespaceVariantsShareOnlyTheComparisonKeyNeverRawText() {
        val first = mapping("日本、語です。")
        val second = mapping("日 本語，です。 ")
        assertEquals(first.comparisonKey, second.comparisonKey)
        assertNotEquals(first.rawText, second.rawText)
        assertEquals("日本、語です。", first.rawText)
        assertEquals("日 本語，です。 ", second.rawText)
        assertLosslessSpans(first)
        assertLosslessSpans(second)
    }
}
