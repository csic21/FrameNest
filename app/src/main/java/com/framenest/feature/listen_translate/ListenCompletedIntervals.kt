package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.ListenTranslateCue

/** Nonempty half-open media interval. No tolerance: even a 1 ms gap remains work. */
internal data class ListenCompletedInterval(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0L && endMs > startMs)
    }
}

/**
 * Opt-in adaptive-span scheduling foundation; not connected to the production session.
 * Callers must first partition cues by validated media/track/model/language cache identity.
 * Cue display bounds are not a substitute for explicit whole-span completion records.
 */
internal object ListenCompletedIntervals {
    /**
     * Conservative bridge for existing records. In bilingual mode, source-only progress,
     * target-only records and uncertain blank records cannot certify completion. A blank
     * rev=1 record is the existing confirmed/completed coverage marker. The caller must
     * only supply persisted records, never provisional ASR progress with fabricated revs.
     */
    fun fromCues(
        cues: List<ListenTranslateCue>,
        requireTranslation: Boolean = true,
    ): List<ListenCompletedInterval> = union(cues.mapNotNull { cue ->
        val complete = when {
            cue.textSrc.isBlank() && cue.textTgt.isBlank() ->
                cue.rev == ListenCoverageRev.CONFIRMED_SILENCE
            cue.textSrc.isBlank() -> false
            requireTranslation -> cue.textTgt.isNotBlank()
            else -> true
        }
        if (!complete || cue.startMs < 0L || cue.endMs <= cue.startMs) null
        else ListenCompletedInterval(cue.startMs, cue.endMs)
    })

    /** Sorted, disjoint union; overlaps and exact adjacency merge without filling gaps. */
    fun union(intervals: List<ListenCompletedInterval>): List<ListenCompletedInterval> {
        val merged = mutableListOf<ListenCompletedInterval>()
        for (next in intervals.sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
            val previous = merged.lastOrNull()
            if (previous != null && next.startMs <= previous.endMs) {
                merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, next.endMs))
            } else {
                merged += next
            }
        }
        return merged
    }

    /**
     * First uncovered subrange in [startMs, endMs). Its end stops at the next completed
     * boundary, so an adaptive engine cannot overwrite a cached future utterance.
     * The caller additionally caps this returned range to its per-request audio budget.
     */
    fun firstGap(
        intervals: List<ListenCompletedInterval>,
        startMs: Long,
        endMs: Long,
    ): ListenCompletedInterval? {
        require(startMs >= 0L && endMs >= startMs)
        var cursor = startMs
        for (interval in union(intervals)) {
            if (cursor >= endMs) return null
            if (interval.endMs <= cursor) continue
            if (interval.startMs > cursor) {
                return ListenCompletedInterval(cursor, minOf(interval.startMs, endMs))
            }
            cursor = maxOf(cursor, interval.endMs)
        }
        return if (cursor < endMs) ListenCompletedInterval(cursor, endMs) else null
    }

    /** Completed media immediately ahead, clipped to the supplied horizon/media end. */
    fun contiguousAheadMs(
        intervals: List<ListenCompletedInterval>,
        positionMs: Long,
        limitEndMs: Long,
    ): Long {
        require(positionMs >= 0L && limitEndMs >= positionMs)
        val gap = firstGap(intervals, positionMs, limitEndMs)
        return (gap?.startMs ?: limitEndMs) - positionMs
    }
}
