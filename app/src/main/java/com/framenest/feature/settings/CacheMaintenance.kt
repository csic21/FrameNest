package com.framenest.feature.settings

import android.content.Context
import com.framenest.data.thumbnail.ThumbnailRepository
import java.io.File

/**
 * Clears disk caches that are safe to wipe without touching credentials or Room.
 */
class CacheMaintenance(
    private val context: Context,
    private val thumbnailRepository: ThumbnailRepository,
) {
    fun clearAllCaches(): CacheClearResult {
        val before = approximateTotalBytes()
        thumbnailRepository.clearCache()
        // External subtitle temp files (same path used by ExternalSubtitleLoader).
        val subtitleDir = File(context.cacheDir, "subtitles")
        runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        // Diagnostic exports (keep buffer in memory).
        val diagDir = File(context.cacheDir, "diagnostics")
        runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    fun approximateTotalBytes(): Long {
        var total = thumbnailRepository.approximateCacheSizeBytes()
        total += dirSize(File(context.cacheDir, "subtitles"))
        total += dirSize(File(context.cacheDir, "diagnostics"))
        return total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}

data class CacheClearResult(
    val freedApproxBytes: Long,
    val remainingApproxBytes: Long,
)
