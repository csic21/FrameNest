package com.framenest.feature.player

import com.framenest.core.model.MediaExtensions
import com.framenest.smb.SmbPathUtils

/**
 * Same-directory video playlist for prev/next continuous play (FN-18).
 * Order matches browser: case-insensitive file name.
 */
data class SiblingVideo(
    val path: String,
    val name: String,
)

data class SiblingPlaylist(
    val videos: List<SiblingVideo>,
    /** Index of the currently playing item, or -1 if not found in the list. */
    val currentIndex: Int,
) {
    val previous: SiblingVideo?
        get() = if (currentIndex > 0) videos[currentIndex - 1] else null

    val next: SiblingVideo?
        get() = if (currentIndex >= 0 && currentIndex < videos.lastIndex) {
            videos[currentIndex + 1]
        } else {
            null
        }

    val positionLabel: String?
        get() = if (currentIndex >= 0 && videos.isNotEmpty()) {
            "${currentIndex + 1} / ${videos.size}"
        } else {
            null
        }

    companion object {
        val Empty = SiblingPlaylist(videos = emptyList(), currentIndex = -1)
    }
}

/**
 * Builds a [SiblingPlaylist] from bare file names in the current video's parent
 * directory. Non-video names are dropped.
 */
object SiblingPlaylistFactory {
    fun build(
        currentPath: String,
        directoryFileNames: List<String>,
    ): SiblingPlaylist {
        val current = SmbPathUtils.normalizeRelative(currentPath)
        if (current.isEmpty()) return SiblingPlaylist.Empty
        val parent = SmbPathUtils.parentOf(current)
        val videos = directoryFileNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !SmbPathUtils.isDotEntry(it) }
            .filter { MediaExtensions.isVideo(it) }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { name ->
                SiblingVideo(
                    path = SmbPathUtils.join(parent, name),
                    name = name,
                )
            }
            .toList()
        if (videos.isEmpty()) return SiblingPlaylist.Empty
        val index = videos.indexOfFirst { it.path.equals(current, ignoreCase = true) }
        return SiblingPlaylist(videos = videos, currentIndex = index)
    }
}

/**
 * UI-facing sibling navigation snapshot.
 */
data class SiblingNavUiState(
    val previousPath: String? = null,
    val previousName: String? = null,
    val nextPath: String? = null,
    val nextName: String? = null,
    val positionLabel: String? = null,
    val loading: Boolean = false,
) {
    val hasPrevious: Boolean get() = previousPath != null
    val hasNext: Boolean get() = nextPath != null

    companion object {
        fun from(playlist: SiblingPlaylist, loading: Boolean = false): SiblingNavUiState =
            SiblingNavUiState(
                previousPath = playlist.previous?.path,
                previousName = playlist.previous?.name,
                nextPath = playlist.next?.path,
                nextName = playlist.next?.name,
                positionLabel = playlist.positionLabel,
                loading = loading,
            )
    }
}
