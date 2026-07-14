package com.framenest.player

import com.framenest.smb.SmbError

/**
 * Maps SMB / open failures to safe [PlayerError] values for the product UI.
 */
object PlayerErrorMapper {
    fun fromSmb(error: SmbError): PlayerError {
        val message = CredentialRedactor.redact(error.message)
        return when (error) {
            is SmbError.Auth -> PlayerError(
                code = PlayerError.Code.Auth,
                message = message.ifBlank { "Authentication failed" },
                retryable = true,
            )
            is SmbError.Network -> PlayerError(
                code = PlayerError.Code.Network,
                message = message.ifBlank { "Network error" },
                retryable = true,
            )
            is SmbError.Disconnected -> PlayerError(
                code = PlayerError.Code.Network,
                message = message.ifBlank { "Disconnected" },
                retryable = true,
            )
            is SmbError.NotFound -> PlayerError(
                code = PlayerError.Code.NotFound,
                message = message.ifBlank { "File not found" },
                retryable = false,
            )
            is SmbError.Permission -> PlayerError(
                code = PlayerError.Code.Auth,
                message = message.ifBlank { "Permission denied" },
                retryable = true,
            )
            is SmbError.Unknown -> PlayerError(
                code = PlayerError.Code.OpenFailed,
                message = message.ifBlank { "SMB error" },
                retryable = true,
            )
        }
    }

    fun fromThrowable(t: Throwable, fallbackCode: PlayerError.Code = PlayerError.Code.OpenFailed): PlayerError {
        val msg = CredentialRedactor.redact(t.message ?: t::class.java.simpleName)
        return PlayerError(
            code = fallbackCode,
            message = msg.ifBlank { "Playback failed" },
            retryable = true,
        )
    }
}
