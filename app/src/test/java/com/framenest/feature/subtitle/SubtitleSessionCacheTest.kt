package com.framenest.feature.subtitle

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSessionCacheTest {
    @Test fun oldSessionExitCannotDeleteSameMovieInNewSession() {
        val root = Files.createTempDirectory("subtitle-sessions").toFile()
        try {
            val a = SubtitleSessionCache(root, "video-A")
            val b = SubtitleSessionCache(root, "video-B")
            val old = a.write("media", "movie.srt", "movie.srt", "A")
            val current = b.write("media", "movie.srt", "movie.srt", "B")
            a.clear()
            a.clear()
            assertFalse(old.exists())
            assertTrue(current.exists())
            assertEquals("B", current.readText())
            val late = runCatching { a.write("media", "movie.srt", "movie.srt", "late A") }
            assertTrue(late.isFailure)
            assertFalse(old.exists())
            assertEquals("B", current.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun sessionKeysCannotEscapeCacheRoot() {
        for (key in listOf("../other", "/tmp/other", "", "a/b", "a\\b")) {
            assertTrue(runCatching { SubtitleSessionCache(java.io.File("unused"), key) }.isFailure)
        }
    }

    @Test fun defaultCachePreservesReusableClearBehavior() {
        val root = Files.createTempDirectory("subtitle-default").toFile()
        try {
            val cache = SubtitleSessionCache(root, null)
            val old = cache.write("media", "movie.srt", "movie.srt", "A")
            cache.clear()
            assertFalse(old.exists())
            val next = cache.write("media", "movie.srt", "movie.srt", "B")
            assertEquals("B", next.readText())
        } finally { root.deleteRecursively() }
    }
}
