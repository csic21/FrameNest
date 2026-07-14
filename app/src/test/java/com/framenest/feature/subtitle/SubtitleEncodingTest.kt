package com.framenest.feature.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class SubtitleEncodingTest {

    @Test
    fun utf8_roundTrip_noNote() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n你好 UTF-8\n"
        val decoded = SubtitleEncoding.decode(text.toByteArray(StandardCharsets.UTF_8))
        assertEquals(text, decoded.text)
        assertNull(decoded.note)
    }

    @Test
    fun utf8Bom_stripped() {
        val body = "hello"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            body.toByteArray(StandardCharsets.UTF_8)
        val decoded = SubtitleEncoding.decode(withBom)
        assertEquals(body, decoded.text)
        assertNull(decoded.note)
    }

    @Test
    fun gbk_convertedWithNote() {
        val gbk = Charset.forName("GBK")
        // bytes that are invalid as UTF-8 strict for Chinese text in GBK
        val text = "中文字幕"
        val bytes = text.toByteArray(gbk)
        // Ensure these are not valid strict UTF-8
        val asUtf8StrictFails = runCatching {
            val dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            dec.decode(java.nio.ByteBuffer.wrap(bytes))
        }.isFailure
        assertTrue("fixture should not be strict UTF-8", asUtf8StrictFails)

        val decoded = SubtitleEncoding.decode(bytes)
        assertEquals(text, decoded.text)
        assertTrue(decoded.note!!.contains("UTF-8").not() || decoded.note!!.contains("converted"))
        assertTrue(decoded.note!!.contains("GB"))
    }
}
