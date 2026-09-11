package com.framenest.feature.player

/**
 * Pure playback-clock formatting shared by the product player UI.
 *
 * Top-tier players always render long-form content as `h:mm:ss` once the
 * duration reaches one hour. The previous local `"%d:%02d"` formatter showed
 * a 90-minute movie as `90:00`, which misreads at a glance and breaks the
 * `pos / dur` status row on NAS movie libraries.
 *
 * Implemented without [String.format] on purpose: the time row recomposes on
 * every position tick while decoding, and manual digit building avoids the
 * locale-sensitive format machinery on that hot path. Output is ASCII digits
 * only, so it is locale-independent by construction.
 */
internal object PlayerTimeFormat {
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    /**
     * Format a clock value as `m:ss`, or `h:mm:ss` once it reaches one hour.
     * Negative inputs are clamped to zero (unknown / not-started clocks).
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / MS_PER_SECOND).coerceAtLeast(0L)
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        return if (totalSeconds >= SECONDS_PER_HOUR) {
            val hours = totalSeconds / SECONDS_PER_HOUR
            val minutes = totalMinutes % SECONDS_PER_MINUTE
            buildString {
                append(hours)
                append(':')
                appendTwoDigits(minutes)
                append(':')
                appendTwoDigits(seconds)
            }
        } else {
            buildString {
                append(totalMinutes)
                append(':')
                appendTwoDigits(seconds)
            }
        }
    }

    /**
     * Signed variant for gesture deltas (`+0:10` / `−0:10`). Uses U+2212 for
     * the minus sign to match the existing skip-indicator typography.
     */
    fun formatSignedDelta(ms: Long): String {
        val sign = if (ms < 0L) "−" else "+"
        return sign + formatDuration(kotlin.math.abs(ms))
    }

    private fun StringBuilder.appendTwoDigits(value: Long) {
        val v = value.coerceIn(0L, 99L).toInt()
        if (v < 10) append('0')
        append(v)
    }
}
