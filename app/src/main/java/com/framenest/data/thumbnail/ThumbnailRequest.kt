package com.framenest.data.thumbnail

import com.framenest.core.model.MediaExtensions
import com.framenest.core.model.RemoteEntry

/**
 * Work item for the single-concurrency thumbnail worker.
 */
data class ThumbnailRequest(
    val key: ThumbnailKey,
    val share: String,
    val path: String,
) {
    companion object {
        fun fromVideoEntry(entry: RemoteEntry): ThumbnailRequest? {
            if (!entry.isFile || !MediaExtensions.isVideo(entry.name)) return null
            if (entry.path.isBlank()) return null
            return ThumbnailRequest(
                key = ThumbnailKey(
                    serverId = entry.serverId,
                    share = entry.share,
                    path = entry.path,
                    sizeBytes = entry.sizeBytes?.coerceAtLeast(0L) ?: 0L,
                    modifiedTimeMs = entry.modifiedTimeMs?.coerceAtLeast(0L) ?: 0L,
                ),
                share = entry.share,
                path = entry.path,
            )
        }
    }
}

/** UI-facing status for a single list row. */
sealed class ThumbnailUiState {
    data object None : ThumbnailUiState()
    data object Loading : ThumbnailUiState()
    data class Ready(val bitmap: android.graphics.Bitmap) : ThumbnailUiState()
    data object Failed : ThumbnailUiState()
}
