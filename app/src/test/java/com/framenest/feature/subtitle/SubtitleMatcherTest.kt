package com.framenest.feature.subtitle

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class SubtitleMatcherMatchTest {

    @Test
    fun videoBasename_stripsLastExtension() {
        assertEquals("Movie", SubtitleMatcher.videoBasename("Movie.mkv"))
        assertEquals("Movie.final", SubtitleMatcher.videoBasename("Movie.final.mp4"))
        assertEquals("noext", SubtitleMatcher.videoBasename("noext"))
    }

    @Test
    fun isMatch_acceptsBasenameAndTaggedSidecars() {
        assertTrue(SubtitleMatcher.isMatch("Movie.mkv", "Movie.srt"))
        assertTrue(SubtitleMatcher.isMatch("Movie.mkv", "Movie.zh.srt"))
        assertTrue(SubtitleMatcher.isMatch("Movie.mkv", "Movie.chs.ass"))
        assertTrue(SubtitleMatcher.isMatch("Movie.mkv", "Movie.en.ssa"))
        assertTrue(SubtitleMatcher.isMatch("Movie.mkv", "Movie.zh-CN.vtt"))
        assertTrue(SubtitleMatcher.isMatch("Movie.MKV", "movie.SRT"))
    }

    @Test
    fun isMatch_rejectsNonPrefixAndNonSubtitle() {
        assertFalse(SubtitleMatcher.isMatch("Movie.mkv", "Other.srt"))
        assertFalse(SubtitleMatcher.isMatch("Movie.mkv", "MovieExtra.srt"))
        assertFalse(SubtitleMatcher.isMatch("Movie.mkv", "Movie.mkv"))
        assertFalse(SubtitleMatcher.isMatch("Movie.mkv", "Movie.txt"))
        assertFalse(SubtitleMatcher.isMatch("Movie.mkv", "readme.srt".replace("readme", "Films")))
    }

    @Test
    fun languageTags_extractedAfterBasename() {
        assertEquals(emptyList<String>(), SubtitleMatcher.languageTags("Movie.mkv", "Movie.srt"))
        assertEquals(listOf("zh"), SubtitleMatcher.languageTags("Movie.mkv", "Movie.zh.srt"))
        assertEquals(listOf("zh-CN"), SubtitleMatcher.languageTags("Movie.mkv", "Movie.zh-CN.ass"))
        assertEquals(listOf("chs", "forced"), SubtitleMatcher.languageTags("Movie.mkv", "Movie.chs.forced.srt"))
    }

    @Test
    fun bestMatch_nullWhenEmpty() {
        assertNull(SubtitleMatcher.bestMatch("Movie.mkv", emptyList(), listOf("zh", "en")))
        assertNull(
            SubtitleMatcher.bestMatch(
                "Movie.mkv",
                listOf("Other.srt", "readme.txt"),
                listOf("zh", "en"),
            ),
        )
    }
}

@RunWith(Parameterized::class)
class SubtitleMatcherRankingTest(
    private val caseName: String,
    private val video: String,
    private val files: List<String>,
    private val preferred: List<String>,
    private val expectedOrder: List<String>,
) {

    @Test
    fun ranksInExpectedOrder() {
        val ranked = SubtitleMatcher.rankMatches(video, files, preferred).map { it.fileName }
        assertEquals(caseName, expectedOrder, ranked)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf(
                "language_zh_over_en",
                "Movie.mkv",
                listOf("Movie.en.srt", "Movie.zh.srt", "Movie.srt"),
                listOf("zh", "en"),
                listOf("Movie.zh.srt", "Movie.srt", "Movie.en.srt"),
            ),
            arrayOf(
                "language_en_over_zh",
                "Movie.mkv",
                listOf("Movie.zh.srt", "Movie.en.srt"),
                listOf("en", "zh"),
                listOf("Movie.en.srt", "Movie.zh.srt"),
            ),
            arrayOf(
                "format_ass_before_srt_same_lang",
                "Movie.mkv",
                listOf("Movie.zh.srt", "Movie.zh.ass", "Movie.zh.vtt", "Movie.zh.ssa"),
                listOf("zh", "en"),
                listOf("Movie.zh.ass", "Movie.zh.ssa", "Movie.zh.srt", "Movie.zh.vtt"),
            ),
            arrayOf(
                "chs_alias_matches_zh_pref",
                "Movie.mkv",
                listOf("Movie.en.srt", "Movie.chs.ass"),
                listOf("zh", "en"),
                listOf("Movie.chs.ass", "Movie.en.srt"),
            ),
            arrayOf(
                "exact_basename_mid_when_no_lang_tag",
                "Show.mp4",
                listOf("Show.ja.srt", "Show.srt", "Show.ass"),
                listOf("zh", "en"),
                // No zh/en tag → format then exact: ass (no tag) before srt (no tag); ja last.
                listOf("Show.ass", "Show.srt", "Show.ja.srt"),
            ),
            arrayOf(
                "unrelated_files_filtered",
                "Clip.mkv",
                listOf("Other.srt", "ClipExtra.srt", "Clip.srt", "poster.jpg"),
                listOf("zh", "en"),
                listOf("Clip.srt"),
            ),
            arrayOf(
                "case_insensitive_match_and_stable_name_order",
                "A.mkv",
                listOf("A.EN.srt", "a.en.ass"),
                listOf("en"),
                listOf("a.en.ass", "A.EN.srt"),
            ),
            arrayOf(
                "system_style_zh_CN_prefers_zh_CN_tag",
                "Film.mkv",
                listOf("Film.en.srt", "Film.zh-CN.srt", "Film.zh-TW.srt"),
                listOf("zh-CN", "zh", "en"),
                listOf("Film.zh-CN.srt", "Film.zh-TW.srt", "Film.en.srt"),
            ),
        )
    }
}

class SubtitleLanguagePrefsTest {

    @Test
    fun chineseLocale_listsZhFirst() {
        val prefs = SubtitleLanguagePrefs.preferredLanguages(Locale.SIMPLIFIED_CHINESE)
        assertTrue(prefs.first().startsWith("zh"))
        assertTrue(prefs.any { it == "en" || it.startsWith("en") })
        assertTrue(prefs.any { it == "chs" || it == "zh-hans" || it == "chi" })
    }

    @Test
    fun englishLocale_listsEnFirst_andZhFallback() {
        val prefs = SubtitleLanguagePrefs.preferredLanguages(Locale.US)
        assertTrue(prefs.first().startsWith("en") || prefs.first() == "en-us")
        assertTrue(prefs.any { it.startsWith("zh") || it == "chi" || it == "chs" })
    }
}

class SubtitleMatcherEmbeddedScoreTest {

    @Test
    fun scoresChineseTrackNames() {
        val prefs = listOf("zh", "en")
        assertTrue(
            SubtitleMatcher.embeddedTrackLanguageScore("Track 1 - [Chinese]", prefs) >
                SubtitleMatcher.embeddedTrackLanguageScore("English", prefs),
        )
        assertTrue(
            SubtitleMatcher.embeddedTrackLanguageScore("zh", prefs) >
                SubtitleMatcher.embeddedTrackLanguageScore("und", prefs),
        )
    }
}
