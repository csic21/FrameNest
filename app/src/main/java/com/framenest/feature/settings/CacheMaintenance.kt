package com.framenest.feature.settings

import android.content.Context
import com.framenest.core.diagnostics.DiagnosticLog
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.listen_translate.model.ListenModelManager
import com.framenest.data.thumbnail.ThumbnailRepository
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Clears disk caches and listen-translate Room rows.
 * Never touches credentials. On-device model packs are **not** removed by
 * [clearAllCaches] — settings has a dedicated「清除听译模型」control via
 * [clearListenModels] (decision 0005). Uninstall still clears everything.
 *
 * Call clear / approximate methods that touch Room or models from a background
 * dispatcher.
 */
class CacheMaintenance(
    private val context: Context,
    private val thumbnailRepository: ThumbnailRepository,
    private val listenTranslateRepository: ListenTranslateRepository? = null,
    private val listenModelManager: ListenModelManager? = null,
    private val voskModelInstaller: VoskModelInstaller? = null,
) {
    fun clearAllCaches(): CacheClearResult {
        val before = approximateTotalBytes()
        thumbnailRepository.clearCache()
        val subtitleDir = File(context.cacheDir, "subtitles")
        runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        // Exported diagnostic reports on disk.
        val diagDir = File(context.cacheDir, "diagnostics")
        runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
        // In-process diagnostic ring buffer (not counted in disk size).
        DiagnosticLog.clear()
        // Room cues only — leave Vosk / JSON model packs alone.
        runBlocking {
            listenTranslateRepository?.purgeAll()
        }
        val after = approximateTotalBytes()
        return CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            // Cache row shows disk cache only (not model packs).
            remainingApproxBytes = approximateDiskCacheBytes(),
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

    /** Clear only on-device model packs under filesDir/listen_models (JSON + Vosk). */
    fun clearListenModels(): CacheClearResult {
        val before = approximateTotalBytes()
        runBlocking {
            listenModelManager?.deleteAll()
            voskModelInstaller?.deleteAll()
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

    fun approximateListenModelBytes(): Long {
        var total = listenModelManager?.approximateBytes() ?: 0L
        total += voskModelInstaller?.approximateBytes() ?: 0L
        return total
    }

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
