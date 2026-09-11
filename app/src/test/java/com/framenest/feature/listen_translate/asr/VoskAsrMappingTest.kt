package com.framenest.feature.listen_translate.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskAsrMappingTest {

    @Test
    fun voskResult_convertsToEngineNeutralShapeVerbatim() {
        val recognition = VoskRecognition(
            text = "hello world",
            words = listOf(
                VoskWord("hello", startMs = 100L, endMs = 400L),
                VoskWord("world", startMs = 500L, endMs = 900L),
            ),
        ).toAsrRecognition()

        assertEquals("hello world", recognition.text)
        assertEquals(
            listOf(
                AsrWord("hello", startMs = 100L, endMs = 400L),
                AsrWord("world", startMs = 500L, endMs = 900L),
            ),
            recognition.words,
        )
    }

    @Test
    fun emptyVoskResult_convertsToEmpty() {
        val recognition = VoskRecognition.EMPTY.toAsrRecognition()

        assertEquals("", recognition.text)
        assertTrue(recognition.words.isEmpty())
    }
}
