package com.framenest.data.thumbnail

/**
 * libVLC chroma `RV32` on little-endian is byte order B,G,R,A.
 * That is the same order as Android `ARGB_8888` pixels.
 */
internal object ThumbnailRv32 {
    fun argb(bytes: ByteArray, pitch: Int, x: Int, y: Int): Int {
        val offset = y * pitch + x * 4
        val b = bytes[offset].toInt() and 0xFF
        val g = bytes[offset + 1].toInt() and 0xFF
        val r = bytes[offset + 2].toInt() and 0xFF
        val a = bytes[offset + 3].toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
