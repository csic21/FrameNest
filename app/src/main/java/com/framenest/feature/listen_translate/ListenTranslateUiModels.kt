package com.framenest.feature.listen_translate

import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import com.framenest.data.listen_translate.ListenTranslateJobStatus

/** How to render listen-translate overlay text. */
enum class ListenDisplayMode {
    SourceOnly,
    TargetOnly,
    Bilingual,
}

/**
 * Curated language tags for manual source/target pickers (decision 0005).
 * Codes are lowercase BCP-47 primary tags.
 */
object ListenTranslateLanguages {
    val ALL: List<String> = listOf("zh", "en", "ja", "ko", "fr", "de", "es")

    fun label(code: String): String =
        when (code.lowercase()) {
            "zh" -> "中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "es" -> "Español"
            else -> code
        }
}

data class ListenTranslateUiState(
    val enabled: Boolean = false,
    val sourceLang: String = "ja",
    val targetLang: String = "zh",
    val displayMode: ListenDisplayMode = ListenDisplayMode.Bilingual,
    val status: ListenTranslateJobStatus = ListenTranslateJobStatus.Idle,
    val coveredUntilMs: Long = 0L,
    val activeCue: ListenTranslateCue? = null,
    val overlayText: String = "",
    val message: String? = null,
    val errorMessage: String? = null,
    val isProcessing: Boolean = false,
) {
    val languages: ListenLanguagePair
        get() = ListenLanguagePair(sourceLang, targetLang).normalized()
}
