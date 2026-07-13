package com.framenest.player

/**
 * Safe-to-display player error. Message must never contain credentials.
 */
data class PlayerError(
    val code: Code,
    val message: String,
) {
    enum class Code {
        OpenFailed,
        PlaybackError,
        Released,
        InvalidSource,
        Unknown,
    }
}
