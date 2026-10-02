package com.framenest.feature.player

import com.framenest.data.thumbnail.ThumbnailKey
import kotlin.math.abs

/**
 * Where a scrub preview image comes from.
 *
 * Frames are grouped into fixed buckets so a drag can paint the nearest
 * decoded picture immediately, while a single background extractor fills the
 * bucket under the finger and a small neighborhood around playback.
 */
internal object ScrubPreviewPlan {
    const val BUCKET_MS: Long = 10_000L
    const val NEIGHBOR_RADIUS: Int = 2
    const val SPREAD_COUNT: Int = 12
    const val MAX_MEMORY_FRAMES: Int = 36
    const val MAX_DISK_BYTES: Long = 32L * 1024L * 1024L
    const val CACHE_DIR: String = "scrub-previews"

    fun bucketStartMs(positionMs: Long): Long {
        val safe = positionMs.coerceAtLeast(0L)
        return safe - (safe % BUCKET_MS)
    }

    /**
     * A fast seek lands on a keyframe near [targetMs]. The opening frame must
     * not satisfy a later bucket.
     */
    fun frameLanded(targetMs: Long, decodedMs: Long, slopMs: Long = BUCKET_MS): Boolean {
        if (targetMs < 0L || decodedMs < 0L) return false
        return decodedMs + slopMs >= targetMs && decodedMs <= targetMs + slopMs
    }

    /**
     * Drop a stale extract when the finger has moved to a bucket that still
     * needs a frame. A bucket that is already ready or already missed stays
     * put, so the scheduler does not spin on the same request.
     */
    fun shouldAbandonScrubExtract(
        requestedBucketMs: Long,
        focusMs: Long,
        focusBucketReady: Boolean,
        focusBucketFailed: Boolean,
    ): Boolean {
        if (focusMs < 0L) return false
        val focusBucket = bucketStartMs(focusMs)
        if (focusBucket == requestedBucketMs) return false
        return !focusBucketReady && !focusBucketFailed
    }

    /**
     * The bucket under [focusMs], then one step either side, so the next drag
     * increment is usually already decoded.
     */
    fun neighborhood(durationMs: Long, focusMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val focus = focusMs.coerceIn(0L, durationMs - 1)
        val center = bucketStartMs(focus)
        val out = ArrayList<Long>(1 + NEIGHBOR_RADIUS * 2)
        if (center in 0 until durationMs) out += center
        for (step in 1..NEIGHBOR_RADIUS) {
            val ahead = center + step * BUCKET_MS
            val behind = center - step * BUCKET_MS
            if (ahead in 0 until durationMs) out += ahead
            if (behind in 0 until durationMs) out += behind
        }
        return out
    }

    /**
     * Playback warm order: neighborhood of the current position first, then a
     * coarse strip across the file. Later scrubbing still jumps the queue.
     */
    fun playbackOrder(durationMs: Long, anchorMs: Long): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val seen = LinkedHashSet<Long>()
        seen += neighborhood(durationMs, anchorMs)
        val buckets = bucketCount(durationMs)
        val count = minOf(SPREAD_COUNT, buckets)
        for (index in 0 until count) {
            val center = (((index + 0.5) / count) * durationMs)
                .toLong()
                .coerceIn(0L, durationMs - 1)
            val bucket = bucketStartMs(center)
            if (bucket in 0 until durationMs) seen += bucket
        }
        return seen.toList()
    }

    /**
     * Next bucket to decode, or null when there is nothing useful to do.
     * A finger target always wins over background warming. Buffering pauses
     * warming so preview extraction does not compete with playback.
     */
    fun nextExtractMs(
        durationMs: Long,
        anchorMs: Long,
        scrubTargetMs: Long?,
        buffering: Boolean,
        active: Boolean,
        ready: Set<Long>,
        failed: Set<Long>,
    ): Long? {
        if (durationMs <= 0L) return null
        if (scrubTargetMs != null) {
            return neighborhood(durationMs, scrubTargetMs)
                .firstOrNull { it !in ready && it !in failed }
        }
        if (!active || buffering) return null
        return playbackOrder(durationMs, anchorMs)
            .firstOrNull { it !in ready && it !in failed }
    }

    /** Closest decoded bucket to [targetMs]. Equal distance prefers the earlier one. */
    fun nearestReadyMs(targetMs: Long, ready: Collection<Long>): Long? {
        if (ready.isEmpty()) return null
        val bucket = bucketStartMs(targetMs)
        return ready.minWithOrNull(compareBy<Long> { abs(it - bucket) }.thenBy { it })
    }

    fun retain(
        keys: Collection<Long>,
        anchorMs: Long,
        scrubTargetMs: Long?,
        maxEntries: Int,
    ): Set<Long> {
        if (maxEntries <= 0) return emptySet()
        if (keys.size <= maxEntries) return keys.toSet()
        val focus = scrubTargetMs ?: anchorMs
        return keys
            .sortedWith(compareBy<Long> { abs(it - focus) }.thenBy { it })
            .take(maxEntries)
            .toSet()
    }

    /**
     * Cache key from a listing that already knows size and modification time.
     * Null when either value is missing, so the caller still stats the file.
     */
    fun digestFromKnownContent(
        serverId: String,
        share: String,
        path: String,
        sizeBytes: Long?,
        modifiedTimeMs: Long?,
    ): String? {
        if (sizeBytes == null || sizeBytes < 0L) return null
        if (modifiedTimeMs == null || modifiedTimeMs < 0L) return null
        return cacheDigest(serverId, share, path, sizeBytes, modifiedTimeMs)
    }

    fun cacheDigest(
        serverId: String,
        share: String,
        path: String,
        sizeBytes: Long,
        modifiedTimeMs: Long,
    ): String = ThumbnailKey(
        serverId = serverId,
        share = share,
        path = path,
        sizeBytes = sizeBytes.coerceAtLeast(0L),
        modifiedTimeMs = modifiedTimeMs.coerceAtLeast(0L),
    ).digest()

    fun filesToEvict(
        files: List<CacheFile>,
        incomingBytes: Long,
        maxBytes: Long,
    ): List<String> {
        var total = files.sumOf { it.bytes } + incomingBytes.coerceAtLeast(0L)
        if (total <= maxBytes) return emptyList()
        val victims = ArrayList<String>()
        for (file in files.sortedWith(compareBy<CacheFile> { it.modifiedAtMs }.thenBy { it.name })) {
            if (total <= maxBytes) break
            victims += file.name
            total -= file.bytes
        }
        return victims
    }

    private fun bucketCount(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val count = (durationMs + BUCKET_MS - 1) / BUCKET_MS
        return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    data class CacheFile(
        val name: String,
        val bytes: Long,
        val modifiedAtMs: Long,
    )
}
