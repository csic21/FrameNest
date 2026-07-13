package com.framenest.player

/**
 * Minimal track description exposed to UI.
 *
 * [id] is the libVLC track id used with setAudioTrack / setSpuTrack.
 * A sentinel id of -1 means "disabled" for subtitle tracks (libVLC convention).
 */
data class PlayerTrack(
    val id: Int,
    val name: String,
    val kind: Kind,
) {
    enum class Kind {
        Audio,
        Subtitle,
        Video,
    }
}
