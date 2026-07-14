package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoScaleModeTest {

    @Test
    fun next_cyclesMainModes() {
        assertEquals(VideoScaleMode.FitScreen, VideoScaleMode.BestFit.next())
        assertEquals(VideoScaleMode.Fill, VideoScaleMode.FitScreen.next())
        assertEquals(VideoScaleMode.Ratio16_9, VideoScaleMode.Fill.next())
        assertEquals(VideoScaleMode.Ratio4_3, VideoScaleMode.Ratio16_9.next())
        assertEquals(VideoScaleMode.Original, VideoScaleMode.Ratio4_3.next())
        assertEquals(VideoScaleMode.BestFit, VideoScaleMode.Original.next())
    }

    @Test
    fun defaultState_isBestFit() {
        assertEquals(VideoScaleMode.BestFit, PlayerState().videoScaleMode)
    }

    @Test
    fun cycleOrder_matchesLibVlcMainTypesCount() {
        // libVLC MediaPlayer.ScaleType.getMainScaleTypes() returns 6 entries.
        assertEquals(6, VideoScaleMode.cycleOrder.size)
        assertEquals(VideoScaleMode.BestFit, VideoScaleMode.cycleOrder.first())
    }
}
