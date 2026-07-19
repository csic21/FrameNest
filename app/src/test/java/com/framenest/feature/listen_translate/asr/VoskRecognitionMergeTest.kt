package com.framenest.feature.listen_translate.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class VoskRecognitionMergeTest {

    @Test
    fun completedUtterancesAndFinalTailArePreserved() {
        val first = VoskRecognition(
            text = "こんにちは",
            words = listOf(VoskWord("こんにちは", 0L, 500L)),
        )
        val tail = VoskRecognition(
            text = "世界",
            words = listOf(VoskWord("世界", 600L, 900L)),
        )

        val merged = mergeVoskRecognitions(listOf(first, VoskRecognition.EMPTY, tail))

        assertEquals("こんにちは 世界", merged.text)
        assertEquals(listOf("こんにちは", "世界"), merged.words.map { it.text })
    }

    @Test
    fun emptyPartsRemainEmpty() {
        assertEquals(VoskRecognition.EMPTY, mergeVoskRecognitions(listOf(VoskRecognition.EMPTY)))
    }
}
