package com.framenest.experiment.localagreement

import java.util.Collections

/**
 * Separate comparison-only mapping for a future experiment. This class is NOT used
 * by BoundedLocalAgreement, whose existing exact comparison remains unchanged.
 *
 * Only Unicode whitespace/space characters and three commas are omitted from the
 * comparison key. Raw text remains intact. Nothing here is normalized, published,
 * or treated as stable/final speech. In particular an empty key is never agreement
 * evidence. Code-point alignment is not a word, grapheme, or utterance boundary.
 */
class ReversibleAgreementKey private constructor(
    val rawText: String,
    val comparisonKey: String,
    val spans: List<CodePointSpan>,
) {
    enum class Error { INVALID_UTF16, TEXT_TOO_LONG }

    data class CreationResult(val mapping: ReversibleAgreementKey?, val error: Error?)

    /** End offsets are exclusive; every span contains exactly one Unicode scalar. */
    data class CodePointSpan(
        val codePoint: Int,
        val keyStartUtf16: Int,
        val keyEndUtf16: Int,
        val rawStartUtf16: Int,
        val rawEndUtf16: Int,
    )

    /**
     * Two code-point-aligned choices around ignored raw text at a key boundary.
     * They may be equal. This class deliberately does not assign that gap to either
     * side or authorize either offset as an audio/publication/final-text boundary.
     */
    data class RawBoundary(
        val beforeIgnoredTextUtf16: Int,
        val afterIgnoredTextUtf16: Int,
    )

    val retainedCodePointCount: Int get() = spans.size
    val hasComparisonEvidence: Boolean get() = spans.isNotEmpty()

    /** Map a prefix length measured in KEY CODE POINTS, not UTF-16 code units. */
    fun rawBoundaryForPrefix(keyPrefixCodePoints: Int): RawBoundary {
        require(keyPrefixCodePoints in 0..spans.size) { "Key prefix count is out of range" }
        return RawBoundary(
            if (keyPrefixCodePoints == 0) 0 else spans[keyPrefixCodePoints - 1].rawEndUtf16,
            if (keyPrefixCodePoints == spans.size) rawText.length else spans[keyPrefixCodePoints].rawStartUtf16,
        )
    }

    companion object {
        const val MAX_RAW_CODE_POINTS = 4096

        /** Stateless and atomic: rejected input produces no partial mapping. */
        fun create(rawText: String): CreationResult {
            if (rawText.length > MAX_RAW_CODE_POINTS * 2) return rejected(Error.TEXT_TOO_LONG)
            val key = StringBuilder()
            val spans = ArrayList<CodePointSpan>()
            var rawOffset = 0
            var rawCodePoints = 0
            while (rawOffset < rawText.length) {
                val c = rawText[rawOffset]
                val width = if (Character.isHighSurrogate(c)) {
                    if (rawOffset + 1 == rawText.length || !Character.isLowSurrogate(rawText[rawOffset + 1])) {
                        return rejected(Error.INVALID_UTF16)
                    }
                    2
                } else {
                    if (Character.isLowSurrogate(c)) return rejected(Error.INVALID_UTF16)
                    1
                }
                if (++rawCodePoints > MAX_RAW_CODE_POINTS) return rejected(Error.TEXT_TOO_LONG)
                val codePoint = rawText.codePointAt(rawOffset)
                if (!ignoredForComparison(codePoint)) {
                    val keyStart = key.length
                    // Append the original UTF-16 slice rather than rewriting the scalar.
                    key.append(rawText, rawOffset, rawOffset + width)
                    spans.add(CodePointSpan(codePoint, keyStart, key.length, rawOffset, rawOffset + width))
                }
                rawOffset += width
            }
            return CreationResult(ReversibleAgreementKey(rawText, key.toString(),
                Collections.unmodifiableList(spans)), null)
        }

        private fun ignoredForComparison(codePoint: Int): Boolean =
            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
                codePoint == 0x002C || codePoint == 0xFF0C || codePoint == 0x3001

        private fun rejected(error: Error) = CreationResult(null, error)
    }
}
