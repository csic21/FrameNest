package com.framenest.feature.subtitle

import com.framenest.core.model.MediaExtensions
import java.util.Locale

/**
 * Pure basename matching + ranking for sidecar subtitles in the current directory.
 *
 * For `Movie.mkv`, matches `Movie.srt`, `Movie.zh.srt`, `Movie.chs.ass`, etc.
 * Non-recursive; callers only pass names from the video's parent directory.
 */
object SubtitleMatcher {

    /** Stem of a video file name without its last extension (`Movie.mkv` → `Movie`). */
    fun videoBasename(videoFileName: String): String {
        val name = videoFileName.trim()
        val ext = MediaExtensions.extensionOf(name)
        if (ext.isEmpty()) return name
        return name.dropLast(ext.length + 1)
    }

    /**
     * Whether [subtitleFileName] is a sidecar for [videoFileName].
     * Stem must equal the video basename or start with `basename.` (case-insensitive).
     */
    fun isMatch(videoFileName: String, subtitleFileName: String): Boolean {
        if (!MediaExtensions.isSubtitle(subtitleFileName)) return false
        val base = videoBasename(videoFileName)
        if (base.isEmpty()) return false
        val stem = subtitleStem(subtitleFileName)
        return stem.equals(base, ignoreCase = true) ||
            stem.startsWith("$base.", ignoreCase = true)
    }

    /** Tokens after the video basename in the subtitle stem (`Movie.zh-CN.forced` → [zh-CN, forced]). */
    fun languageTags(videoFileName: String, subtitleFileName: String): List<String> {
        if (!isMatch(videoFileName, subtitleFileName)) return emptyList()
        val base = videoBasename(videoFileName)
        val stem = subtitleStem(subtitleFileName)
        if (stem.equals(base, ignoreCase = true)) return emptyList()
        val prefix = "$base."
        if (!stem.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            return emptyList()
        }
        return stem.substring(prefix.length)
            .split('.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Filter directory names to matching subtitle files and sort by auto-select priority.
     *
     * Priority (higher first):
     * 1. Preferred language match (earlier in [preferredLanguages] wins)
     * 2. Extension: ass > ssa > srt > vtt
     * 3. Fewer language tags (exact `Movie.srt` before long tag chains)
     * 4. Case-insensitive file name
     */
    fun rankMatches(
        videoFileName: String,
        directoryFileNames: List<String>,
        preferredLanguages: List<String>,
    ): List<RankedSidecar> {
        val preferred = preferredLanguages.map { normalizeLangToken(it) }.filter { it.isNotEmpty() }
        return directoryFileNames
            .asSequence()
            .filter { isMatch(videoFileName, it) }
            .map { name ->
                val ext = MediaExtensions.extensionOf(name)
                val tags = languageTags(videoFileName, name)
                RankedSidecar(
                    fileName = name,
                    extension = ext,
                    languageTags = tags,
                    languageScore = languageScore(tags, preferred),
                    formatScore = formatScore(ext),
                )
            }
            .sortedWith(
                compareByDescending<RankedSidecar> { it.languageScore }
                    .thenByDescending { it.formatScore }
                    .thenBy { it.languageTags.size }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName },
            )
            .toList()
    }

    /** Best auto-select candidate, or null if none. */
    fun bestMatch(
        videoFileName: String,
        directoryFileNames: List<String>,
        preferredLanguages: List<String>,
    ): RankedSidecar? =
        rankMatches(videoFileName, directoryFileNames, preferredLanguages).firstOrNull()

    /**
     * Score an embedded track display name against preferred languages.
     * Higher is better; 0 means no recognized preference match.
     */
    fun embeddedTrackLanguageScore(trackName: String, preferredLanguages: List<String>): Int {
        val preferred = preferredLanguages.map { normalizeLangToken(it) }.filter { it.isNotEmpty() }
        if (preferred.isEmpty()) return 0
        val trackTokens = EMBEDDED_TRACK_TOKEN
            .findAll(trackName.lowercase(Locale.ROOT).replace('_', '-'))
            .map { normalizeLangToken(it.value) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (trackTokens.isEmpty()) return 0
        preferred.forEachIndexed { index, pref ->
            if (trackTokens.any { token -> token in tokenAliases(pref) }) {
                return 1000 - index * 10
            }
        }
        return 0
    }

    private fun subtitleStem(subtitleFileName: String): String {
        val ext = MediaExtensions.extensionOf(subtitleFileName)
        if (ext.isEmpty()) return subtitleFileName.trim()
        return subtitleFileName.trim().dropLast(ext.length + 1)
    }

    private fun languageScore(tags: List<String>, preferredNormalized: List<String>): Int {
        if (preferredNormalized.isEmpty()) {
            return if (tags.isEmpty()) 50 else 0
        }
        if (tags.isEmpty()) {
            // Exact basename match (no language tag): just under the top preferred language,
            // but above lower fallback languages (common for untagged `Movie.srt`).
            return preferredNormalized.size * 1000 - 100
        }
        val normalizedTags = tags.map { normalizeLangToken(it) }
        var best = 0
        preferredNormalized.forEachIndexed { index, pref ->
            val tier = (preferredNormalized.size - index) * 1000
            val quality = matchQuality(normalizedTags, pref)
            if (quality > 0) {
                best = maxOf(best, tier + quality)
            }
        }
        return best
    }

    /**
     * How well [tags] match a single preferred token.
     * exact=100, base/compatible region=80, alias=60, same macro-language other region=40.
     */
    private fun matchQuality(tags: List<String>, pref: String): Int {
        if (tags.any { it == pref }) return 100

        val prefBase = pref.substringBefore('-')
        val prefRegion = pref.substringAfter('-', missingDelimiterValue = "")

        for (tag in tags) {
            val tagBase = tag.substringBefore('-')
            val tagRegion = tag.substringAfter('-', missingDelimiterValue = "")
            when {
                tag == prefBase || pref == tagBase -> {
                    // Prefer bare-base when either side has no region.
                    return if (prefRegion.isEmpty() || tagRegion.isEmpty()) 80 else 40
                }
                tag.startsWith("$prefBase-") || pref.startsWith("$tagBase-") -> {
                    return if (prefRegion.isEmpty() ||
                        tagRegion.isEmpty() ||
                        tagRegion == prefRegion
                    ) {
                        80
                    } else if (isChineseFamily(tagBase) && isChineseFamily(prefBase)) {
                        40
                    } else if (tagBase == prefBase) {
                        40
                    } else {
                        0
                    }
                }
            }
        }

        val aliases = tokenAliases(prefBase)
        if (tags.any { tag ->
                val tagBase = tag.substringBefore('-')
                tag in aliases || tagBase in aliases
            }
        ) {
            return 60
        }
        return 0
    }

    private fun isChineseFamily(token: String): Boolean {
        val t = token.lowercase(Locale.ROOT)
        return t == "zh" || t == "chi" || t == "zho" || t == "chinese" ||
            t == "chs" || t == "cht" || t == "cn" || t == "tw" || t == "hk"
    }

    private fun formatScore(extension: String): Int =
        when (extension.lowercase(Locale.ROOT)) {
            "ass" -> 40
            "ssa" -> 30
            "srt" -> 20
            "vtt" -> 10
            else -> 0
        }

    internal fun normalizeLangToken(raw: String): String =
        raw.trim()
            .lowercase(Locale.ROOT)
            .replace('_', '-')
            .replace(' ', '-')

    /**
     * Common aliases so `zh`, `chi`, `chs`, `chinese` compare as related.
     */
    internal fun tokenAliases(normalized: String): Set<String> {
        val base = normalized.substringBefore('-')
        val set = linkedSetOf(normalized, base)
        when (base) {
            "zh", "chi", "zho", "chinese", "chs", "cht", "cn", "tw", "hk" -> {
                set += listOf("zh", "chi", "zho", "chinese", "chs", "cht", "cn", "zh-cn", "zh-hans", "zh-hant", "zh-tw", "zh-hk")
            }
            "en", "eng", "english" -> {
                set += listOf("en", "eng", "english")
            }
            "ja", "jpn", "jp", "japanese" -> {
                set += listOf("ja", "jpn", "jp", "japanese")
            }
            "ko", "kor", "kr", "korean" -> {
                set += listOf("ko", "kor", "kr", "korean")
            }
        }
        return set
    }

    private val EMBEDDED_TRACK_TOKEN = Regex("""[\p{L}\p{N}]+(?:-[\p{L}\p{N}]+)*""")
}

/**
 * A sidecar subtitle candidate after ranking.
 */
data class RankedSidecar(
    val fileName: String,
    val extension: String,
    val languageTags: List<String>,
    val languageScore: Int,
    val formatScore: Int,
)
