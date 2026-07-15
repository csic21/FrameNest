package com.framenest.smb

import android.util.Log

/**
 * Logging helpers that always run messages through secret redaction.
 */
object SmbLog {
    private const val TAG = "FrameNest.SMB"

    fun d(message: String) {
        Log.d(TAG, SmbErrorMapper.redactSecrets(message))
    }

    fun i(message: String) {
        Log.i(TAG, SmbErrorMapper.redactSecrets(message))
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(TAG, SmbErrorMapper.redactSecrets(message))
        } else {
            Log.w(TAG, SmbErrorMapper.redactSecrets(message), sanitizeThrowable(error))
        }
    }

    fun e(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.e(TAG, SmbErrorMapper.redactSecrets(message))
        } else {
            Log.e(TAG, SmbErrorMapper.redactSecrets(message), sanitizeThrowable(error))
        }
    }

    internal fun sanitizeThrowable(error: Throwable): Throwable {
        return sanitizeThrowable(error, HashSet())
    }

    private fun sanitizeThrowable(
        error: Throwable,
        seen: MutableSet<Throwable>,
    ): Throwable {
        if (!seen.add(error)) {
            return RedactedLogThrowable(
                originalType = error.javaClass.name,
                safeMessage = "[cyclic cause]",
            ).also { it.stackTrace = error.stackTrace }
        }
        val sanitizedCause = error.cause
            ?.takeUnless { it === error }
            ?.let { sanitizeThrowable(it, seen) }
        return RedactedLogThrowable(
            originalType = error.javaClass.name,
            safeMessage = error.message
                ?.let(SmbErrorMapper::redactSecrets)
                ?.take(500),
            cause = sanitizedCause,
        ).also { sanitized ->
            sanitized.stackTrace = error.stackTrace
            error.suppressed.forEach { suppressed ->
                sanitized.addSuppressed(sanitizeThrowable(suppressed, seen))
            }
        }
    }

    /**
     * Log-only throwable that retains the original type name and stack frames while
     * ensuring Android's stack-trace formatter never sees an unsanitized message.
     */
    private class RedactedLogThrowable(
        private val originalType: String,
        private val safeMessage: String?,
        cause: Throwable? = null,
    ) : Throwable(safeMessage, cause, true, true) {
        override fun toString(): String = if (safeMessage.isNullOrBlank()) {
            originalType
        } else {
            "$originalType: $safeMessage"
        }
    }
}
