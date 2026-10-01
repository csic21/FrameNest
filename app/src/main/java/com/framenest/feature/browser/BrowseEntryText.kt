package com.framenest.feature.browser

import java.util.Locale

/** Plain-text details shown beside a folder listing, independent of Compose. */
internal object BrowseEntryText {
    fun formatBytes(sizeBytes: Long?): String? {
        if (sizeBytes == null || sizeBytes <= 0L) return null
        if (sizeBytes < 1024L) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val seconds = totalSec % 60L
        val minutes = (totalSec / 60L) % 60L
        val hours = totalSec / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }
}
