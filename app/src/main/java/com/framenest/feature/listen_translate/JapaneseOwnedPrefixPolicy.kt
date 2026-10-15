package com.framenest.feature.listen_translate

/** Estimated token starts, in absolute media time; not phonetic word boundaries. */
internal data class JapaneseTimedToken(val text: String, val startMs: Long)

/** Actual PCM-derived quiet run. A token timestamp gap is not an acoustic pause. */
internal data class JapaneseQuietRun(val startMs: Long, val endMs: Long)

internal enum class JapanesePrefixBoundaryReason {
    TerminalWithAcousticPause,
    TerminalWithEstimatedTokenGap,
    ForcedAcousticPause,
    ForcedDelimiter,
    ForcedTokenBoundary,
    EndOfMedia,
}

internal data class JapaneseOwnedPrefix(
    val text: String,
    val tokenEndExclusive: Int,
    val processedThroughMs: Long,
    val reason: JapanesePrefixBoundaryReason,
)

/**
 * Unconnected Japanese experiment, NOT a general sentence parser. Selects one owned
 * prefix from at most 15 s PCM, preferring terminal forms within 12 s. Punctuation
 * is never proof of completion. The caller resumes at processedThroughMs, not 12 s.
 * Forced boundaries preserve token ownership, not guaranteed Japanese word accuracy.
 * stableTokenEndExclusive optionally restricts ownership to a caller-verified common
 * prefix of two recognitions (count in THIS input, including spaces/punctuation).
 * Omission means no stability check was supplied, not that recognition is stable.
 */
internal object JapaneseOwnedPrefixPolicy {
    const val MAX_READ_MS = 15_000L
    const val MAX_OWNED_MS = 12_000L
    const val RIGHT_GUARD_MS = 750L
    private const val MIN_QUIET_MS = 160L
    private const val TOKEN_GAP_MS = 700L
    private const val LAST_TOKEN_TAIL_MS = 120L
    private val terminal = Regex("(?:でしょうか|だろうか|でしたか|ましたか|ますか|ですか|ませんか|でした|ました|ません|でしょう|だろう|です|ます)$")
    private val connective = Regex("(?:では|には|とは|ので|のに|けれども|けれど|けど|ながら|ため|ほど|そして|しかし|が|は|を|に|で|と|も|の|て|れば)$")
    private val rightContinuation = Regex("^(?:けれど|けど|ので|のに|から|が|し|と)")
    private fun lexical(text: String): String = text.filter { !it.isWhitespace() && it !in "▁。、，,.!?！？「」『』（）()\"'" }

    fun select(
        tokens: List<JapaneseTimedToken>,
        quietRuns: List<JapaneseQuietRun>,
        startMs: Long,
        decodedThroughMs: Long,
        endOfMedia: Boolean = false,
        ownedLimitMs: Long = startMs + minOf(decodedThroughMs - startMs, MAX_OWNED_MS),
        stableTokenEndExclusive: Int = tokens.size,
    ): JapaneseOwnedPrefix? {
        require(startMs >= 0L && decodedThroughMs > startMs)
        require(decodedThroughMs - startMs <= MAX_READ_MS)
        require(ownedLimitMs > startMs && ownedLimitMs <= decodedThroughMs)
        require(stableTokenEndExclusive in 0..tokens.size)
        if (stableTokenEndExclusive == 0) return null
        require(tokens.all { it.startMs in startMs until decodedThroughMs })
        require(tokens.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        require(quietRuns.all { it.startMs >= startMs && it.endMs > it.startMs && it.endMs <= decodedThroughMs })
        val words = tokens.withIndex().filter { lexical(it.value.text).isNotEmpty() }
        if (words.isEmpty()) return null // Silence completion is a separate, audio-backed decision.
        val cap = minOf(ownedLimitMs, minOf(decodedThroughMs - startMs, MAX_OWNED_MS) + startMs)
        val stableThroughMs = tokens.getOrNull(stableTokenEndExclusive)?.startMs ?: decodedThroughMs
        val guardedCap = minOf(cap, decodedThroughMs - RIGHT_GUARD_MS, stableThroughMs)
        data class Candidate(val last: Int, val boundary: Long, val acoustic: Boolean, val terminal: Boolean, val commaContinuation: Boolean = false)
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until words.lastIndex) {
            val last = words[i]
            val next = words[i + 1]
            if (last.index >= stableTokenEndExclusive || next.value.startMs <= last.value.startMs) continue
            val prefix = tokens.take(last.index + 1).joinToString("") { lexical(it.text) }
            val right = words.drop(i + 1).take(5).joinToString("") { lexical(it.value.text) }
            val endsTerminal = terminal.containsMatchIn(prefix) && !connective.containsMatchIn(prefix) &&
                !rightContinuation.containsMatchIn(right)
            val pause = quietRuns.filter {
                it.endMs - it.startMs >= MIN_QUIET_MS &&
                    it.startMs >= last.value.startMs + LAST_TOKEN_TAIL_MS &&
                    it.endMs <= next.value.startMs &&
                    it.startMs + (it.endMs - it.startMs) / 2L <= guardedCap
            }.minByOrNull { it.startMs }
            if (pause != null) {
                val cut = pause.startMs + (pause.endMs - pause.startMs) / 2L
                candidates += Candidate(last.index, cut, true, endsTerminal,
                    tokens.subList(last.index + 1, next.index).any { it.text.any { c -> c in "、，," } })
            } else if (endsTerminal && next.value.startMs - last.value.startMs >= TOKEN_GAP_MS) {
                // Weaker evidence: leave 200 ms before the following estimated onset.
                val cut = next.value.startMs - 200L
                if (cut <= guardedCap && cut > last.value.startMs + LAST_TOKEN_TAIL_MS) {
                    candidates += Candidate(last.index, cut, false, true)
                }
            }
        }
        fun result(last: Int, boundary: Long, reason: JapanesePrefixBoundaryReason): JapaneseOwnedPrefix {
            // Include only punctuation/spaces physically before the owned boundary. A !
            // hallucinated at the decode edge cannot drag an earlier noun's PTS forward.
            var exclusive = last + 1
            while (exclusive < stableTokenEndExclusive && lexical(tokens[exclusive].text).isEmpty() &&
                tokens[exclusive].startMs < boundary) exclusive++
            return JapaneseOwnedPrefix(tokens.take(exclusive).joinToString("") { it.text }.replace('▁', ' ').trim(), exclusive, boundary, reason)
        }
        candidates.filter { it.terminal }.minByOrNull { it.boundary }?.let {
            return result(it.last, it.boundary, if (it.acoustic) JapanesePrefixBoundaryReason.TerminalWithAcousticPause
                else JapanesePrefixBoundaryReason.TerminalWithEstimatedTokenGap)
        }
        // EOF flush is explicit, never inferred from an absent next token. Earlier complete
        // phrases still win. A >12 s remainder is split first to retain the ownership bound.
        if (endOfMedia && decodedThroughMs <= cap && stableTokenEndExclusive == tokens.size) {
            return JapaneseOwnedPrefix(tokens.joinToString("") { it.text }.replace('▁', ' ').trim(),
                tokens.size, decodedThroughMs, JapanesePrefixBoundaryReason.EndOfMedia)
        }
        // No strong terminal: prefer a real pause, but label it forced. A noun answer may
        // remain combined with the next sentence. Reject known connective fragments.
        candidates.filter { it.acoustic && !it.commaContinuation && !connective.containsMatchIn(
            tokens.take(it.last + 1).joinToString("") { token -> lexical(token.text) }) }
            .maxByOrNull { it.boundary }?.let {
                return result(it.last, it.boundary, JapanesePrefixBoundaryReason.ForcedAcousticPause)
            }
        // Prefer a delimiter before the cap over slicing an unsegmented character run.
        // Spaces are NOT word boundaries: SenseVoice emits spaces inside Japanese words.
        for (i in words.lastIndex - 1 downTo 0) {
            val last = words[i]
            val next = words[i + 1]
            if (last.index >= stableTokenEndExclusive) continue
            val between = tokens.subList(last.index + 1, next.index)
            if (between.none { it.text.any { char -> char in "。、，,.!?！？" } }) continue
            val prefix = tokens.take(last.index + 1).joinToString("") { lexical(it.text) }
            if (connective.containsMatchIn(prefix)) continue
            // A comma after a hiragana-ending phrase is weak continuation evidence.
            // No fixture vocabulary or inferred dictionary word boundaries are used.
            if (between.any { it.text.any { c -> c in "、，," } } &&
                prefix.lastOrNull() in 'ぁ'..'ゖ') continue
            val delimiterEnd = between.maxOfOrNull { it.startMs } ?: last.value.startMs
            val cut = delimiterEnd + (next.value.startMs - delimiterEnd) / 2L
            if (cut > startMs && cut <= guardedCap && cut > delimiterEnd) {
                return result(last.index, cut, JapanesePrefixBoundaryReason.ForcedDelimiter)
            }
        }
        // Last resort: advance to a token start, excluding that token from the prefix.
        // No tokens are silently dropped by this selector. Recognition after restart can
        // still differ: timestamp/word-boundary accuracy must be measured, not promised.
        for (i in words.lastIndex downTo 1) {
            val next = words[i]
            if (words[i - 1].index < stableTokenEndExclusive &&
                next.value.startMs > startMs && next.value.startMs <= guardedCap &&
                words[i - 1].value.startMs < next.value.startMs) {
                return result(words[i - 1].index, next.value.startMs, JapanesePrefixBoundaryReason.ForcedTokenBoundary)
            }
        }
        return null // Insufficient safe progress; caller must not certify the unread tail.
    }
}
