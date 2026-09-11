package com.framenest.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrEngineChoiceTest {

    @Test
    fun explicitVosk_isHonored() {
        assertEquals(AsrEngineChoice.VOSK, AsrEngineChoice.fromStorage("vosk"))
        assertEquals(AsrEngineChoice.VOSK, AsrEngineChoice.fromStorage(" VOSK "))
    }

    @Test
    fun sherpaIsTheDefault_forMissingOrUnknownValues() {
        assertEquals(AsrEngineChoice.SHERPA, AsrEngineChoice.fromStorage("sherpa"))
        assertEquals(AsrEngineChoice.SHERPA, AsrEngineChoice.fromStorage(null))
        assertEquals(AsrEngineChoice.SHERPA, AsrEngineChoice.fromStorage(""))
        assertEquals(AsrEngineChoice.SHERPA, AsrEngineChoice.fromStorage("whisper"))
    }

    @Test
    fun storageRoundTrips() {
        AsrEngineChoice.entries.forEach { choice ->
            assertEquals(choice, AsrEngineChoice.fromStorage(choice.storageValue()))
        }
    }
}
