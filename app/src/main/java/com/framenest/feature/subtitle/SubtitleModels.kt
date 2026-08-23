package com.framenest.feature.subtitle

import com.framenest.player.PlayerTrack

/**
 * UI / session models for subtitle selection (embedded + external sidecars).
 */
data class SubtitleUiState(
    val scanning: Boolean = false,
    val externalOptions: List<ExternalSubtitleOption> = emptyList(),
    /** Key of the active selection: [SubtitleSelectionKeys.OFF], embedded:id, or external:path. */
    val selectedKey: String = SubtitleSelectionKeys.OFF,
    /** Subtitle delay in milliseconds (positive = subtitles later). */
    val delayMs: Long = 0L,
    /** Relative freetype size for libVLC (smaller number → larger text). */
    val fontRelSize: Int = SubtitleFontSizes.NORMAL,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class ExternalSubtitleOption(
    val fileName: String,
    /** Share-relative path to the sidecar on SMB. */
    val remotePath: String,
    val extension: String,
    val languageTags: List<String>,
    /** Local cache path after download; null until loaded. */
    val localPath: String? = null,
) {
    val selectionKey: String get() = SubtitleSelectionKeys.external(remotePath)
}

object SubtitleTrackLists {
    /**
     * Tracks shown under the embedded heading: valid SPU ids that were not
     * created by attaching a sidecar via addSlave.
     */
    fun embedded(tracks: List<PlayerTrack>): List<PlayerTrack> =
        tracks.filter {
            it.kind == PlayerTrack.Kind.Subtitle && it.id >= 0 && !it.isExternalSlave
        }
}

object SubtitleSelectionKeys {
    const val OFF = "off"

    fun embedded(trackId: Int): String = "embedded:$trackId"

    fun external(remotePath: String): String = "external:$remotePath"

    fun isEmbedded(key: String): Boolean = key.startsWith("embedded:")

    fun isExternal(key: String): Boolean = key.startsWith("external:")

    fun embeddedId(key: String): Int? =
        if (isEmbedded(key)) key.removePrefix("embedded:").toIntOrNull() else null

    fun externalPath(key: String): String? =
        if (isExternal(key)) key.removePrefix("external:") else null
}

/**
 * libVLC `--freetype-rel-fontsize` style values (fraction of video height).
 * Smaller ⇒ larger on-screen text.
 */
object SubtitleFontSizes {
    const val SMALL = 20
    const val NORMAL = 16
    const val LARGE = 12
    const val EXTRA_LARGE = 10

    val ALL: List<Int> = listOf(SMALL, NORMAL, LARGE, EXTRA_LARGE)

    fun labelKey(size: Int): String = when (size) {
        SMALL -> "small"
        LARGE -> "large"
        EXTRA_LARGE -> "xlarge"
        else -> "normal"
    }
}
