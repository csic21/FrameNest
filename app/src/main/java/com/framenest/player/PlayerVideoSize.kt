package com.framenest.player

/**
 * Pixel frame size of the current video track plus its sample aspect ratio, as
 * reported by libVLC. Used to mirror the library's own letterbox / scale layout
 * so app-drawn captions sit where libVLC draws SPU subtitles.
 */
data class PlayerVideoSize(
    val width: Int,
    val height: Int,
    val sarNum: Int = 1,
    val sarDen: Int = 1,
)
