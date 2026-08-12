package com.framenest.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ColorContrastTest {

    @Test
    fun `brand foreground roles meet normal text contrast`() {
        assertContrast(Color.White, FrameNestPrimaryLight)
        assertContrast(Color.White, FrameNestSecondaryLight)
        assertContrast(Color.White, FrameNestTertiaryLight)
        assertContrast(FrameNestMidnight, FrameNestPeriwinkle)
        assertContrast(Color(0xFF282047), FrameNestLavender)
        assertContrast(Color(0xFF3D2F00), FrameNestIvory)
        assertContrast(FrameNestOnSurfaceLight, FrameNestBackgroundLight)
        assertContrast(FrameNestOnSurfaceDark, FrameNestMidnight)
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("Expected at least 4.5:1, was $ratio", ratio >= 4.5f)
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val light = max(relativeLuminance(foreground), relativeLuminance(background))
        val dark = min(relativeLuminance(foreground), relativeLuminance(background))
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun relativeLuminance(color: Color): Float {
        fun channel(value: Float): Float =
            if (value <= 0.04045f) value / 12.92f
            else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }
}
