package com.framenest.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.common.SMBRuntimeException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

/**
 * Maps raw SMBJ / I/O failures into [SmbError] without leaking credentials.
 */
object SmbErrorMapper {

    fun map(throwable: Throwable): SmbError {
        val api = findCause(throwable, SMBApiException::class.java)
        if (api != null) {
            return mapStatus(api)
        }
        val root = unwrap(throwable)
        return when (root) {
            is SmbException -> root.error
            is UnknownHostException -> SmbError.Network("Unknown host", root)
            is ConnectException -> SmbError.Network("Connection refused", root)
            is NoRouteToHostException -> SmbError.Network("No route to host", root)
            is SocketTimeoutException -> SmbError.Network("Connection timed out", root)
            is SocketException -> SmbError.Network(safeMessage(root), root)
            is IOException -> classifyByMessage(root, defaultNetwork = true)
            is SMBRuntimeException -> classifyByMessage(root, defaultNetwork = false)
            else -> classifyByMessage(root, defaultNetwork = false)
        }
    }

    fun mapStatus(exception: SMBApiException): SmbError {
        val status = exception.status
        val codeName = status?.name ?: statusCodeName(exception)
        return when (status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            -> SmbError.Auth("Authentication failed ($codeName)", exception)

            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_BAD_NETWORK_PATH,
            NtStatus.STATUS_OBJECT_NAME_INVALID,
            -> SmbError.NotFound("Not found ($codeName)", exception)

            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_SHARING_VIOLATION,
            NtStatus.STATUS_PRIVILEGE_NOT_HELD,
            -> SmbError.Permission("Access denied ($codeName)", exception)

            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_CONNECTION_RESET,
            NtStatus.STATUS_UNEXPECTED_IO_ERROR,
            NtStatus.STATUS_IO_TIMEOUT,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_CONNECTION_IN_USE,
            NtStatus.STATUS_FILE_CLOSED,
            -> SmbError.Disconnected("Disconnected ($codeName)", exception)

            else -> classifyByMessage(exception, defaultNetwork = false, codeName = codeName)
        }
    }

    private fun classifyByMessage(
        error: Throwable,
        defaultNetwork: Boolean,
        codeName: String? = null,
    ): SmbError {
        val msg = error.message.orEmpty()
        val suffix = codeName?.let { " ($it)" }.orEmpty()
        return when {
            looksLikeAuth(msg) -> SmbError.Auth("Authentication failed$suffix", error)
            looksLikeNotFound(msg) -> SmbError.NotFound("Not found$suffix", error)
            looksLikeNetwork(msg) || defaultNetwork ->
                SmbError.Network(safeMessage(error) + suffix, error)
            else -> SmbError.Unknown(safeMessage(error) + suffix, error)
        }
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        val seen = HashSet<Throwable>()
        while (seen.add(current)) {
            when (current) {
                is ExecutionException, is SMBRuntimeException -> {
                    val cause = current.cause ?: return current
                    if (cause === current) return current
                    current = cause
                }
                else -> return current
            }
        }
        return current
    }

    private fun <T : Throwable> findCause(throwable: Throwable, type: Class<T>): T? {
        var cursor: Throwable? = throwable
        val seen = HashSet<Throwable>()
        while (cursor != null && seen.add(cursor)) {
            if (type.isInstance(cursor)) {
                @Suppress("UNCHECKED_CAST")
                return cursor as T
            }
            cursor = cursor.cause
        }
        return null
    }

    private fun statusCodeName(exception: SMBApiException): String {
        val raw = exception.message.orEmpty()
        val match = STATUS_TOKEN.find(raw)
        return match?.value ?: "STATUS_0x${java.lang.Long.toHexString(exception.statusCode)}"
    }

    private fun looksLikeAuth(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("logon") ||
            m.contains("login") ||
            m.contains("password") ||
            m.contains("status_logon_failure") ||
            m.contains("status_wrong_password") ||
            m.contains("status_logon_type_not_granted") ||
            (m.contains("auth") && !m.contains("author"))
    }

    private fun looksLikeNotFound(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("not found") ||
            m.contains("no such") ||
            m.contains("status_object_name_not_found") ||
            m.contains("status_object_path_not_found") ||
            m.contains("status_bad_network_name") ||
            m.contains("status_no_such_file") ||
            m.contains("status_not_found")
    }

    private fun looksLikeNetwork(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("timeout") ||
            m.contains("connection") ||
            m.contains("network") ||
            m.contains("unreachable") ||
            m.contains("reset") ||
            m.contains("broken pipe")
    }

    fun safeMessage(error: Throwable): String {
        val raw = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return error.javaClass.simpleName
        return redactSecrets(raw).take(200)
    }

    /** Strip SMB URI userinfo and password-like assignments. */
    fun redactSecrets(input: String): String {
        var out = input
        out = PASSWORD_ASSIGN.replace(out, "$1=***")
        out = SMB_USER_INFO.replace(out, "$1***@")
        return out
    }

    private val PASSWORD_ASSIGN = Regex(
        "(?i)(password|passwd|pwd|pass)\\s*[=:]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
    )
    // Userinfo may contain domain/username/password in several forms. Redact the
    // whole authority prefix even if a malformed URL only contains a username.
    private val SMB_USER_INFO = Regex("(?i)(smb://)[^/@\\s]+@")
    private val STATUS_TOKEN = Regex("STATUS_[A-Z0-9_]+")
}
