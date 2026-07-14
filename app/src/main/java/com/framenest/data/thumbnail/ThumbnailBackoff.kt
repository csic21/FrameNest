package com.framenest.data.thumbnail

/**
 * Failure / retry backoff for list thumbnail generation.
 *
 * Generation is never retried infinitely: after [MAX_ATTEMPTS] failed
 * generation runs for a key, the key is permanently skipped until the cache
 * key changes (file size/mtime) or the cache is cleared.
 */
data class ThumbnailBackoffState(
    val attempts: Int = 0,
    val nextEligibleAtMs: Long = 0L,
    val permanentlyFailed: Boolean = false,
) {
    fun isEligible(nowMs: Long): Boolean {
        if (permanentlyFailed) return false
        return nowMs >= nextEligibleAtMs
    }
}

object ThumbnailBackoff {
    /** Max full generation runs (each run may try several candidate times). */
    const val MAX_ATTEMPTS: Int = 3

    /** Base delay after the first failed generation. */
    const val BASE_DELAY_MS: Long = 30_000L

    /** Cap so a transient NAS blip can recover without multi-hour waits. */
    const val MAX_DELAY_MS: Long = 10 * 60_000L

    /**
     * Record a failed generation attempt.
     *
     * @param previous prior state for this key (or default)
     * @param nowMs wall clock
     */
    fun afterFailure(previous: ThumbnailBackoffState, nowMs: Long): ThumbnailBackoffState {
        // Already exhausted — do not increment forever (no infinite retry bookkeeping).
        if (previous.permanentlyFailed) return previous
        val nextAttempts = previous.attempts + 1
        if (nextAttempts >= MAX_ATTEMPTS) {
            return ThumbnailBackoffState(
                attempts = nextAttempts.coerceAtMost(MAX_ATTEMPTS),
                nextEligibleAtMs = Long.MAX_VALUE,
                permanentlyFailed = true,
            )
        }
        val delay = delayAfterAttempt(nextAttempts)
        return ThumbnailBackoffState(
            attempts = nextAttempts,
            nextEligibleAtMs = nowMs + delay,
            permanentlyFailed = false,
        )
    }

    fun afterSuccess(): ThumbnailBackoffState = ThumbnailBackoffState()

    /**
     * Exponential backoff: attempt 1 → BASE, attempt 2 → 2×BASE, …
     * (called with the new attempt count after a failure).
     */
    fun delayAfterAttempt(attemptNumber: Int): Long {
        require(attemptNumber >= 1) { "attemptNumber must be >= 1" }
        val shift = (attemptNumber - 1).coerceAtMost(10)
        val raw = BASE_DELAY_MS shl shift
        return minOf(raw, MAX_DELAY_MS)
    }
}
