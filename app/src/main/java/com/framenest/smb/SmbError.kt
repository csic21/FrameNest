package com.framenest.smb

/**
 * Stable error taxonomy for UI and repositories.
 * Messages must never contain passwords or full credential dumps.
 */
sealed class SmbError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    data class Security(
        override val message: String = "NAS 不满足 SMB 安全要求。默认需要 SMB3 加密和签名；如确需旧设备兼容，请在服务器编辑中明确选择仅签名模式。",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class Auth(
        override val message: String = "Authentication failed",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class Network(
        override val message: String = "Network error",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class NotFound(
        override val message: String = "Path or share not found",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class Permission(
        override val message: String = "Access denied",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class Disconnected(
        override val message: String = "Session disconnected",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)

    data class Unknown(
        override val message: String = "SMB error",
        override val cause: Throwable? = null,
    ) : SmbError(message, cause)
}

class SmbException(
    val error: SmbError,
) : Exception(error.message, error.cause)
