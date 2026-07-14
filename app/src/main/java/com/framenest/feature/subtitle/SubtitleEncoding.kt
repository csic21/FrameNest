package com.framenest.feature.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Prefer UTF-8 for sidecar subtitle bytes; convert common non-UTF-8 encodings
 * to UTF-8 text. Never throws for ordinary input.
 */
object SubtitleEncoding {

    data class Decoded(val text: String, val note: String?)

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded("", note = null)

        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                val text = String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
                return Decoded(text, note = null)
            }
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> {
                return Decoded(
                    text = String(bytes, StandardCharsets.UTF_16LE),
                    note = "Converted from UTF-16LE to UTF-8",
                )
            }
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> {
                return Decoded(
                    text = String(bytes, StandardCharsets.UTF_16BE),
                    note = "Converted from UTF-16BE to UTF-8",
                )
            }
        }

        decodeStrict(bytes, StandardCharsets.UTF_8)?.let {
            return Decoded(it, note = null)
        }

        val fallbacks = listOf(
            charsetOrNull("GB18030") to "GB18030",
            charsetOrNull("GBK") to "GBK",
            StandardCharsets.ISO_8859_1 to "ISO-8859-1",
        )
        for ((charset, label) in fallbacks) {
            if (charset == null) continue
            val text = decodeStrict(bytes, charset) ?: continue
            return Decoded(text, note = "Subtitle was not UTF-8; converted from $label")
        }

        return Decoded(
            text = String(bytes, StandardCharsets.UTF_8),
            note = "Subtitle encoding uncertain; loaded with replacement characters",
        )
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()
}
