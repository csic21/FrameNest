package com.framenest.player

/**
 * User-facing video surface scale modes, mapped 1:1 to libVLC
 * [org.videolan.libvlc.MediaPlayer.ScaleType] main cycle.
 *
 * Default is [BestFit] (letterbox / pillarbox, no stretch).
 */
enum class VideoScaleMode {
    /** Keep source aspect; black bars as needed. */
    BestFit,

    /** Enlarge to cover the shorter side of the surface (may crop). */
    FitScreen,

    /** Stretch to fill the surface (may distort). */
    Fill,

    /** Force 16:9 frame. */
    Ratio16_9,

    /** Force 4:3 frame. */
    Ratio4_3,

    /** 1:1 pixel, no scaling. */
    Original,
    ;

    fun next(): VideoScaleMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        /** Modes exposed by the cycle button (matches libVLC getMainScaleTypes). */
        val cycleOrder: List<VideoScaleMode> = entries
    }
}
