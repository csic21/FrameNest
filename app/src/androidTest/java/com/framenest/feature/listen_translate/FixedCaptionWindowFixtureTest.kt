package com.framenest.feature.listen_translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** No generated media, model, native call, or download. */
@RunWith(AndroidJUnit4::class)
class FixedCaptionWindowFixtureTest {
    private fun manifest() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("caption-diagnosis/fixed-window-comparison.json").bufferedReader().use { it.readText() })

    @Test fun completeBothFixturesAtBothFixedSizesIncludingTrueEof() {
        val fixture = FixedCaptionWindowFixture(manifest())
        for ((duration, counts) in listOf(47_720L to listOf(16, 8), 113_960L to listOf(38, 19))) {
            for ((index, size) in fixture.windowSizes.withIndex()) {
                val windows = fixture.windows(duration, size)
                assertEquals(counts[index], windows.size)
                assertEquals(0L, windows.first().first)
                assertEquals(duration, windows.last().second)
                assertEquals(duration, windows.sumOf { it.second - it.first })
                windows.zipWithNext().forEach { (left, right) -> assertEquals(left.second, right.first) }
            }
        }
        assertEquals(45_000L to 47_720L, fixture.windows(47_720L, 3_000L).last())
        assertEquals(42_000L to 47_720L, fixture.windows(47_720L, 6_000L).last())
        assertEquals(111_000L to 113_960L, fixture.windows(113_960L, 3_000L).last())
        assertEquals(108_000L to 113_960L, fixture.windows(113_960L, 6_000L).last())
    }

    @Test fun alteredLimitsSourcesAndGoldFailClosed() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("context_padding_ms", 500) },
            { it.put("timeout_ms", 1_200_001) },
            { it.put("window_ms", org.json.JSONArray(listOf(3000, 4500, 6000))) },
            { it.getJSONObject("emoji").put("duration_ms", 114_000) },
            { it.getJSONObject("emoji").put("reference_is_verbatim_gold", true) },
            { it.getJSONObject("emoji").put("media_url", "https://example.com/emoji") },
            { it.getJSONObject("emoji").getJSONObject("pcm").put("sha256", "0".repeat(64)) },
        )
        changes.forEach { change -> assertThrows(IllegalArgumentException::class.java) { FixedCaptionWindowFixture(manifest().also(change)) } }
        val fixture = FixedCaptionWindowFixture(manifest())
        assertThrows(IllegalArgumentException::class.java) { fixture.windows(47_720L, 4_500L) }
        assertThrows(IllegalArgumentException::class.java) { fixture.windows(0L, 6_000L) }
    }
}
