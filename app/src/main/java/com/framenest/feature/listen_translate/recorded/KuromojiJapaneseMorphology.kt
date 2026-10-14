package com.framenest.feature.listen_translate.recorded

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * NORMAL mode preserves compound words. Never use SEARCH/EXTENDED for source-unit boundaries.
 * ASR-inserted whitespace is removed only in the analysis view; output retains original Unicode.
 * Reuse one adapter, construct off the UI thread. Dictionary loading has a measurable cost.
 */
internal class KuromojiJapaneseMorphology : JapaneseMorphology {
    private val tokenizer by lazy { Tokenizer() }
    override fun analyze(rawText: String): List<JapaneseMorpheme> {
        val rawOffsets = ArrayList<Int>(rawText.length)
        val analysis = buildString {
            rawText.forEachIndexed { index, c ->
                if (!c.isWhitespace()) { append(c); rawOffsets += index }
            }
        }
        if (analysis.isEmpty()) return emptyList()
        return tokenizer.tokenize(analysis).map { t ->
            val begin = t.position
            val end = begin + t.surface.length
            require(begin >= 0 && end <= rawOffsets.size && end > begin)
            JapaneseMorpheme(rawOffsets[begin], rawOffsets[end - 1] + 1, t.surface,
                t.partOfSpeechLevel1, t.partOfSpeechLevel2, t.conjugationForm, t.isKnown)
        }
    }
}
