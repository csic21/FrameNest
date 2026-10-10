package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.ListenTranslateCue

/**
 * Pure helpers for windowed listen-translate scheduling (FN-12).
 */
object ListenTranslateWindows {
    const val DEFAULT_WINDOW_MS: Long = 3_000L

    /**
     * Align [positionMs] into a half-open window `[start, end)`.
     * Last window may end at [durationMs] when known.
     */
    fun windowContaining(
        positionMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        durationMs: Long = 0L,
    ): Pair<Long, Long> {
        require(windowMs > 0L)
        val pos = positionMs.coerceAtLeast(0L)
        val start = (pos / windowMs) * windowMs
        var end = start + windowMs
        if (durationMs > 0L) {
            end = end.coerceAtMost(durationMs)
            if (end <= start && durationMs > start) {
                end = durationMs
            }
        }
        if (end <= start) {
            end = start + windowMs
        }
        return start to end
    }

    fun cueAt(cues: List<ListenTranslateCue>, positionMs: Long): ListenTranslateCue? {
        val pos = positionMs.coerceAtLeast(0L)
        var bestExclusive: ListenTranslateCue? = null
        var bestInclusive: ListenTranslateCue? = null
        for (cue in cues) {
            if (cue.startMs > pos) continue
            if (pos < cue.endMs && hasHigherPriority(cue, bestExclusive)) {
                bestExclusive = cue
            }
            if (pos <= cue.endMs && hasHigherPriority(cue, bestInclusive)) {
                bestInclusive = cue
            }
        }
        return bestExclusive ?: bestInclusive
    }

    private fun hasHigherPriority(
        candidate: ListenTranslateCue,
        current: ListenTranslateCue?,
    ): Boolean {
        if (current == null) return true
        // Coverage records describe processing, not display. They must never hide speech.
        val candidateHasText = candidate.textSrc.isNotBlank() || candidate.textTgt.isNotBlank()
        val currentHasText = current.textSrc.isNotBlank() || current.textTgt.isNotBlank()
        if (candidateHasText != currentHasText) return candidateHasText
        return compareValuesBy(candidate, current,
            { it.startMs }, { it.rev }, { it.endMs }, { it.id }) > 0
    }

    /**
     * True when no cue fully covers [startMs, endMs) (allowing tiny edge slack).
     */
    fun needsFill(
        cues: List<ListenTranslateCue>,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        if (endMs <= startMs) return false
        val covering = cues.any { cue ->
            val translationComplete = cue.textSrc.isBlank() ||
                cue.textTgt.isNotBlank() ||
                cue.languages.sourceLang.equals(cue.languages.targetLang, ignoreCase = true)
            cue.startMs <= startMs && cue.endMs >= endMs && translationComplete
        }
        return !covering
    }

    /**
     * Legacy and audible-but-unrecognized blank coverage may be retried only when
     * the playhead is inside that exact window. Confirmed silence remains cached.
     */
    fun needsBlankRecoveryAt(
        cues: List<ListenTranslateCue>,
        positionMs: Long,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        val active = cueAt(cues, positionMs) ?: return false
        if (active.textSrc.isNotBlank() || active.textTgt.isNotBlank()) return false
        if (active.startMs > startMs || active.endMs < endMs) return false
        return active.rev == ListenCoverageRev.LEGACY_BLANK ||
            active.rev == ListenCoverageRev.UNRECOGNIZED_SPEECH
    }

    fun formatOverlay(
        cue: ListenTranslateCue?,
        mode: ListenDisplayMode,
    ): String {
        if (cue == null) return ""
        return when (mode) {
            ListenDisplayMode.SourceOnly -> cue.textSrc
            ListenDisplayMode.TargetOnly -> cue.textTgt
            ListenDisplayMode.Bilingual -> {
                when {
                    cue.textSrc.isBlank() -> cue.textTgt
                    cue.textTgt.isBlank() -> cue.textSrc
                    cue.textSrc == cue.textTgt -> cue.textSrc
                    else -> "${cue.textSrc}\n${cue.textTgt}"
                }
            }
        }
    }
}

/** Maintains the DAO's `start_ms ASC, rev ASC` order without reloading every cue. */
internal object ListenCueCache {
    fun upsert(
        existing: List<ListenTranslateCue>,
        incoming: ListenTranslateCue,
    ): List<ListenTranslateCue> {
        val result = ArrayList<ListenTranslateCue>(existing.size + 1)
        var inserted = false
        for (cue in existing) {
            if (cue.startMs == incoming.startMs && cue.endMs == incoming.endMs) {
                continue
            }
            if (!inserted && compare(incoming, cue) <= 0) {
                result += incoming
                inserted = true
            }
            result += cue
        }
        if (!inserted) result += incoming
        return result
    }

    private fun compare(left: ListenTranslateCue, right: ListenTranslateCue): Int {
        val start = left.startMs.compareTo(right.startMs)
        return if (start != 0) start else compareValuesBy(left, right, { it.rev }, { it.endMs }, { it.id })
    }
}

internal object ListenCoverageRev {
    const val LEGACY_BLANK: Int = 0
    const val CONFIRMED_SILENCE: Int = 1
    const val UNRECOGNIZED_SPEECH: Int = 2
}
