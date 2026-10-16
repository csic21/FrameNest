package com.framenest.feature.listen_translate

import com.framenest.experiment.localagreement.ReversibleAgreementKey

/**
 * Test-only MT request experiment. Never controls ASR ownership, audio cuts, a
 * renderer, or final coverage. Its two retained origins mirror the rolling
 * controller's old/candidate contexts; historical rows belong to the caller.
 */
internal class RollingCaptionRequestPlanner {
    enum class Type { STABLE_SENTENCE_CANDIDATE, REVISABLE_PREVIEW }
    data class Hypothesis(val requestId: Long, val startSample: Long, val endSample: Long,
        val raw: String, val recovery: Boolean = false, val terminal: Boolean = false)
    data class UnitRequest(val id: Long, val type: Type, val text: String,
        val hypothesisRequestId: Long, val startSample: Long, val endSample: Long,
        val rawStart: Int, val rawEnd: Int, val stableKeyPrefixCodePoints: Int,
        val stableLexicalRightContext: Int, val reason: String,
        val supersedes: Long? = null, val correction: Boolean = false,
        val latencySample: Boolean = true, val leftEdgeUnverified: Boolean = true) {
        val finalCoverage: Boolean get() = false
    }
    data class Revision(val unitId: Long, val reason: String)
    data class Decision(val requests: List<UnitRequest>, val revisions: List<Revision>,
        val stableKeyPrefixCodePoints: Int, val deadlineSample: Long?, val nextDeadlineSample: Long,
        val deadlineDisposition: String, val duplicate: Boolean = false)
    private data class Candidate(val id: Long, val prefixKey: String, val rawPrefix: String)
    private data class Origin(val mapping: ReversibleAgreementKey, val candidates: List<Candidate>)
    private val origins = linkedMapOf<Long, Origin>()
    private var nextId = 1L
    private var lastRequestId = 0L
    private var lastHypothesis: Hypothesis? = null
    private var observedEnd = 0L
    private var nextDeadline = PREVIEW_STEP
    private var previewId: Long? = null
    private var previewEnd: Long? = null
    private var correctedEnd: Long? = null
    private var eofPreviewIssued = false

    fun observe(h: Hypothesis): Decision {
        if (h == lastHypothesis) return Decision(emptyList(), emptyList(), 0, null, nextDeadline,
            "DUPLICATE_HYPOTHESIS_IGNORED", duplicate = true)
        require(h.requestId > lastRequestId)
        require(h.startSample >= 0 && h.endSample > h.startSample && h.endSample >= observedEnd)
        require(h.endSample - h.startSample <= MAX_HYPOTHESIS)
        val map = requireNotNull(ReversibleAgreementKey.create(h.raw).mapping)
        val previous = origins.remove(h.startSample)
        val stable = previous?.let { commonPrefix(it.mapping, map) } ?: 0
        val revisions = mutableListOf<Revision>()
        val live = previous?.candidates.orEmpty().filter { candidate ->
            when {
                !map.comparisonKey.startsWith(candidate.prefixKey) -> {
                    revisions += Revision(candidate.id, "LATER_HYPOTHESIS_RETRACTED_SENTENCE_PREFIX")
                    false
                }
                !h.raw.startsWith(candidate.rawPrefix) -> {
                    // Comparison equivalence does not make punctuation/spacing in the raw
                    // source immutable. Keep a separate revision record and re-request it.
                    revisions += Revision(candidate.id, "RAW_PRESENTATION_CHANGED_KEY_PREFIX_PRESERVED")
                    false
                }
                else -> true
            }
        }.toMutableList()
        val units = mutableListOf<UnitRequest>()
        var sentenceStartKey = 0
        for (i in 0 until stable) {
            if (map.spans[i].codePoint !in TERMINALS) continue
            if (i + 1 < stable && map.spans[i + 1].codePoint in TERMINALS) continue
            if (map.spans.subList(sentenceStartKey, i).none { Character.isLetterOrDigit(it.codePoint) }) continue
            val lexicalRight = map.spans.subList(i + 1, stable).count { Character.isLetterOrDigit(it.codePoint) }
            if (lexicalRight == 0) continue
            val endKey = i + 1
            val startRaw = map.rawBoundaryForPrefix(sentenceStartKey).beforeIgnoredTextUtf16
            val endRaw = map.rawBoundaryForPrefix(endKey).beforeIgnoredTextUtf16
            val prefix = map.comparisonKey.substring(0, map.spans[i].keyEndUtf16)
            if (live.none { it.prefixKey == prefix }) {
                val id = nextId++
                units += UnitRequest(id, Type.STABLE_SENTENCE_CANDIDATE, h.raw.substring(startRaw, endRaw),
                    h.requestId, h.startSample, h.endSample, startRaw, endRaw, stable, lexicalRight,
                    "STABLE_TERMINAL_WITH_STABLE_LEXICAL_RIGHT_CONTEXT",
                    leftEdgeUnverified = sentenceStartKey == 0 && h.startSample != 0L)
                live += Candidate(id, prefix, h.raw.substring(0, endRaw))
            }
            sentenceStartKey = endKey
        }
        origins[h.startSample] = Origin(map, live)
        while (origins.size > 2) origins.remove(origins.keys.first())

        val deadline = nextDeadline.takeIf { h.endSample >= it }
        if (deadline != null) while (nextDeadline <= h.endSample) nextDeadline += PREVIEW_STEP
        val correction = h.recovery && previewEnd == h.endSample && correctedEnd != h.endSample
        val eof = h.terminal && !eofPreviewIssued
        val due = deadline != null && units.isEmpty()
        val shouldPreview = h.raw.isNotBlank() && (correction || eof || due)
        if (shouldPreview) {
            val id = nextId++
            units += UnitRequest(id, Type.REVISABLE_PREVIEW, h.raw, h.requestId,
                h.startSample, h.endSample, 0, h.raw.length, stable, 0,
                when { correction -> "SAME_END_RECOVERY_CORRECTION"; eof -> "EOF_COMPLETE_HYPOTHESIS"; else -> "CLIP_DEADLINE_COMPLETE_HYPOTHESIS" },
                supersedes = previewId, correction = correction, latencySample = !correction,
                leftEdgeUnverified = h.startSample != 0L)
            previewId = id
            previewEnd = h.endSample
            if (correction) correctedEnd = h.endSample
        }
        if (h.terminal) eofPreviewIssued = true
        lastRequestId = h.requestId
        lastHypothesis = h
        observedEnd = h.endSample
        return Decision(units, revisions, stable, deadline, nextDeadline, when {
            correction && shouldPreview -> "CORRECTION_NOT_NEW_MEDIA_LATENCY_SAMPLE"
            eof && shouldPreview -> "EOF_PREVIEW"
            deadline == null -> "NOT_DUE"
            h.raw.isBlank() -> "EMPTY_HYPOTHESIS_NO_MT"
            units.any { it.type == Type.STABLE_SENTENCE_CANDIDATE } -> "STABLE_SENTENCE_AVAILABLE_PREVIEW_NOT_REQUIRED"
            else -> "PREVIEW_REQUESTED"
        })
    }

    companion object {
        const val PREVIEW_STEP = 6L * 16_000L
        const val MAX_HYPOTHESIS = 18L * 16_000L
        private val TERMINALS = setOf('。'.code, '！'.code, '？'.code, '!'.code, '?'.code)
        private fun commonPrefix(a: ReversibleAgreementKey, b: ReversibleAgreementKey): Int {
            var count = 0
            while (count < minOf(a.spans.size, b.spans.size) && a.spans[count].codePoint == b.spans[count].codePoint) count++
            return count
        }

        /** Original tokens of ANY kind; no rounding, onset tolerance, or token/audio trim. */
        fun eligibilityRawStart(raw: String, tokens: List<String>, onsetsSeconds: List<Float>,
            startSample: Long, endSample: Long): Int {
            require(tokens.size == onsetsSeconds.size && tokens.joinToString("") == raw)
            require(onsetsSeconds.all { it.isFinite() && it >= 0f })
            require(onsetsSeconds.zipWithNext().all { (a, b) -> a <= b })
            val retainedSample = maxOf(startSample, endSample - PREVIEW_STEP)
            var rawOffset = 0
            for (i in tokens.indices) {
                if (startSample.toDouble() + onsetsSeconds[i].toDouble() * 16_000.0 >= retainedSample.toDouble()) return rawOffset
                rawOffset += tokens[i].length
            }
            return raw.length
        }
    }
}
