package com.framenest.feature.listen_translate.recorded

/** Raw UTF-16 offsets into the unchanged input, even when analysis omits ASR spacing. */
internal data class JapaneseMorpheme(
    val rawStart: Int, val rawEnd: Int, val surface: String,
    val partOfSpeech: String, val detail: String, val conjugationForm: String, val known: Boolean,
)
internal fun interface JapaneseMorphology { fun analyze(rawText: String): List<JapaneseMorpheme> }
internal data class RecordedBoundary(val rawEnd: Int, val mediaEndMs: Long, val reason: RecordedSourceReason)

/** A conservative linguistic/acoustic heuristic; never a guarantee of ASR/translation accuracy. */
internal class JapaneseSourceBoundary(private val morphology: JapaneseMorphology) {
    fun first(
        text: String, anchors: List<RecordedCharacterAnchor>, quiet: List<RecordedQuietSpan>,
        decodedThroughMs: Long, eof: Boolean = false,
    ): RecordedBoundary? {
        if (text.isBlank()) return null
        val tokens = morphology.analyze(text)
        require(tokens.zipWithNext().all { (a, b) -> a.rawEnd <= b.rawStart })
        require(tokens.all { it.rawStart >= 0 && it.rawEnd <= text.length && it.rawStart < it.rawEnd })
        fun anchorAt(offset: Int) = anchors.firstOrNull { offset >= it.unitRawStart && offset < it.unitRawEnd }
        for (i in tokens.indices) {
            if (!terminal(tokens, i)) continue
            var endIndex = i
            // Consume only sentence-closing punctuation, never comma/colon/list separators.
            while (endIndex + 1 < tokens.size && tokens[endIndex + 1].surface.all { it in "。！？!?」』）)\"”’" }) endIndex++
            val right = tokens.getOrNull(endIndex + 1)
            if (right != null && dependent(right)) continue
            val end = tokens[endIndex].rawEnd
            val terminalTime = anchorAt(tokens[i].rawEnd - 1)?.estimatedTokenStartMs ?: continue
            val rightTime = right?.let { anchorAt(it.rawStart)?.estimatedTokenStartMs }
            // Silence near a terminal is useful only when there is actual right context.
            if (rightTime != null && rightTime > terminalTime) {
                val run = quiet.filter {
                    it.endMs - it.startMs >= 160 && it.startMs >= terminalTime &&
                        it.endMs <= rightTime + 250 && it.startMs - terminalTime <= 1_500 &&
                        rightTime - it.endMs <= 1_000 && decodedThroughMs - it.endMs >= 750
                }.minByOrNull { kotlin.math.abs(rightTime - it.endMs) }
                if (run != null) return RecordedBoundary(end, (run.startMs + run.endMs) / 2,
                    if (endIndex > i) RecordedSourceReason.PUNCTUATION_AND_PCM_QUIET
                    else RecordedSourceReason.MORPHOLOGY_AND_PCM_QUIET)
            }
            // A trailing measured quiet run is right-context PCM too. Do not wait forever
            // for another lexical token after an isolated complete utterance.
            if (right == null) {
                val trailing = quiet.firstOrNull {
                    it.startMs >= terminalTime && it.startMs - terminalTime <= 1_500 &&
                        it.endMs >= decodedThroughMs - 20 && it.endMs <= decodedThroughMs &&
                        it.endMs - it.startMs >= 750
                }
                if (trailing != null) return RecordedBoundary(text.length, trailing.startMs + 80,
                    if (endIndex > i) RecordedSourceReason.PUNCTUATION_AND_PCM_QUIET
                    else RecordedSourceReason.MORPHOLOGY_AND_PCM_QUIET)
            }
            // Reliable physical EOF plus complete morphology. EOF alone never completes a fragment.
            if (eof && right == null && terminalTime <= decodedThroughMs) {
                return RecordedBoundary(text.length, decodedThroughMs, RecordedSourceReason.MORPHOLOGY_AT_EOF)
            }
        }
        return null
    }

    private fun terminal(tokens: List<JapaneseMorpheme>, index: Int): Boolean {
        var i = index
        val last = tokens[i]
        if (!last.known || last.partOfSpeech == "記号") return false
        if (last.partOfSpeech == "助詞") {
            if (!last.detail.contains("終助詞")) return false
            while (i >= 0 && tokens[i].partOfSpeech == "助詞" && tokens[i].detail.contains("終助詞")) i--
            if (i < 0) return false
        }
        val predicate = tokens[i]
        return predicate.known && predicate.partOfSpeech in setOf("動詞", "形容詞", "助動詞") &&
            (predicate.conjugationForm == "基本形" || predicate.conjugationForm.startsWith("命令"))
    }

    private fun dependent(next: JapaneseMorpheme): Boolean =
        next.partOfSpeech in setOf("助詞", "助動詞") || next.detail in setOf("非自立", "接尾") ||
            next.surface.any { it in "、，,:：;；" }
}
