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
        val priority = compareBy<ListenTranslateCue> { it.startMs }.thenBy { it.rev }
        return cues.filter { it.startMs <= pos && pos < it.endMs }.maxWithOrNull(priority)
            ?: cues.filter { it.startMs <= pos && it.endMs >= pos }.maxWithOrNull(priority)
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
