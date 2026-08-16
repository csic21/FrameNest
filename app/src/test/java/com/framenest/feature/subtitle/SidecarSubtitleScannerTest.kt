package com.framenest.feature.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarSubtitleScannerTest {
    @Test
    fun optionsFromFileNames_usesSharedDirectorySnapshotAndRanksMatches() {
        val options = SidecarSubtitleScanner().optionsFromFileNames(
            videoPath = "Movies/Movie.mkv",
            directoryFileNames = listOf(
                "Movie.mkv",
                "Movie.en.ass",
                "Movie.zh-CN.srt",
                "Other.zh.srt",
                ".",
            ),
            preferredLanguages = listOf("zh", "en"),
        )

        assertEquals(listOf("Movie.zh-CN.srt", "Movie.en.ass"), options.map { it.fileName })
        assertEquals(
            listOf("Movies/Movie.zh-CN.srt", "Movies/Movie.en.ass"),
            options.map { it.remotePath },
        )
    }
}
