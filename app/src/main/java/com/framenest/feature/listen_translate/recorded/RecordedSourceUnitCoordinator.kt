package com.framenest.feature.listen_translate.recorded

import com.framenest.feature.listen_translate.RecordedExpansion
import com.framenest.feature.listen_translate.RecordedFrozenAnchor
import com.framenest.feature.listen_translate.RecordedHypothesis
import com.framenest.feature.listen_translate.RecordedOverlapAlignment
import com.framenest.feature.listen_translate.RecordedPending

/**
 * Serialized, single-owner recorded-audio coordinator. No Android, model calls, or persistence I/O.
 * PCM reads ALWAYS remain on the original 9-second grid. Text cuts cannot move that grid.
 * Read progress, source finality, translation success, and completed coverage are distinct.
 */
internal class RecordedSourceUnitCoordinator(
    morphology: JapaneseMorphology,
    initialGeneration: Long = 0,
    initialStartMs: Long = 0,
    private val maxQueuedUnits: Int = 4,
    private val maxContextChars: Int = 1024,
    private val maxContextMs: Long = 24_000,
) {
    private data class Chunk(val h: RecordedHypothesis, val begin: Int, val end: Int) {
        val text get() = h.text.substring(begin, end)
    }
    private data class Recovery(val request: RecordedExpansion, val newer: RecordedWindowInput)
    private val boundary = JapaneseSourceBoundary(morphology)
    private var generation = initialGeneration
    private var sequence = 0L
    private var readThrough = initialStartMs
    private var nextWindow = initialStartMs
    private var coverageStart = initialStartMs
    private var leftComplete = initialStartMs == 0L
    private var ended = false
    private var pending: RecordedPending? = null
    private var frozenAnchor: RecordedFrozenAnchor? = null
    private val stable = mutableListOf<Chunk>()
    private var quiet = emptyList<RecordedQuietSpan>()
    private var recovery: Recovery? = null
    private var provisional: RecordedSourceUnit? = null
    private val queue = linkedMapOf<RecordedUnitId, RecordedTranslationTicket>()
    private val retries = linkedMapOf<RecordedUnitId, RecordedTranslationTicket>()

    init {
        require(initialStartMs >= 0 && maxQueuedUnits in 2..8)
        require(maxContextChars in 128..1024 && maxContextMs in 15_000..30_000)
    }

    fun snapshot(): RecordedCoordinatorResult = result(true)

    fun reset(nextGeneration: Long, startMs: Long): RecordedCoordinatorResult {
        require(nextGeneration > generation && startMs >= 0)
        generation = nextGeneration; sequence = 0; readThrough = startMs; nextWindow = startMs
        coverageStart = startMs; leftComplete = startMs == 0L; ended = false
        pending = null; frozenAnchor = null; stable.clear(); quiet = emptyList()
        recovery = null; provisional = null; queue.clear(); retries.clear()
        return result(true)
    }

    fun push(input: RecordedWindowInput): RecordedCoordinatorResult {
        val h = input.hypothesis
        if (h.generation != generation) return result(false, error = "stale_generation")
        if (ended) return result(false, error = "already_ended")
        if (recovery != null) return result(false, error = "awaiting_union_result")
        if (queue.size >= maxQueuedUnits - 1) return result(false, error = "translation_backpressure")
        validate(input, 15_000)
        if (h.startMs != nextWindow) return result(false, error = "not_next_fixed_window")
        val records = mutableListOf<RecordedCaptionRecord>()
        val ready = mutableListOf<RecordedTranslationTicket>()
        val old = pending
        val oldContext = chunks()
        readThrough = maxOf(readThrough, h.endMs)
        nextWindow = h.startMs + 9_000
        quiet = (quiet + input.quietSpans).distinct().filter { it.endMs >= readThrough - 30_000 }.takeLast(256)
        if (h.text.isBlank() && text().isBlank()) {
            // An empty recognizer result is not proof of silence. Do not later let a
            // successful sentence retroactively complete this unidentified audio.
            pending = null; stable.clear(); frozenAnchor = null; provisional = null
            coverageStart = h.endMs; leftComplete = false
            return result(true)
        } else if (old == null) {
            seed(input, completeLeft = leftComplete && h.startMs == coverageStart)
        } else if (h.text.isBlank()) {
            // Empty ASR is not silence evidence and never completes coverage.
            if (readThrough - coverageStart >= maxContextMs) {
                emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT,
                    old.window.endMs, ready, records, consume = true)
                pending = null; stable.clear(); frozenAnchor = null; coverageStart = h.endMs; leftComplete = false
            }
            return result(true, ready, records)
        } else if (h.endMs - coverageStart > maxContextMs) {
            // Archive the complete old context; restart only on the fixed grid, retaining overlap.
            emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT,
                old.window.endMs, ready, records, consume = true)
            seed(input, completeLeft = false)
        } else {
            // If a complete utterance consumed the whole prior hypothesis, use its
            // immutable source anchor to skip only verified repeated overlap. Starting
            // the newer window at raw zero could repeat a final sentence or split a word.
            val consumedRecovery = if (old.text.isEmpty()) frozenAnchor?.let { RecordedOverlapAlignment.recover(it, h) } else null
            val aligned = if (old.text.isNotEmpty()) RecordedOverlapAlignment.align(old, h) else null
            val cut = aligned?.cut
            if (old.text.isEmpty() && consumedRecovery?.audit != null) {
                pending = RecordedPending(h, consumedRecovery.audit.replacementPendingStart, coverageStart)
            } else if (cut == null || cut.oldOffset <= old.rawStart) {
                val union = if (h.endMs - old.window.startMs <= 24_000)
                    RecordedExpansion(generation, old.window.startMs, h.endMs) else null
                if (union != null) {
                    recovery = Recovery(union, input)
                    return result(true, error = aligned?.error ?: consumedRecovery?.error ?: "no_stitch_progress")
                }
                emitContext(RecordedSourceStatus.UNCONFIRMED, RecordedSourceReason.ALIGNMENT_UNCONFIRMED,
                    old.window.endMs, ready, records, consume = true)
                seed(input, completeLeft = false)
            } else {
                val added = Chunk(old.window, old.rawStart, cut.oldOffset)
                val candidateSize = stable.sumOf { it.end - it.begin } + added.end - added.begin + h.text.length - cut.newOffset
                if (candidateSize > maxContextChars) {
                    // Never truncate a source string merely to fit the working-memory bound.
                    check(oldContext.sumOf { it.end - it.begin } <= maxContextChars)
                    emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT,
                        old.window.endMs, ready, records, consume = true)
                    seed(input, completeLeft = false)
                } else {
                    stable += added
                    frozenAnchor = RecordedFrozenAnchor(old.window, old.rawStart, cut.oldOffset)
                    pending = RecordedPending(h, cut.newOffset, coverageStart)
                }
            }
        }
        releaseBoundaries(ready, records)
        if (text().isNotEmpty() && (readThrough - coverageStart >= maxContextMs || text().length >= maxContextChars)) {
            emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT,
                readThrough, ready, records, consume = false)
        }
        return result(true, ready, records)
    }

    /** Exactly one bounded union result is accepted for the outstanding request. */
    fun recoverUnion(input: RecordedWindowInput): RecordedCoordinatorResult {
        if (input.hypothesis.generation != generation) return result(false, error = "stale_generation")
        val blocked = recovery ?: return result(false, error = "no_union_requested")
        validate(input, 24_000)
        val h = input.hypothesis
        if (h.startMs != blocked.request.startMs || h.endMs != blocked.request.endMs)
            return result(false, error = "stale_union_window")
        if (h.text.isBlank()) return failUnion(generation, "empty_union_hypothesis")
        val old = requireNotNull(pending)
        val anchor = frozenAnchor
        val recovered = if (old.rawStart > 0 && anchor != null) RecordedOverlapAlignment.recover(anchor, h) else null
        if (old.rawStart > 0 && recovered?.audit == null) return failUnion(generation,
            recovered?.error ?: "missing_frozen_anchor")
        pending = RecordedPending(h, recovered?.audit?.replacementPendingStart ?: 0, coverageStart)
        recovery = null
        quiet = (quiet + input.quietSpans).distinct().filter { it.endMs >= readThrough - 30_000 }.takeLast(256)
        val ready = mutableListOf<RecordedTranslationTicket>()
        val records = mutableListOf<RecordedCaptionRecord>()
        if (text().length > maxContextChars) {
            // The old prefix plus union tail exceeded the cap. Retain both hypotheses as
            // unconfirmed contexts rather than silently deleting either text range.
            pending = old
            return abandonRecovery(blocked, ready, records, "union_context_bound")
        }
        releaseBoundaries(ready, records)
        if (text().isNotEmpty() && readThrough - coverageStart >= maxContextMs)
            emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT,
                readThrough, ready, records, consume = false)
        return result(true, ready, records)
    }

    fun failUnion(atGeneration: Long, reason: String = "union_decode_failed"): RecordedCoordinatorResult {
        if (atGeneration != generation) return result(false, error = "stale_generation")
        val blocked = recovery ?: return result(false, error = "no_union_requested")
        return abandonRecovery(blocked, mutableListOf(), mutableListOf(), reason)
    }

    private fun abandonRecovery(blocked: Recovery, ready: MutableList<RecordedTranslationTicket>,
        records: MutableList<RecordedCaptionRecord>, reason: String): RecordedCoordinatorResult {
        emitContext(RecordedSourceStatus.UNCONFIRMED, RecordedSourceReason.ALIGNMENT_UNCONFIRMED,
            pending!!.window.endMs, ready, records, consume = true)
        recovery = null
        seed(blocked.newer, completeLeft = false)
        // Subsequent windows continue; the already tried union is never requested again.
        releaseBoundaries(ready, records)
        return result(true, ready, records, error = reason)
    }

    fun finish(atGeneration: Long, eofMs: Long): RecordedCoordinatorResult {
        if (atGeneration != generation) return result(false, error = "stale_generation")
        if (ended) return result(true)
        if (recovery != null) return result(false, error = "resolve_union_before_eof")
        if (queue.size >= maxQueuedUnits - 1) return result(false, error = "translation_backpressure")
        // Millisecond PCM sample rounding can differ from an explicit media EOF by 1 ms.
        require(eofMs >= 0 && eofMs >= readThrough - 1)
        val ready = mutableListOf<RecordedTranslationTicket>()
        val records = mutableListOf<RecordedCaptionRecord>()
        readThrough = eofMs
        releaseBoundaries(ready, records, eof = true)
        if (text().isNotBlank()) emitContext(RecordedSourceStatus.PROVISIONAL, RecordedSourceReason.INCOMPLETE_EOF,
            eofMs, ready, records, consume = true)
        if (text().isBlank()) { pending = null; stable.clear() }
        ended = true
        return result(true, ready, records)
    }

    fun translationSucceeded(ticket: RecordedTranslationTicket, translatedText: String): RecordedCoordinatorResult {
        if (!current(ticket)) return result(false, error = "stale_translation")
        if (translatedText.isBlank()) return translationFailed(ticket, "empty_translation")
        queue.remove(ticket.unit.id)
        val u = ticket.unit
        return result(true, records = listOf(RecordedCaptionRecord(u, RecordedResultState.TRANSLATED, translatedText)),
            completed = if (u.status == RecordedSourceStatus.FINAL)
                RecordedCompletedCoverage(u.id, u.coverageStartMs, u.coverageEndMs) else null)
    }

    /** Keep uncertain source visible without pretending that an MT call failed. */
    fun deferUnconfirmed(ticket: RecordedTranslationTicket): RecordedCoordinatorResult {
        if (!current(ticket)) return result(false, error = "stale_translation")
        if (ticket.unit.status == RecordedSourceStatus.FINAL)
            return result(false, error = "final_source_cannot_be_deferred")
        queue.remove(ticket.unit.id)
        retries.remove(ticket.unit.id)
        return result(true, records = listOf(RecordedCaptionRecord(ticket.unit, RecordedResultState.SOURCE_READY,
            deferredReason = "source_pending_confirmation")))
    }

    fun translationFailed(ticket: RecordedTranslationTicket, reason: String): RecordedCoordinatorResult {
        if (!current(ticket)) return result(false, error = "stale_translation")
        queue.remove(ticket.unit.id)
        val retry = if (ticket.attempt < 2) ticket.copy(attempt = ticket.attempt + 1) else null
        if (retry != null) {
            retries[ticket.unit.id] = retry
            while (retries.size > maxQueuedUnits) retries.remove(retries.keys.first())
        }
        return result(true, records = listOf(RecordedCaptionRecord(ticket.unit, RecordedResultState.TRANSLATION_FAILED,
            error = reason.take(160))), retry = retry)
    }

    /** Explicit caller retry only. No automatic NONE/retry busy loop. */
    fun retryTranslation(ticket: RecordedTranslationTicket): RecordedCoordinatorResult {
        if (ticket.unit.id.generation != generation || ticket.attempt != 2 || queue.containsKey(ticket.unit.id) || retries[ticket.unit.id] != ticket)
            return result(false, error = "invalid_retry")
        if (queue.size >= maxQueuedUnits) return result(false, error = "translation_backpressure")
        retries.remove(ticket.unit.id)
        queue[ticket.unit.id] = ticket
        return result(true, ready = listOf(ticket))
    }

    private fun current(ticket: RecordedTranslationTicket) = ticket.unit.id.generation == generation && queue[ticket.unit.id] == ticket

    private fun releaseBoundaries(ready: MutableList<RecordedTranslationTicket>, records: MutableList<RecordedCaptionRecord>, eof: Boolean = false) {
        while (queue.size < maxQueuedUnits - 1 && text().isNotBlank()) {
            val all = chunks()
            val source = all.joinToString("") { it.text }
            val found = boundary.first(source, anchors(all), quiet, readThrough, eof) ?: break
            if (found.mediaEndMs <= coverageStart) break
            val status = if (leftComplete) RecordedSourceStatus.FINAL else RecordedSourceStatus.PROVISIONAL
            emit(found.rawEnd, status, if (leftComplete) found.reason else RecordedSourceReason.LEFT_CONTEXT_UNCONFIRMED,
                found.mediaEndMs, ready, records, consume = true)
            leftComplete = true
        }
    }

    private fun emitContext(status: RecordedSourceStatus, reason: RecordedSourceReason, endMs: Long,
        ready: MutableList<RecordedTranslationTicket>, records: MutableList<RecordedCaptionRecord>, consume: Boolean) {
        if (text().isBlank()) return
        val existing = provisional
        if (existing?.text == text() && existing.status == status && existing.reason == reason) {
            // Already persisted/queued this exact context. Rotation is not a reason to run MT twice.
            if (consume) { consume(text().length); coverageStart = endMs; provisional = null }
            return
        }
        emit(text().length, status, reason, endMs, ready, records, consume)
        if (consume) provisional = null
    }

    private fun emit(rawEnd: Int, status: RecordedSourceStatus, reason: RecordedSourceReason, endMs: Long,
        ready: MutableList<RecordedTranslationTicket>, records: MutableList<RecordedCaptionRecord>, consume: Boolean) {
        check(queue.size < maxQueuedUnits)
        val parts = prefix(chunks(), rawEnd)
        val raw = parts.joinToString("") { it.text }
        val mapped = anchors(parts)
        val previous = provisional
        val u = RecordedSourceUnit(RecordedUnitId(generation, ++sequence), raw, status, reason,
            (mapped.firstOrNull()?.estimatedTokenStartMs ?: coverageStart).coerceIn(coverageStart, endMs - 1),
            endMs, coverageStart, endMs, mapped, previous?.id)
        if (previous != null) {
            queue.remove(previous.id)
            retries.remove(previous.id)
            ready.removeAll { it.unit.id == previous.id }
            records += RecordedCaptionRecord(previous, RecordedResultState.SUPERSEDED, supersededBy = u.id)
            provisional = null
        }
        val ticket = RecordedTranslationTicket(u)
        queue[u.id] = ticket; ready += ticket
        records += RecordedCaptionRecord(u, RecordedResultState.SOURCE_READY)
        if (!consume) provisional = u
        else {
            consume(rawEnd)
            coverageStart = endMs
        }
    }

    private fun consume(count: Int) {
        var remaining = count
        while (remaining > 0 && stable.isNotEmpty()) {
            val c = stable.first(); val n = minOf(remaining, c.end - c.begin)
            frozenAnchor = RecordedFrozenAnchor(c.h, c.begin, c.begin + n)
            if (n == c.end - c.begin) stable.removeAt(0) else stable[0] = c.copy(begin = c.begin + n)
            remaining -= n
        }
        if (remaining > 0) {
            val p = requireNotNull(pending)
            frozenAnchor = RecordedFrozenAnchor(p.window, p.rawStart, p.rawStart + remaining)
            pending = p.copy(rawStart = p.rawStart + remaining)
        }
    }

    private fun seed(input: RecordedWindowInput, completeLeft: Boolean) {
        stable.clear(); pending = RecordedPending(input.hypothesis, 0, input.hypothesis.startMs)
        frozenAnchor = null; provisional = null; coverageStart = input.hypothesis.startMs; leftComplete = completeLeft
        quiet = input.quietSpans
    }
    private fun chunks(): List<Chunk> = stable + listOfNotNull(pending?.let { Chunk(it.window, it.rawStart, it.window.text.length) }).filter { it.end > it.begin }
    private fun text(): String = chunks().joinToString("") { it.text }
    private fun prefix(parts: List<Chunk>, length: Int): List<Chunk> {
        var left = length
        return parts.mapNotNull { c ->
            val n = minOf(left, c.end - c.begin); left -= n
            if (n == 0) null else c.copy(end = c.begin + n)
        }.also { require(left == 0) }
    }
    private fun anchors(parts: List<Chunk>): List<RecordedCharacterAnchor> {
        val out = mutableListOf<RecordedCharacterAnchor>()
        var unitOffset = 0
        for (c in parts) {
            var raw = 0
            for (token in c.h.tokens) {
                val a = maxOf(raw, c.begin); val b = minOf(raw + token.text.length, c.end)
                if (a < b) out += RecordedCharacterAnchor(unitOffset + a - c.begin, unitOffset + b - c.begin,
                    c.h.startMs, c.h.endMs, a, b, token.startMs)
                raw += token.text.length
            }
            unitOffset += c.end - c.begin
        }
        return out
    }
    private fun validate(input: RecordedWindowInput, duration: Long) {
        val h = input.hypothesis
        require(h.startMs >= 0 && h.endMs > h.startMs && h.endMs - h.startMs <= duration)
        require(h.text.length <= maxContextChars && h.tokens.size <= maxContextChars)
        require(h.tokens.all { it.startMs in h.startMs until h.endMs })
        require(h.tokens.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        require(input.quietSpans.size <= 256 && input.quietSpans.all { it.startMs >= h.startMs && it.endMs <= h.endMs })
    }
    private fun result(accepted: Boolean, ready: List<RecordedTranslationTicket> = emptyList(),
        records: List<RecordedCaptionRecord> = emptyList(), completed: RecordedCompletedCoverage? = null,
        retry: RecordedTranslationTicket? = null, error: String? = null): RecordedCoordinatorResult =
        RecordedCoordinatorResult(accepted, readThrough, nextWindow, ready, text(), records, completed, retry,
            recovery?.let { RecordedNextAction.DecodeUnion(it.request) }
                ?: queue.values.firstOrNull()?.let { RecordedNextAction.Translate(it) }
                ?: if (ended) RecordedNextAction.Finished else RecordedNextAction.DecodeWindow(nextWindow), error,
            pendingSource = text().takeIf { it.isNotEmpty() }?.let {
                val mapped = anchors(chunks())
                RecordedPendingSource(it, maxOf(coverageStart, mapped.firstOrNull()?.estimatedTokenStartMs ?: coverageStart),
                    readThrough, mapped)
            })
}
