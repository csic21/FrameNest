package com.framenest.feature.listen_translate

import com.framenest.feature.listen_translate.asr.VoskWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealListenTranslateWindowTest {

    @Test
    fun contextWordsAreAssignedToExactlyOneTargetWindow() {
        val words = listOf(
            VoskWord("before", startMs = 100L, endMs = 300L),
            VoskWord("inside", startMs = 900L, endMs = 1_200L),
            VoskWord("after", startMs = 3_900L, endMs = 4_100L),
        )

        val selected = selectWordsForWindow(
            words = words,
            decodeStartMs = 2_250L,
            windowStartMs = 3_000L,
            windowEndMs = 6_000L,
        )

        assertEquals(listOf("inside"), selected.map { it.text })
    }

    @Test
    fun pcmBlankReason_distinguishesMissingAudioFromSilence() {
        assertEquals(ListenBlankReason.EmptyPcm, listenPcmBlankReason(shortArrayOf()))
        assertEquals(ListenBlankReason.NearSilence, listenPcmBlankReason(ShortArray(16_000)))
        assertNull(listenPcmBlankReason(ShortArray(16_000) { 2_000 }))
    }
}
