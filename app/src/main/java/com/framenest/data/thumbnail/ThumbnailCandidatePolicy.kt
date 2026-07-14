package com.framenest.data.thumbnail

/**
 * Pure policy for which timestamps to sample when extracting a list thumbnail.
 *
 * - Prefer **10s** for typical-length videos.
 * - Short videos use ~**20%** of duration (still capped below duration).
 * - On failure / black frame, try a **finite** candidate list — never infinite.
 */
object ThumbnailCandidatePolicy {
    /** Preferred seek for non-short videos. */
    const val PREFERRED_MS: Long = 10_000L

    /** Videos shorter than this use the 20%-of-duration rule. */
    const val SHORT_DURATION_THRESHOLD_MS: Long = 15_000L

    /** Fraction of duration used for short videos. */
    const val SHORT_FRACTION: Double = 0.20

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
            (durationMs * 0.25).toLong(),
            (durationMs * 0.50).toLong(),
            minOf(5_000L, durationMs / 2L),
            0L,
        ).map { it.coerceIn(0L, lastUsable) }

        return (listOf(preferred) + extras)
            .distinct()
            .take(MAX_CANDIDATES)
    }

    fun preferredTimestampMs(durationMs: Long): Long {
        if (durationMs <= 0L) return PREFERRED_MS
        return if (durationMs < SHORT_DURATION_THRESHOLD_MS) {
            (durationMs * SHORT_FRACTION).toLong()
        } else {
            PREFERRED_MS.coerceAtMost(durationMs - 1L)
        }
    }
}
