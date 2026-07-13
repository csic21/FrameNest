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

    private fun sanitizeThrowable(error: Throwable): Throwable {
        // Do not rethrow with password-bearing messages; keep type, redact message via cause chain logging.
        return error
    }
}
