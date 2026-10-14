package com.framenest.feature.listen_translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapanesePcmPausesTest {
    @Test fun silenceRunUsesActualSampleTime() {
        val pcm = ShortArray(16_000) { if (it in 3_200 until 6_400) 0 else 8_000 }
        assertEquals(listOf(JapaneseQuietRun(1_200, 1_400)), japanesePcmQuietRuns(pcm, 1_000))
    }
    @Test fun briefPauseIsNotPromoted() {
        val pcm = ShortArray(16_000) { if (it in 3_200 until 5_440) 0 else 8_000 }
        assertTrue(japanesePcmQuietRuns(pcm, 0).isEmpty())
    }
    @Test fun quietTailRetainsActualShortDecodeEnd() {
        assertEquals(listOf(JapaneseQuietRun(500, 671)), japanesePcmQuietRuns(ShortArray(2_736), 500))
    }
    @Test fun speechIsNotQuiet() {
        assertTrue(japanesePcmQuietRuns(ShortArray(16_000) { 8_000 }, 0).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWholeMovieInput() {
        japanesePcmQuietRuns(ShortArray(240_001), 0)
    }
}
