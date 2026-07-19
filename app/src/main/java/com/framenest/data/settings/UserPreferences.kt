package com.framenest.data.settings

import android.content.Context
import java.util.Locale

/**
 * Non-sensitive user preferences (SharedPreferences).
 * Passwords never live here — only language tags and UI flags.
 */
class UserPreferences(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Preferred subtitle language tags, most preferred first.
     * Empty means "follow system locale" (see [SubtitleLanguagePrefs]).
     */
    fun subtitleLanguageTags(): List<String> {
        val raw = prefs.getString(KEY_SUBTITLE_LANGS, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
    }

    fun setSubtitleLanguageTags(tags: List<String>) {
        val cleaned = tags.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        prefs.edit().putString(KEY_SUBTITLE_LANGS, cleaned.joinToString(",")).apply()
    }

    /** Preset id for Settings UI: system | zh | en | custom */
    fun subtitleLanguagePreset(): String =
        prefs.getString(KEY_SUBTITLE_PRESET, PRESET_SYSTEM) ?: PRESET_SYSTEM

    fun setSubtitleLanguagePreset(preset: String) {
        prefs.edit().putString(KEY_SUBTITLE_PRESET, preset).apply()
        when (preset) {
            PRESET_SYSTEM -> setSubtitleLanguageTags(emptyList())
            PRESET_ZH -> setSubtitleLanguageTags(listOf("zh", "chi", "chs", "cht"))
            PRESET_EN -> setSubtitleLanguageTags(listOf("en", "eng"))
            else -> Unit // custom leaves tags as-is
        }
    }

    /**
     * Max concurrent list-thumbnail extract jobs (1–2). Default 1 per architecture.
     */
    fun thumbnailConcurrency(): Int =
        prefs.getInt(KEY_THUMB_CONCURRENCY, DEFAULT_THUMB_CONCURRENCY)
            .coerceIn(MIN_THUMB_CONCURRENCY, MAX_THUMB_CONCURRENCY)

    fun setThumbnailConcurrency(value: Int) {
        prefs.edit()
            .putInt(
                KEY_THUMB_CONCURRENCY,
                value.coerceIn(MIN_THUMB_CONCURRENCY, MAX_THUMB_CONCURRENCY),
            )
            .apply()
    }

    /** SMB browse directory layout: list (default) or multi-column grid. */
    fun browseLayoutMode(): BrowseLayoutMode =
        BrowseLayoutMode.fromStorage(prefs.getString(KEY_BROWSE_LAYOUT, null))

    fun setBrowseLayoutMode(mode: BrowseLayoutMode) {
        prefs.edit().putString(KEY_BROWSE_LAYOUT, mode.storageValue()).apply()
    }

    /** Large ASR/MT packs default to Wi-Fi/Ethernet to avoid surprise data usage. */
    fun allowMeteredModelDownloads(): Boolean =
        prefs.getBoolean(KEY_ALLOW_METERED_MODEL_DOWNLOADS, false)

    fun setAllowMeteredModelDownloads(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_METERED_MODEL_DOWNLOADS, allow).apply()
    }

    companion object {
        private const val PREFS_NAME = "framenest_user_prefs"
        private const val KEY_SUBTITLE_LANGS = "subtitle_language_tags"
        private const val KEY_SUBTITLE_PRESET = "subtitle_language_preset"
        private const val KEY_THUMB_CONCURRENCY = "thumbnail_concurrency"
        private const val KEY_BROWSE_LAYOUT = "browse_layout_mode"
        private const val KEY_ALLOW_METERED_MODEL_DOWNLOADS = "allow_metered_model_downloads"

        const val PRESET_SYSTEM = "system"
        const val PRESET_ZH = "zh"
        const val PRESET_EN = "en"

        const val MIN_THUMB_CONCURRENCY = 1
        const val MAX_THUMB_CONCURRENCY = 2
        const val DEFAULT_THUMB_CONCURRENCY = 1
    }
}
