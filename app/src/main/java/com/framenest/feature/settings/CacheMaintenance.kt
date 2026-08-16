package com.framenest.feature.settings

import android.content.Context
import com.framenest.core.diagnostics.DiagnosticLog
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.listen_translate.model.ListenModelManager
import com.framenest.data.thumbnail.ThumbnailRepository
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import com.framenest.feature.listen_translate.mt.MlKitTranslationModelCleaner
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears disk caches and listen-translate Room rows.
 * Never touches credentials. On-device model packs are **not** removed by
 * [clearAllCaches] — settings has a dedicated「清除听译模型」control via
 * [clearListenModels] (decision 0005). Uninstall still clears everything.
 *
 * Public operations switch to [ioDispatcher] themselves; callers never need a blocking bridge.
 */
class CacheMaintenance(
    private val context: Context,
    private val thumbnailRepository: ThumbnailRepository,
    private val listenTranslateRepository: ListenTranslateRepository? = null,
    private val listenModelManager: ListenModelManager? = null,
    private val voskModelInstaller: VoskModelInstaller? = null,
    private val mlKitTranslationModelCleaner: MlKitTranslationModelCleaner =
        MlKitTranslationModelCleaner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun usageSnapshot(): CacheUsageSnapshot = withContext(ioDispatcher) {
        CacheUsageSnapshot(
            diskCacheBytes = approximateDiskCacheBytesOnCurrentThread(),
            listenTranslateBytes = approximateListenTranslateBytesOnCurrentThread(),
            listenModelBytes = approximateListenModelBytesOnCurrentThread(),
        )
    }

    suspend fun clearAllCaches(): CacheClearResult = withContext(ioDispatcher) {
        // Model packs are intentionally excluded: this control does not remove them.
        val beforeDisk = approximateDiskCacheBytesOnCurrentThread()
        val beforeListen = approximateListenTranslateBytesOnCurrentThread()
        thumbnailRepository.clearCache()
        val subtitleDir = File(context.cacheDir, "subtitles")
        runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        // Exported diagnostic reports on disk.
        val diagDir = File(context.cacheDir, "diagnostics")
        runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
        // In-process diagnostic ring buffer (not counted in disk size).
        DiagnosticLog.clear()
        // Room cues only — leave Vosk / JSON model packs alone.
        listenTranslateRepository?.purgeAll()
        val afterDisk = approximateDiskCacheBytesOnCurrentThread()
        val afterListen = approximateListenTranslateBytesOnCurrentThread()
        CacheClearResult(
            freedApproxBytes =
                (beforeDisk + beforeListen - afterDisk - afterListen).coerceAtLeast(0L),
            // Cache row shows disk cache only (not model packs).
            remainingApproxBytes = afterDisk,
        )
    }

    /** Clear only listen-translate Room rows (not model packs). */
    suspend fun clearListenTranslateCache(): CacheClearResult = withContext(ioDispatcher) {
        val before = approximateListenTranslateBytesOnCurrentThread()
        listenTranslateRepository?.purgeAll()
        val after = approximateListenTranslateBytesOnCurrentThread()
        CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    /** Clear JSON/Vosk packs and ML Kit translation models from app-private storage. */
    suspend fun clearListenModels(): CacheClearResult = withContext(ioDispatcher) {
        val before = approximateListenModelBytesOnCurrentThread()
        listenModelManager?.deleteAll()
        voskModelInstaller?.deleteAll()
        mlKitTranslationModelCleaner.deleteAll()
        val after = approximateListenModelBytesOnCurrentThread()
        CacheClearResult(
            freedApproxBytes = (before - after).coerceAtLeast(0L),
            remainingApproxBytes = after,
        )
    }

    private suspend fun approximateListenTranslateBytesOnCurrentThread(): Long =
        listenTranslateRepository?.approximateCacheBytes() ?: 0L

    private fun approximateListenModelBytesOnCurrentThread(): Long {
        var total = listenModelManager?.approximateBytes() ?: 0L
        total += voskModelInstaller?.approximateBytes() ?: 0L
        return total
    }

    private fun approximateDiskCacheBytesOnCurrentThread(): Long {
        var total = thumbnailRepository.approximateCacheSizeBytes()
        total += directorySizeBytes(File(context.cacheDir, "subtitles"))
        total += directorySizeBytes(File(context.cacheDir, "diagnostics"))
        return total
    }
}

internal fun directorySizeBytes(directory: File): Long {
    if (!directory.exists()) return 0L
    return directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

data class CacheUsageSnapshot(
    val diskCacheBytes: Long,
    val listenTranslateBytes: Long,
    val listenModelBytes: Long,
)

data class CacheClearResult(
    val freedApproxBytes: Long,
    val remainingApproxBytes: Long,
)
