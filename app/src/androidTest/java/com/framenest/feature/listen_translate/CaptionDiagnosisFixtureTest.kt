package com.framenest.feature.listen_translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Small default-safe parser tests: no generated inputs, models, or networking. */
@RunWith(AndroidJUnit4::class)
class CaptionDiagnosisFixtureTest {
    private fun manifest() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("caption-diagnosis/manifest.json").bufferedReader().use { it.readText() })

    @Test fun fixedReferenceRangesAndPartialLastWindowAreRetained() {
        val fixture = CaptionDiagnosisFixture(manifest())
        assertEquals(4, fixture.units.size)
        assertEquals(16, fixture.windows().size)
        assertEquals(0L to 3_000L, fixture.windows().first())
        assertEquals(45_000L to 47_720L, fixture.windows().last())
        assertEquals(listOf("u1", "u2"), fixture.unitsOverlapping(6_000L, 9_000L))
        assertEquals(listOf("u2"), fixture.unitsOverlapping(8_939L, 12_000L))
        assertFalse(fixture.json.getJSONObject("reference").getBoolean("verbatim_gold"))
    }

    @Test fun invalidProvenanceOverlapPathsAndNonIntegerRangesFailClosed() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("reference").put("verbatim_gold", true) },
            { it.getJSONObject("pcm").put("file", "../escape") },
            { it.getJSONArray("units").getJSONObject(1).put("start_ms", 0) },
            { it.getJSONArray("units").getJSONObject(0).put("start_ms", 0.5) },
            { it.getJSONArray("models").getJSONObject(0).put("sha256", "0".repeat(64)) },
        )
        for (change in changes) assertThrows(IllegalArgumentException::class.java) {
            CaptionDiagnosisFixture(manifest().also(change))
        }
    }

    @Test fun pcmParserRetainsSignedLittleEndianExtremesAndRejectsWrongExtent() {
        val fixture = CaptionDiagnosisFixture(manifest())
        val bytes = ByteArray((fixture.durationMs * 32L).toInt())
        bytes[1] = 0x80.toByte()
        bytes[2] = 0xff.toByte(); bytes[3] = 0x7f.toByte()
        bytes[4] = 0xff.toByte(); bytes[5] = 0xff.toByte()
        val pcm = fixture.decodePcm(bytes)
        assertEquals(Short.MIN_VALUE, pcm[0])
        assertEquals(Short.MAX_VALUE, pcm[1])
        assertEquals((-1).toShort(), pcm[2])
        assertThrows(IllegalArgumentException::class.java) { fixture.decodePcm(bytes.copyOf(bytes.size - 2)) }
    }
}
