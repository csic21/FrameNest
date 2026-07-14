package com.framenest.feature.subtitle

import java.util.Locale

/**
 * Default subtitle language preference list for auto-select.
 *
 * Uses the system locale first, then simple zh/en fallbacks required by MVP.
 */
object SubtitleLanguagePrefs {

    /**
     * Ordered preference tokens (most preferred first). Includes common aliases
     * for Chinese/English so sidecar tags like `chs` / `eng` score correctly.
     *
     * @param userPreferred explicit tags from settings; when non-empty they are
     * prepended ahead of system-locale defaults.
     */
    fun preferredLanguages(
        locale: Locale = Locale.getDefault(),
        userPreferred: List<String> = emptyList(),
    ): List<String> {
        val ordered = linkedSetOf<String>()
        userPreferred
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .forEach { ordered += it }

        val language = locale.language.trim().lowercase(Locale.ROOT).ifBlank { "zh" }
        val country = locale.country.trim().lowercase(Locale.ROOT)

        if (country.isNotEmpty()) {
            ordered += "$language-$country"
        }
        ordered += language
        ordered.addAll(aliasesForPrimary(language, country))

        // Simple defaults: always consider zh and en as lower-priority fallbacks.
        if (!ordered.any { it.startsWith("zh") || it == "chi" || it == "chs" || it == "cht" }) {
            ordered += listOf("zh", "chi", "chs")
        }
        if (!ordered.any { it.startsWith("en") || it == "eng" }) {
            ordered += listOf("en", "eng")
        }

        return ordered.toList()
    }

    private fun aliasesForPrimary(language: String, country: String): List<String> =
        when (language) {
            "zh" -> buildList {
                add("chi")
                add("chinese")
                add("zho")
                when (country) {
                    "tw", "hk", "mo" -> {
                        add("cht")
                        add("zh-hant")
                        add("zh-tw")
                        add("zh-hk")
                    }
                    else -> {
                        add("chs")
                        add("zh-hans")
                        add("zh-cn")
                        add("cn")
                    }
                }
            }
            "en" -> listOf("eng", "english")
            "ja" -> listOf("jpn", "jp", "japanese")
            "ko" -> listOf("kor", "kr", "korean")
            else -> emptyList()
        }
}
