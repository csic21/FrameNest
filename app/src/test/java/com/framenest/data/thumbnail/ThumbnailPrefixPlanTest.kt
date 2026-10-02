package com.framenest.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrefixPlanTest {

    @Test
    fun prefixBytes_capsALongFileAndKeepsAShortOne() {
        assertEquals(0L, ThumbnailPrefixPlan.prefixBytes(-1L))
        assertEquals(12L, ThumbnailPrefixPlan.prefixBytes(12L))
        assertEquals(
            ThumbnailPrefixPlan.MAX_PREFIX_BYTES,
            ThumbnailPrefixPlan.prefixBytes(88_000_000_000L),
        )
    }

    @Test
    fun timestamps_longVideoStayNearTheStart() {
        assertEquals(
            listOf(2_000L, 5_000L, 8_000L, 12_000L),
            ThumbnailPrefixPlan.timestampsMs(7_200_000L),
        )
        assertEquals(
            listOf(2_000L, 5_000L, 8_000L, 12_000L),
            ThumbnailPrefixPlan.timestampsMs(0L),
        )
        assertTrue(ThumbnailPrefixPlan.timestampsMs(7_200_000L).all { it <= 12_000L })
    }

    @Test
    fun timestamps_shortVideoUsesTheCoverPolicy() {
        assertEquals(
            ThumbnailCandidatePolicy.candidateTimestampsMs(8_000L),
            ThumbnailPrefixPlan.timestampsMs(8_000L),
        )
    }

    @Test
    fun mkvSegmentSize_isTheVintAfterTheSegmentId() {
        val header = byteArrayOf(
            0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(),
            0x8F.toByte(),
            0x18, 0x53, 0x80.toByte(), 0x67,
            0x01, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78, 0x00,
        )
        val field = ThumbnailPrefixPlan.mkvSegmentSizeField(header)
        assertEquals(9 until 17, field)
    }

    @Test
    fun mkvSegmentSize_absentForOtherContainers() {
        val mp4 = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
        )
        assertNull(ThumbnailPrefixPlan.mkvSegmentSizeField(mp4))
        assertNull(ThumbnailPrefixPlan.mkvSegmentSizeField(byteArrayOf(0x1A, 0x45)))
    }
}
