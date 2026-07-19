package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.ListenTranslateCue
import kotlin.math.abs
import kotlin.math.max

/** Playback-first scheduling for cached listen-translate windows. */
internal object ListenPrefetchPolicy {
    const val FAST_LOOKAHEAD_MS = 30_000L
    const val BALANCED_LOOKAHEAD_MS = 12_000L

    private const val FAST_REALTIME_FACTOR = 0.65
    private const val MAX_PREFETCH_REALTIME_FACTOR = 1.0
    private const val EWMA_NEW_SAMPLE_WEIGHT = 0.25

    /**
     * Realtime factor = processing milliseconds / decoded audio milliseconds.
     * Below 1 means the pipeline runs faster than playback.
     */
    fun lookAheadMs(
        playing: Boolean,
        buffering: Boolean,
        realtimeFactor: Double?,
    ): Long {
        if (!playing || buffering) return 0L
        return when {
            realtimeFactor == null -> BALANCED_LOOKAHEAD_MS
            realtimeFactor <= FAST_REALTIME_FACTOR -> FAST_LOOKAHEAD_MS
            realtimeFactor <= MAX_PREFETCH_REALTIME_FACTOR -> BALANCED_LOOKAHEAD_MS
            else -> 0L
        }
    }

    fun updateRealtimeFactor(
        previous: Double?,
        elapsedMs: Long,
        audioMs: Long,
    ): Double {
        if (audioMs <= 0L) return previous ?: 1.0
        val sample = (elapsedMs.coerceAtLeast(0L).toDouble() / audioMs.toDouble())
            .coerceIn(0.0, 10.0)
        return previous?.let {
            it * (1.0 - EWMA_NEW_SAMPLE_WEIGHT) + sample * EWMA_NEW_SAMPLE_WEIGHT
        } ?: sample
    }

    /** Current gap first, then the first future gap inside the adaptive horizon. */
    fun nextWindow(
        cues: List<ListenTranslateCue>,
        positionMs: Long,
        durationMs: Long,
        windowMs: Long,
        lookAheadMs: Long,
    ): Pair<Long, Long>? {
        val current = ListenTranslateWindows.windowContaining(positionMs, windowMs, durationMs)
        if (ListenTranslateWindows.needsFill(cues, current.first, current.second)) return current
        if (lookAheadMs <= 0L) return null

        val horizonEnd = if (durationMs > 0L) {
            (positionMs.coerceAtLeast(0L) + lookAheadMs).coerceAtMost(durationMs)
        } else {
            positionMs.coerceAtLeast(0L) + lookAheadMs
        }
        var start = current.second
        while (start < horizonEnd) {
            val end = if (durationMs > 0L) {
                (start + windowMs).coerceAtMost(durationMs)
            } else {
                start + windowMs
            }
            if (end <= start) return null
            if (ListenTranslateWindows.needsFill(cues, start, end)) return start to end
            start = end
        }
        return null
    }

    fun isFarSeek(previousMs: Long, currentMs: Long, windowMs: Long): Boolean =
        abs(currentMs - previousMs) >= max(windowMs * 2L, 6_000L)
}

/** Audio selection is part of cache validity, without retaining credential material. */
internal object ListenCacheVariant {
    fun contentKey(baseContentKey: String, audioTrackOrdinal: Int?): String {
        val base = baseContentKey.trim()
        // MediaExtractor defaults to the first audio stream when VLC track metadata
        // has not arrived yet, so null and ordinal 0 must share the same cache.
        val track = (audioTrackOrdinal ?: 0).coerceAtLeast(0).toString()
        return if (base.isBlank()) "audio=$track" else "$base|audio=$track"
    }
}
