package com.framenest.data.thumbnail

/**
 * Pure policy for which timestamps to sample when extracting a list thumbnail.
 *
 * Short videos stay near 20% of their length. Longer videos start past the usual
 * logo and fade, then fall back through a finite list when that frame is black
 * or a flat color field.
 */
object ThumbnailCandidatePolicy {
    /** Preferred seek when duration is unknown, and the last fallback. */
    const val PREFERRED_MS: Long = 10_000L

    /** Videos shorter than this use the 20%-of-duration rule. */
    const val SHORT_DURATION_THRESHOLD_MS: Long = 15_000L

    /** Videos at least this long sample a point inside the body, not the opening. */
    const val CONTENT_DURATION_THRESHOLD_MS: Long = 60_000L

    /** Percent of duration used for short videos. */
    const val SHORT_PERCENT: Int = 20

    /** Percent of duration used once a video is long enough to have an opening. */
    const val CONTENT_PERCENT: Int = 15

    /** Do not sample the body earlier than this on long videos. */
    const val CONTENT_MIN_MS: Long = 30_000L

    /** Cap the first sample so a long file does not seek deep into the timeline. */
    const val CONTENT_MAX_MS: Long = 120_000L

    /** Hard cap on candidate timestamps per generation attempt. */
    const val MAX_CANDIDATES: Int = 4

    /**
     * Ordered candidate timestamps in milliseconds (0-based media time).
     *
     * Always finite; empty only when [durationMs] is negative (invalid).
     * When duration is unknown (0), returns a single mid-clip guess of 10s so
     * extractors that can seek without duration still try once.
     */
    fun candidateTimestampsMs(durationMs: Long): List<Long> {
        if (durationMs < 0L) return emptyList()
        if (durationMs == 0L) {
            return listOf(PREFERRED_MS)
        }

        val lastUsable = (durationMs - 1L).coerceAtLeast(0L)
        val preferred = preferredTimestampMs(durationMs).coerceIn(0L, lastUsable)

        val extras = listOf(
            percentOf(durationMs, 45),
            percentOf(durationMs, 75),
            PREFERRED_MS,
        ).map { it.coerceIn(0L, lastUsable) }

        return (listOf(preferred) + extras)
            .distinct()
            .take(MAX_CANDIDATES)
    }

    fun preferredTimestampMs(durationMs: Long): Long {
        if (durationMs <= 0L) return PREFERRED_MS
        val lastUsable = durationMs - 1L
        return when {
            durationMs < SHORT_DURATION_THRESHOLD_MS ->
                percentOf(durationMs, SHORT_PERCENT).coerceIn(0L, lastUsable)
            durationMs < CONTENT_DURATION_THRESHOLD_MS ->
                PREFERRED_MS.coerceAtMost(lastUsable)
            else ->
                percentOf(durationMs, CONTENT_PERCENT)
                    .coerceIn(CONTENT_MIN_MS, CONTENT_MAX_MS)
                    .coerceAtMost(lastUsable)
        }
    }

    private fun percentOf(durationMs: Long, percent: Int): Long =
        durationMs * percent.toLong() / 100L
}
