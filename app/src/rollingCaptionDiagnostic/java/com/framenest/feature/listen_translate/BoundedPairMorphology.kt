package com.framenest.feature.listen_translate

import com.atilika.kuromoji.ipadic.Tokenizer
import com.framenest.feature.listen_translate.experimental.PairMorpheme

/** Test-only equivalent of the production morphology analysis view; output is never normalized. */
internal fun boundedPairMorphemes(tokenizer: Tokenizer, raw: String): List<PairMorpheme> {
    val offsets = ArrayList<Int>(raw.length)
    val analysis = buildString {
        raw.forEachIndexed { index, char -> if (!char.isWhitespace()) { append(char); offsets += index } }
    }
    if (analysis.isEmpty()) return emptyList()
    return tokenizer.tokenize(analysis).map { token ->
        val start = token.position
        val end = start + token.surface.length
        require(start >= 0 && end > start && end <= offsets.size)
        PairMorpheme(offsets[start], offsets[end - 1] + 1, token.surface, token.isKnown)
    }
}
