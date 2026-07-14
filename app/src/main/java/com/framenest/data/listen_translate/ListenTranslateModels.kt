package com.framenest.data.listen_translate

import com.framenest.core.model.PlaybackIdentity

/**
 * Language pair for a listen-translate job (user-selected, decision 0005).
 */
data class ListenLanguagePair(
    val sourceLang: String,
    val targetLang: String,
) {
    init {
        require(sourceLang.isNotBlank()) { "sourceLang required" }
        require(targetLang.isNotBlank()) { "targetLang required" }
    }

    fun normalized(): ListenLanguagePair =
        ListenLanguagePair(
            sourceLang = sourceLang.trim().lowercase(),
            targetLang = targetLang.trim().lowercase(),
        )
}

enum class ListenTranslateJobStatus {
    Idle,
    Running,
    Partial,
    Complete,
    Failed,
    ;

    companion object {
        fun fromStorage(raw: String): ListenTranslateJobStatus =
            entries.find { it.name.equals(raw, ignoreCase = true) } ?: Idle
    }
}

data class ListenTranslateJob(
    val identity: PlaybackIdentity,
    val languages: ListenLanguagePair,
    val contentKey: String,
    val status: ListenTranslateJobStatus,
    val durationMs: Long,
    val coveredUntilMs: Long,
    val asrModel: String,
    val mtModel: String,
    val updatedAtEpochMs: Long,
    val lastError: String,
)

data class ListenTranslateCue(
    val id: Long,
    val identity: PlaybackIdentity,
    val languages: ListenLanguagePair,
    val startMs: Long,
    val endMs: Long,
    val textSrc: String,
    val textTgt: String,
    val rev: Int,
)

/**
 * Stable content fingerprint: size + modified time (when known).
 * Empty when metadata is unavailable — does not force cache invalidation alone.
 */
object ListenContentKey {
    fun of(sizeBytes: Long?, modifiedTimeMs: Long?): String {
        if (sizeBytes == null && modifiedTimeMs == null) return ""
        val size = sizeBytes ?: -1L
        val mtime = modifiedTimeMs ?: -1L
        return "s${size}_m${mtime}"
    }
}
