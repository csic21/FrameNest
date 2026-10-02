package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailRv32Test {

    @Test
    fun rv32BytesMatchAndroidArgb() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte())
        assertEquals(0xFF302010.toInt(), ThumbnailRv32.argb(bytes, pitch = 4, x = 0, y = 0))
    }

    @Test
    fun pitchedRowSkipsPadding() {
        val size = ThumbnailFrameGeometry.fit(1080, 1920)
        val bytes = ByteArray(size.byteCount)
        val offset = size.pitch + 0
        bytes[offset] = 0x10
        bytes[offset + 1] = 0x20
        bytes[offset + 2] = 0x30
        bytes[offset + 3] = 0xFF.toByte()
        assertEquals(0xFF302010.toInt(), ThumbnailRv32.argb(bytes, size.pitch, x = 0, y = 1))
    }
}
