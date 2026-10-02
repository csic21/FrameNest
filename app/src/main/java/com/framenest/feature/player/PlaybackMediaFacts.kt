package com.framenest.feature.player

import com.framenest.core.model.RemoteEntry
import com.framenest.smb.SmbPathUtils

/**
 * Size and modification time already paid for by a directory listing.
 *
 * Opening a video from that listing can carry the pair into playback so scrub
 * preview does not open another SMB connection just to build a cache key.
 * Recent items and next-episode jumps that never saw a listing still stat.
 */
internal object PlaybackMediaFacts {
    data class Stamp(
        val sizeBytes: Long,
        val modifiedTimeMs: Long,
    )

    private const val MAX_ENTRIES = 128
    private val lock = Any()
    private val stamps = object : LinkedHashMap<String, Stamp>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stamp>?): Boolean =
            size > MAX_ENTRIES
    }

    fun remember(entry: RemoteEntry) {
        if (!entry.isFile) return
        val size = entry.sizeBytes ?: return
        val modified = entry.modifiedTimeMs ?: return
        remember(
            serverId = entry.serverId,
            share = entry.share,
            path = entry.path,
            sizeBytes = size,
            modifiedTimeMs = modified,
        )
    }

    fun remember(
        serverId: String,
        share: String,
        path: String,
        sizeBytes: Long,
        modifiedTimeMs: Long,
    ) {
        if (sizeBytes < 0L || modifiedTimeMs < 0L) return
        val key = key(serverId, share, path) ?: return
        synchronized(lock) {
            stamps[key] = Stamp(sizeBytes, modifiedTimeMs)
        }
    }

    fun lookup(serverId: String, share: String, path: String): Stamp? {
        val key = key(serverId, share, path) ?: return null
        return synchronized(lock) { stamps[key] }
    }

    private fun key(serverId: String, share: String, path: String): String? {
        if (serverId.isBlank() || share.isBlank()) return null
        val normalized = SmbPathUtils.normalizeRelative(path)
        if (normalized.isEmpty()) return null
        return "$serverId\u0000$share\u0000$normalized"
    }
}
