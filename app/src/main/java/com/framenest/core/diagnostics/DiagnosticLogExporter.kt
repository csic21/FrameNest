package com.framenest.core.diagnostics

import android.content.Context
import android.os.Build
import com.framenest.BuildConfig
import com.framenest.player.CredentialRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a redacted diagnostic report to the app cache directory for share/export.
 */
class DiagnosticLogExporter(
    private val context: Context,
) {
    /**
     * @return absolute path of the written report, or failure with redacted message.
     */
    fun export(): Result<File> = runCatching {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "framenest-diag-$stamp.txt")
        val body = buildString {
            appendLine("FrameNest diagnostic export")
            appendLine("generated=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("---")
            appendLine("Notes: credentials and passwords are redacted. Do not paste NAS passwords.")
            appendLine("--- diagnostic lines ---")
            DiagnosticLog.snapshot().forEach { appendLine(CredentialRedactor.redact(it)) }
            if (DiagnosticLog.snapshot().isEmpty()) {
                appendLine("(no in-app diagnostic lines yet)")
            }
        }
        out.writeText(body)
        out
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { e ->
            Result.failure(
                IllegalStateException(
                    CredentialRedactor.redact(e.message ?: "export failed"),
                    e,
                ),
            )
        },
    )
}
