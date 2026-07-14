package com.framenest.feature.settings

import android.content.Context
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.thumbnail.ThumbnailRepository
import java.io.File

/**
 * Clears disk caches and optional Room listen-translate rows.
 * Never touches credentials. Listen-translate data lives only in app-private Room
 * (decision 0005 — uninstall still clears everything).
 *
 * Call [clearAllCaches] / [clearListenTranslateCache] / [approximateListenTranslateBytes]
 * from a background dispatcher (Room is not main-thread safe here).
 */
class CacheMaintenance(
    private val context: Context,
    private val thumbnailRepository: ThumbnailRepository,
    private val listenTranslateRepository: ListenTranslateRepository? = null,
) {
    fun clearAllCaches(): CacheClearResult {
        val before = approximateTotalBytes()
        thumbnailRepository.clearCache()
        val subtitleDir = File(context.cacheDir, "subtitles")
        runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        val diagDir = File(context.cacheDir, "diagnostics")
        runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
        // Room purge — caller should already be on Dispatchers.IO.
        kotlinx.coroutines.runBlocking {
            listenTranslateRepository?.purgeAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    /** Clear only listen-translate Room rows (not thumbnails / subtitle temps). */
    fun clearListenTranslateCache(): CacheClearResult {
        val before = approximateTotalBytes()
        kotlinx.coroutines.runBlocking {
            listenTranslateRepository?.purgeAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    /** Approximate Room text payload; call from IO. */
    fun approximateListenTranslateBytes(): Long =
        kotlinx.coroutines.runBlocking {
            listenTranslateRepository?.approximateCacheBytes() ?: 0L
        }

    /**
     * Disk caches + listen-translate Room estimate.
     * Prefer calling from a background thread when Room is included.
     */
    fun approximateTotalBytes(): Long {
        var total = approximateDiskCacheBytes()
        total += approximateListenTranslateBytes()
        return total
    }

    fun approximateDiskCacheBytes(): Long {
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
