package com.framenest.feature.listen_translate

import java.text.Normalizer

internal data class RecordedToken(val text: String, val startMs: Long)
internal data class RecordedHypothesis(val generation: Long, val startMs: Long, val endMs: Long, val tokens: List<RecordedToken>) {
    val text: String get() = tokens.joinToString("") { it.text }
}
/** Transcript fragment only: may end INSIDE a word. Never translate each fragment alone. */
internal data class RecordedFrozenText(val coarseLabelStartMs: Long, val coarseLabelEndMs: Long, val text: String)
internal data class RecordedPending(val window: RecordedHypothesis, val rawStart: Int, val coarseLabelStartMs: Long) {
    val text: String get() = window.text.substring(rawStart)
}
internal data class RecordedExpansion(val generation: Long, val startMs: Long, val endMs: Long)
/** UTF-16 offsets into unmodified concatenated token text. No deletion is unaccounted for. */
internal data class RecordedOffsetMatch(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)
internal data class RecordedSpliceAudit(
    val oldWindowStartMs: Long, val newWindowStartMs: Long,
    val oldRetainedStart: Int, val oldRetainedEnd: Int,
    val oldReplacedStart: Int, val oldReplacedEnd: Int,
    val newOmittedPrefixEnd: Int, val newPendingStart: Int,
    val matchedCodePoints: Int, val editCount: Int,
    val matchedOffsets: List<RecordedOffsetMatch>,
)
internal data class RecordedFrozenAnchor(val window: RecordedHypothesis, val rawStart: Int, val rawEnd: Int)
internal data class RecordedRecoveryAudit(
    val frozenWindowStartMs: Long, val frozenAnchorStart: Int, val frozenAnchorEnd: Int,
    val replacementAnchorStart: Int, val replacementAnchorEnd: Int,
    val replacementPendingStart: Int, val matchedCodePoints: Int,
)
internal data class RecordedStitchResult(
    val frozenText: RecordedFrozenText? = null,
    val pending: RecordedPending? = null,
    val conflict: String? = null,
    val expansion: RecordedExpansion? = null,
    val audit: RecordedSpliceAudit? = null,
    val eof: Boolean = false,
    val abandonedPending: RecordedPending? = null,
    val recoveryAudit: RecordedRecoveryAudit? = null,
)

/**
 * Independent recorded-audio experiment. Fixed audio windows are supplied by the caller;
 * no text/timestamp-derived audio restart is produced. Final means frozen TEXT, not MT
 * success, verified semantic completeness, or proof that ASR did not omit words.
 */
internal class RecordedTranscriptStitcher(initialGeneration: Long = 0L) {
    private var generation = initialGeneration
    private var pending: RecordedPending? = null
    private var blocked: RecordedExpansion? = null
    private var ended = false
    private var frozenAnchor: RecordedFrozenAnchor? = null

    fun reset(nextGeneration: Long): RecordedStitchResult {
        require(nextGeneration > generation)
        val abandoned = pending
        generation = nextGeneration
        pending = null
        blocked = null
        ended = false
        frozenAnchor = null
        return RecordedStitchResult(abandonedPending = abandoned)
    }

    fun push(next: RecordedHypothesis): RecordedStitchResult {
        if (next.generation != generation) return RecordedStitchResult(conflict = "stale_generation")
        if (ended) return RecordedStitchResult(conflict = "already_ended")
        if (blocked != null) return RecordedStitchResult(pending = pending, conflict = "unresolved_boundary")
        validate(next, 15_000L)
        val old = pending
        if (old == null) {
            pending = RecordedPending(next, 0, next.startMs)
            return RecordedStitchResult(pending = pending)
        }
        if (next.startMs <= old.window.startMs || next.endMs <= old.window.endMs || next.startMs >= old.window.endMs) {
            return conflict(old, next, "non_overlapping_or_non_advancing_window", allowExpansion = false)
        }
        val alignment = RecordedOverlapAlignment.align(old, next)
        if (alignment.cut == null) return conflict(old, next, alignment.error ?: "no_alignment")
        val cut = alignment.cut
        val coarseBoundary = next.startMs + (old.window.endMs - next.startMs) / 2L
        if (cut.oldOffset <= old.rawStart || coarseBoundary <= old.coarseLabelStartMs) {
            return conflict(old, next, "no_frozen_progress")
        }
        val frozen = RecordedFrozenText(old.coarseLabelStartMs, coarseBoundary,
            old.window.text.substring(old.rawStart, cut.oldOffset))
        frozenAnchor = RecordedFrozenAnchor(old.window, old.rawStart, cut.oldOffset)
        pending = RecordedPending(next, cut.newOffset, coarseBoundary)
        return RecordedStitchResult(frozenText = frozen, pending = pending, audit = RecordedSpliceAudit(
            old.window.startMs, next.startMs, old.rawStart, cut.oldOffset,
            cut.oldOffset, old.window.text.length, cut.newOffset, cut.newOffset,
            cut.matches, cut.edits, cut.offsets,
        ))
    }

    /** Outer coordinator may fulfill the ONE requested union decode; no model call here. */
    fun replacePendingWithExpansion(expanded: RecordedHypothesis): RecordedStitchResult {
        if (expanded.generation != generation) return RecordedStitchResult(conflict = "stale_generation")
        val request = blocked ?: return RecordedStitchResult(conflict = "no_expansion_requested")
        val old = pending ?: return RecordedStitchResult(conflict = "no_pending_tail")
        validate(expanded, 24_000L)
        require(expanded.startMs == request.startMs && expanded.endMs == request.endMs)
        val recovery = if (old.rawStart != 0) {
            val anchor = frozenAnchor ?: return RecordedStitchResult(pending = old, conflict = "missing_frozen_prefix_anchor")
            RecordedOverlapAlignment.recover(anchor, expanded)
        } else null
        if (old.rawStart != 0 && recovery?.audit == null) {
            return RecordedStitchResult(pending = old, conflict = recovery?.error ?: "unresolved_frozen_prefix_anchor")
        }
        val rawStart = recovery?.audit?.replacementPendingStart ?: 0
        pending = RecordedPending(expanded, rawStart, old.coarseLabelStartMs)
        blocked = null
        return RecordedStitchResult(pending = pending, abandonedPending = old, recoveryAudit = recovery?.audit)
    }

    /** Explicit reliable EOF only. Repeated flush is idempotent, and conflicts cannot flush. */
    fun finish(atGeneration: Long): RecordedStitchResult {
        if (atGeneration != generation) return RecordedStitchResult(conflict = "stale_generation")
        if (ended) return RecordedStitchResult(eof = true)
        if (blocked != null) return RecordedStitchResult(pending = pending, conflict = "unresolved_boundary")
        ended = true
        val tail = pending
        pending = null
        return RecordedStitchResult(frozenText = tail?.let { RecordedFrozenText(it.coarseLabelStartMs, it.window.endMs, it.text) }, eof = true)
    }

    private fun conflict(old: RecordedPending, next: RecordedHypothesis, reason: String,
        allowExpansion: Boolean = true): RecordedStitchResult {
        val request = if (allowExpansion && next.endMs - old.window.startMs <= 24_000L)
            RecordedExpansion(generation, old.window.startMs, next.endMs) else null
        // A sentinel also blocks a second request when no bounded expansion is available.
        blocked = request ?: RecordedExpansion(generation, old.window.startMs, old.window.endMs)
        return RecordedStitchResult(pending = old, conflict = reason, expansion = request)
    }

    private fun validate(h: RecordedHypothesis, maxMs: Long) {
        require(h.startMs >= 0L && h.endMs > h.startMs && h.endMs - h.startMs <= maxMs)
        require(h.tokens.size <= 4096 && h.text.length <= 16_384)
        require(h.tokens.all { it.startMs in h.startMs until h.endMs })
        require(h.tokens.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        require(h.text.isNotBlank())
    }
}

internal object RecordedOverlapAlignment {
    private const val MAX_SIDE = 512
    private const val TIME_SLACK_MS = 1_500L
    private data class Point(val value: Int, val rawStart: Int, val rawEnd: Int, val time: Long)
    data class Cut(val oldOffset: Int, val newOffset: Int, val matches: Int, val edits: Int, val offsets: List<RecordedOffsetMatch>)
    data class Outcome(val cut: Cut? = null, val error: String? = null)

    /** NFKC per original code point preserves a mapping even for compatibility expansion. */
    private fun points(h: RecordedHypothesis): List<Point> {
        val result = mutableListOf<Point>()
        var offset = 0
        for (token in h.tokens) {
            var i = 0
            while (i < token.text.length) {
                val cp = token.text.codePointAt(i)
                val size = Character.charCount(cp)
                val normalized = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
                normalized.codePoints().forEach { n ->
                    // Letters/digits/marks remain; whitespace, punctuation and symbols do not
                    // serve as anchors. Original output keeps ALL those characters unchanged.
                    val type = Character.getType(n)
                    if (Character.isLetterOrDigit(n) || type == Character.NON_SPACING_MARK.toInt() ||
                        type == Character.COMBINING_SPACING_MARK.toInt()) {
                        result += Point(n, offset + i, offset + i + size, token.startMs)
                    }
                }
                i += size
            }
            offset += token.text.length
        }
        return result
    }

    data class Recovery(val audit: RecordedRecoveryAudit? = null, val error: String? = null)

    /** Exact suffix of the ACTUALLY frozen text, not the abandoned hypothesis prefix. */
    fun recover(anchor: RecordedFrozenAnchor, expanded: RecordedHypothesis): Recovery {
        val suffix = points(anchor.window).filter {
            it.rawStart >= anchor.rawStart && it.rawEnd <= anchor.rawEnd &&
                it.time >= expanded.startMs - TIME_SLACK_MS
        }.takeLast(32)
        val target = points(expanded)
        if (suffix.size < 8) return Recovery(error = "frozen_anchor_too_short")
        if (target.size > MAX_SIDE) return Recovery(error = "recovery_alignment_bound_exceeded")
        for (length in listOf(32, 24, 16, 12, 8).filter { it <= suffix.size }.distinct()) {
            val wanted = suffix.takeLast(length)
            val occurrences = (0..target.size - length).filter { start ->
                wanted.indices.all { target[start + it].value == wanted[it].value }
            }
            if (occurrences.size > 1) return Recovery(error = "ambiguous_frozen_prefix_anchor")
            val start = occurrences.singleOrNull() ?: continue
            if (!wanted.indices.all { kotlin.math.abs(target[start + it].time - wanted[it].time) <= TIME_SLACK_MS }) continue
            val last = target[start + length - 1]
            val right = target.getOrNull(start + length) ?: return Recovery(error = "frozen_anchor_has_no_right_context")
            if (right.rawStart == last.rawStart) return Recovery(error = "frozen_anchor_splits_normalized_character")
            val originalLast = wanted.last()
            val trailingRaw = anchor.window.text.substring(originalLast.rawEnd, anchor.rawEnd)
            val cut = last.rawEnd + trailingRaw.length
            if (cut > expanded.text.length || expanded.text.substring(last.rawEnd, cut) != trailingRaw ||
                cut > right.rawStart) return Recovery(error = "frozen_anchor_trailing_raw_disagrees")
            return Recovery(RecordedRecoveryAudit(anchor.window.startMs, wanted.first().rawStart, anchor.rawEnd,
                target[start].rawStart, last.rawEnd, cut, length))
        }
        return Recovery(error = "frozen_prefix_anchor_not_found")
    }

    fun align(old: RecordedPending, next: RecordedHypothesis): Outcome {
        val a = points(old.window).filter { it.rawStart >= old.rawStart && it.time >= next.startMs - TIME_SLACK_MS }
        val b = points(next).filter { it.time < old.window.endMs + TIME_SLACK_MS }
        if (a.size > MAX_SIDE || b.size > MAX_SIDE) return Outcome(error = "alignment_bound_exceeded")
        if (a.size < 8 || b.size < 8) return Outcome(error = "insufficient_overlap_text")
        val width = b.size + 1
        val score = IntArray((a.size + 1) * width)
        val direction = ByteArray(score.size)
        var best = 0
        val ends = mutableListOf<Int>()
        for (i in 1..a.size) for (j in 1..b.size) {
            val index = i * width + j
            val equal = a[i - 1].value == b[j - 1].value &&
                kotlin.math.abs(a[i - 1].time - b[j - 1].time) <= TIME_SLACK_MS
            val diagonal = score[index - width - 1] + if (equal) 3 else -2
            val up = score[index - width] - 2
            val left = score[index - 1] - 2
            val value = maxOf(0, diagonal, up, left)
            score[index] = value
            direction[index] = when { value == 0 -> 0; value == diagonal -> 1; value == up -> 2; else -> 3 }
            if (value > best) { best = value; ends.clear(); ends += index }
            else if (value == best && value > 0) ends += index
        }
        data class Path(val pairs: List<Pair<Int, Int>>, val edits: Int)
        fun path(end: Int): Path {
            var index = end
            val pairs = mutableListOf<Pair<Int, Int>>()
            var edits = 0
            while (score[index] > 0) {
                val i = index / width; val j = index % width
                when (direction[index].toInt()) {
                    1 -> {
                        if (a[i - 1].value == b[j - 1].value && kotlin.math.abs(a[i - 1].time - b[j - 1].time) <= TIME_SLACK_MS)
                            pairs += (i - 1) to (j - 1) else edits++
                        index -= width + 1
                    }
                    2 -> { edits++; index -= width }
                    else -> { edits++; index-- }
                }
            }
            return Path(pairs.asReversed(), edits)
        }
        val chosen = ends.firstOrNull()?.let(::path) ?: return Outcome(error = "no_alignment")
        if (chosen.pairs.size < 8 || chosen.edits * 4 > chosen.pairs.size) return Outcome(error = "weak_alignment")
        if (ends.drop(1).take(64).any { path(it).pairs != chosen.pairs }) return Outcome(error = "ambiguous_alignment")
        val chosenA = chosen.pairs.toMap()
        val chosenB = chosen.pairs.associate { it.second to it.first }
        var alternatives = 0
        for (index in score.indices) {
            if (score[index] < best - 3 || score[index] == 0) continue
            if (++alternatives > 256) return Outcome(error = "alignment_alternative_bound_exceeded")
            val other = path(index)
            val conflicting = other.pairs.count { (i, j) ->
                (chosenA[i] != null && chosenA[i] != j) || (chosenB[j] != null && chosenB[j] != i)
            }
            if (other.pairs.size >= 8 && conflicting >= 4) return Outcome(error = "near_tied_alignment")
        }
        val runs = mutableListOf<MutableList<Pair<Int, Int>>>()
        for (pair in chosen.pairs) {
            val last = runs.lastOrNull()?.lastOrNull()
            if (last == null || pair.first != last.first + 1 || pair.second != last.second + 1) runs += mutableListOf(pair)
            else runs.last().add(pair)
        }
        fun occurrences(points: List<Point>, values: List<Int>): Int =
            (0..points.size - values.size).count { start -> values.indices.all { points[start + it].value == values[it] } }
        val anchors = runs.filter { run ->
            if (run.size < 4) false else {
                val values = run.map { a[it.first].value }
                occurrences(a, values) == 1 && occurrences(b, values) == 1
            }
        }
        if (anchors.sumOf { it.size / 4 } < 2) return Outcome(error = "non_unique_or_short_anchors")
        // Repeated long phrases can make a longer maximal run look unique. Refuse when
        // every four-character sub-anchor repeats in either side, rather than deleting it.
        val anchor = anchors.maxBy { it.size }
        val anchorValues = anchor.map { a[it.first].value }
        if ((4..anchorValues.size / 2).any { period ->
                anchorValues.indices.all { anchorValues[it] == anchorValues[it % period] }
            }) return Outcome(error = "repeated_phrase_ambiguity")
        val distinctive = anchor.windowed(4).any { run ->
            val values = run.map { a[it.first].value }
            occurrences(a, values) == 1 && occurrences(b, values) == 1
        }
        if (!distinctive) return Outcome(error = "repeated_phrase_ambiguity")
        // Both offsets point to the SAME matched character. Never split a surrogate or
        // a normalized expansion of one raw character; choose a raw boundary on both sides.
        val seam = anchor.drop(1).dropLast(1).sortedBy { kotlin.math.abs(anchor.indexOf(it) - anchor.size / 2) }
            .firstOrNull { (i, j) -> (i == 0 || a[i - 1].rawStart != a[i].rawStart) &&
                (j == 0 || b[j - 1].rawStart != b[j].rawStart) }
            ?: return Outcome(error = "no_raw_character_seam")
        return Outcome(Cut(a[seam.first].rawStart, b[seam.second].rawStart, chosen.pairs.size, chosen.edits,
            chosen.pairs.map { (i, j) -> RecordedOffsetMatch(a[i].rawStart, a[i].rawEnd, b[j].rawStart, b[j].rawEnd) }))
    }
}
