package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRatesTest {

    @Test
    fun clamp_snapsToNearestSupportedRate() {
        assertEquals(1.0f, PlaybackRates.clamp(1.0f))
        assertEquals(1.25f, PlaybackRates.clamp(1.2f), 0f)
        assertEquals(0.5f, PlaybackRates.clamp(0.1f), 0f)
        assertEquals(2.0f, PlaybackRates.clamp(9f), 0f)
        assertEquals(1.0f, PlaybackRates.clamp(Float.NaN), 0f)
    }

    @Test
    fun next_cyclesThroughAllSteps() {
        var rate = 0.5f
        val seen = mutableListOf<Float>()
        repeat(PlaybackRates.ALL.size) {
            seen += rate
            rate = PlaybackRates.next(rate)
        }
        assertEquals(PlaybackRates.ALL, seen)
        assertEquals(0.5f, rate, 0f)
    }

    @Test
    fun label_formatsWholeAndFractional() {
        assertEquals("1x", PlaybackRates.label(1.0f))
        assertEquals("2x", PlaybackRates.label(2.0f))
        assertEquals("1.25x", PlaybackRates.label(1.25f))
        assertEquals("0.75x", PlaybackRates.label(0.75f))
    }
}
