package com.framenest.feature.listen_translate.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaModelSpecTest {

    @Test
    fun packFiles_arePinnedToAnImmutableRevision() {
        assertEquals(2, SherpaModelInstaller.FILES.size)
        val byName = SherpaModelInstaller.FILES.associateBy { it.name }
        val weights = byName.getValue(SherpaModelInstaller.MODEL_FILE)
        val tokens = byName.getValue(SherpaModelInstaller.TOKENS_FILE)

        // Both files come from one immutable Hugging Face commit.
        val revision = "2365baeacb507f821a0c8120fcee3d484dba7a07"
        assertTrue(weights.url.contains("/resolve/$revision/model.int8.onnx"))
        assertTrue(tokens.url.contains("/resolve/$revision/tokens.txt"))
        assertTrue(weights.url.startsWith("https://"))
        assertTrue(tokens.url.startsWith("https://"))
    }

    @Test
    fun weightIntegrity_isPinnedBySizeAndSha256() {
        val weights = SherpaModelInstaller.FILES.first {
            it.name == SherpaModelInstaller.MODEL_FILE
        }

        assertEquals(239_233_841L, weights.expectedBytes)
        assertEquals(
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            weights.sha256,
        )
    }

    @Test
    fun tokensSidecar_isPinnedBySize() {
        val tokens = SherpaModelInstaller.FILES.first {
            it.name == SherpaModelInstaller.TOKENS_FILE
        }

        assertEquals(315_894L, tokens.expectedBytes)
    }

    @Test
    fun modelLabel_isDistinctFromAnyVoskTag() {
        // Cache isolation: switching engines must never reuse the other
        // backend's cues (see listen_translate_job asr_model invalidation).
        assertEquals("sherpa-sensevoice-2024-07-17-int8", SherpaModelInstaller.MODEL_VERSION)
        assertNotEquals(
            SherpaModelInstaller.MODEL_VERSION,
            "vosk-${VoskModelInstaller.modelVersionTag("zh")}",
        )
        assertTrue(SherpaModelInstaller.APPROX_PACK_BYTES > 200_000_000L)
    }
}
