package com.framenest.feature.listen_translate

import java.text.Normalizer

/**
 * Language-neutral exact local agreement. Ignores punctuation/space only; does not
 * fuzzy-match substitutions or manufacture stability after an early disagreement.
 * Returns an index into contextualTokens, not a character or word-alignment claim.
 */
internal fun listenStableTokenPrefix(
    shorterText: String,
    contextualTokens: List<String>,
): Int {
    fun normalized(value: String): IntArray = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .codePoints().filter { Character.isLetterOrDigit(it) }.toArray()
    val shorter = normalized(shorterText)
    val pieces = contextualTokens.map { normalized(it) }
    val longer = pieces.flatMap { it.asIterable() }
    var stable = 0
    while (stable < minOf(shorter.size, longer.size) && shorter[stable] == longer[stable]) stable++
    if (stable == 0) return 0
    var consumed = 0
    var exclusive = 0
    for (piece in pieces) {
        if (consumed + piece.size > stable) break
        consumed += piece.size
        exclusive++
    }
    return exclusive
}
