package com.framenest.core.diagnostics

import com.framenest.player.CredentialRedactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-process ring buffer of redacted diagnostic lines for support export.
 * Never store passwords or raw credential material.
 */
object DiagnosticLog {
    private const val MAX_LINES = 200
    private val lines = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun info(tag: String, message: String) = append("I", tag, message)

    fun warn(tag: String, message: String) = append("W", tag, message)

    fun error(tag: String, message: String) = append("E", tag, message)

    @Synchronized
    private fun append(level: String, tag: String, message: String) {
        val safe = CredentialRedactor.redact(message)
        val safeTag = CredentialRedactor.redact(tag)
        val line = "${timeFormat.format(Date())} $level/$safeTag: $safe"
        lines.addLast(line)
        while (lines.size > MAX_LINES) {
            lines.pollFirst()
        }
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() {
        lines.clear()
    }
}
