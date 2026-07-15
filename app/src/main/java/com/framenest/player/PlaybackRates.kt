package com.framenest.player

/**
 * Discrete playback rates exposed by the product player.
 * Values map 1:1 to libVLC [org.videolan.libvlc.MediaPlayer.setRate].
 */
object PlaybackRates {
    val ALL: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    const val DEFAULT: Float = 1.0f

    fun clamp(rate: Float): Float {
        if (rate.isNaN() || rate.isInfinite()) return DEFAULT
        // Snap to the nearest supported step so UI chips and setRate stay in sync.
        return ALL.minBy { kotlin.math.abs(it - rate) }
    }

    fun next(current: Float): Float {
        val clamped = clamp(current)
        val index = ALL.indexOf(clamped).takeIf { it >= 0 } ?: ALL.indexOf(DEFAULT)
        return ALL[(index + 1) % ALL.size]
    }

    fun label(rate: Float): String {
        val r = clamp(rate)
        return if (r == r.toLong().toFloat()) {
            "${r.toLong()}x"
        } else {
            "${r}x"
        }
    }
}
