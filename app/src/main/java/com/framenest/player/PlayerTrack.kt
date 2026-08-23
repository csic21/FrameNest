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
    /**
     * True when this SPU track was registered by [org.videolan.libvlc.MediaPlayer.addSlave]
     * (sidecar / external subtitle). Product UI lists those under 外挂字幕, not 内嵌字幕.
     */
    val isExternalSlave: Boolean = false,
) {
    enum class Kind {
        Audio,
        Subtitle,
        Video,
    }
}
