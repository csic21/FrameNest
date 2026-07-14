package com.framenest.core.model

/**
 * MVP media extension sets for browser filtering and FN-05/06 recognition.
 *
 * Browser policy (FN-04): show directories, [VIDEO] files, and [SUBTITLE] files.
 * Other files (nfo, jpg, txt, …) are hidden so large media folders stay usable.
 */
object MediaExtensions {
    val VIDEO: Set<String> = setOf(
        "mkv",
        "mp4",
        "avi",
        "mov",
        "m4v",
        "ts",
        "m2ts",
        "mts",
        "webm",
        "wmv",
        "flv",
        "mpeg",
        "mpg",
        "3gp",
        "ogv",
    )

    val SUBTITLE: Set<String> = setOf(
        "srt",
        "ass",
        "ssa",
        "vtt",
    )

    fun extensionOf(fileName: String): String {
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.lastIndex) return ""
        return trimmed.substring(dot + 1).lowercase()
    }

    fun isVideo(fileName: String): Boolean = extensionOf(fileName) in VIDEO

    fun isSubtitle(fileName: String): Boolean = extensionOf(fileName) in SUBTITLE

    /**
     * Whether a non-directory file should appear in the browser list.
     * Directories are always shown by the caller.
     */
    fun isBrowsableMediaFile(fileName: String): Boolean =
        isVideo(fileName) || isSubtitle(fileName)
}
