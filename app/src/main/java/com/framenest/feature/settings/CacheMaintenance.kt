package com.framenest.feature.settings

import android.content.Context
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.listen_translate.model.ListenModelManager
import com.framenest.data.thumbnail.ThumbnailRepository
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Clears disk caches, listen-translate Room rows, and on-device model packs.
 * Never touches credentials. Models and DB live only in app-private storage
 * (decision 0005 — uninstall still clears everything).
 *
 * Call clear / approximate methods that touch Room or models from a background
 * dispatcher.
 */
class CacheMaintenance(
    private val context: Context,
    private val thumbnailRepository: ThumbnailRepository,
    private val listenTranslateRepository: ListenTranslateRepository? = null,
    private val listenModelManager: ListenModelManager? = null,
) {
    fun clearAllCaches(): CacheClearResult {
        val before = approximateTotalBytes()
        thumbnailRepository.clearCache()
        val subtitleDir = File(context.cacheDir, "subtitles")
        runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        val diagDir = File(context.cacheDir, "diagnostics")
        runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
        runBlocking {
            listenTranslateRepository?.purgeAll()
            listenModelManager?.deleteAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    /** Clear only listen-translate Room rows (not model packs). */
    fun clearListenTranslateCache(): CacheClearResult {
        val before = approximateTotalBytes()
        runBlocking {
            listenTranslateRepository?.purgeAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    /** Clear only on-device model packs under filesDir/listen_models. */
    fun clearListenModels(): CacheClearResult {
        val before = approximateTotalBytes()
        runBlocking {
            listenModelManager?.deleteAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    fun approximateListenTranslateBytes(): Long =
        runBlocking {
            listenTranslateRepository?.approximateCacheBytes() ?: 0L
        }

    fun approximateListenModelBytes(): Long =
        listenModelManager?.approximateBytes() ?: 0L

    fun approximateTotalBytes(): Long {
        var total = approximateDiskCacheBytes()
        total += approximateListenTranslateBytes()
        total += approximateListenModelBytes()
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
