package com.framenest.player

/**
 * Safe-to-display player error. Message must never contain credentials.
 */
data class PlayerError(
    val code: Code,
    val message: String,
    /** Network / auth failures are retryable from the product player UI. */
    val retryable: Boolean = false,
) {
    enum class Code {
        OpenFailed,
        PlaybackError,
        Network,
        Auth,
        NotFound,
        Released,
        InvalidSource,
        Unknown,
    }
}
