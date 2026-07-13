package com.framenest.smb

/**
 * Connection parameters for an SMB2/3 session.
 *
 * Password is held as [CharArray] so callers can zero it after use.
 * [toString] / [safeSummary] never include the password.
 */
data class SmbCredentials(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val password: CharArray,
    val domain: String = "",
) {
    fun safeSummary(): String =
        "smb://${host.trim()}:$port domain=${domain.ifBlank { "<empty>" }} user=${username.ifBlank { "<empty>" }}"

    override fun toString(): String = safeSummary()

    /** Zeroes password chars. Call when the session is no longer needed. */
    fun clearPassword() {
        password.fill('\u0000')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmbCredentials) return false
        return host == other.host &&
            port == other.port &&
            username == other.username &&
            domain == other.domain &&
            password.contentEquals(other.password)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + password.contentHashCode()
        result = 31 * result + domain.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_PORT: Int = 445
    }
}
