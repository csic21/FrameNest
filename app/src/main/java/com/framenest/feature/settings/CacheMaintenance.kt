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
 * Clears explicitly selected cache domains.
 * Never touches credentials. General disk caches, listen-translate Room rows and
 * on-device model packs stay separate so a low-risk cleanup never deletes costly
 * generated captions or downloaded models.
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

    suspend fun clearGeneralCaches(): CacheClearResult = withContext(ioDispatcher) {
        val domains = CacheClearPolicy.domainsFor(CacheClearTarget.General)
        val beforeDisk = approximateDiskCacheBytesOnCurrentThread()
        if (CacheDomain.Thumbnails in domains) thumbnailRepository.clearCache()
        if (CacheDomain.SubtitleFiles in domains) {
            val subtitleDir = File(context.cacheDir, "subtitles")
            runCatching { subtitleDir.listFiles()?.forEach { it.deleteRecursively() } }
        }
        if (CacheDomain.Diagnostics in domains) {
            val diagDir = File(context.cacheDir, "diagnostics")
            runCatching { diagDir.listFiles()?.forEach { it.deleteRecursively() } }
            // In-process diagnostic ring buffer is not counted in disk size.
            DiagnosticLog.clear()
        }
        val afterDisk = approximateDiskCacheBytesOnCurrentThread()
        CacheClearResult(
            freedApproxBytes = (beforeDisk - afterDisk).coerceAtLeast(0L),
            remainingApproxBytes = afterDisk,
        )
    }

    /** Clear only listen-translate Room rows (not model packs). */
    suspend fun clearListenTranslateCache(): CacheClearResult = withContext(ioDispatcher) {
        check(
            CacheDomain.ListenTranslate in
                CacheClearPolicy.domainsFor(CacheClearTarget.ListenTranslate),
        )
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
        check(CacheDomain.Models in CacheClearPolicy.domainsFor(CacheClearTarget.Models))
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

internal enum class CacheClearTarget { General, ListenTranslate, Models }

internal enum class CacheDomain { Thumbnails, SubtitleFiles, Diagnostics, ListenTranslate, Models }

/** Pure contract used by Settings to keep destructive cache actions non-overlapping. */
internal object CacheClearPolicy {
    fun domainsFor(target: CacheClearTarget): Set<CacheDomain> = when (target) {
        CacheClearTarget.General -> setOf(
            CacheDomain.Thumbnails,
            CacheDomain.SubtitleFiles,
            CacheDomain.Diagnostics,
        )
        CacheClearTarget.ListenTranslate -> setOf(CacheDomain.ListenTranslate)
        CacheClearTarget.Models -> setOf(CacheDomain.Models)
    }
}
