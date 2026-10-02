package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetrieverSourceLengthTest {

    @Test
    fun declaredDescriptorLengthWins() {
        assertEquals(80L, RetrieverSourceLength.bytes(afdLength = 80L, fileSizeBytes = 2_000L))
        assertEquals(0L, RetrieverSourceLength.bytes(afdLength = 0L, fileSizeBytes = 2_000L))
    }

    @Test
    fun unknownDescriptorLengthUsesPositiveFileSize() {
        assertEquals(2_258_889_637L, RetrieverSourceLength.bytes(afdLength = -1L, fileSizeBytes = 2_258_889_637L))
        assertEquals(12L, RetrieverSourceLength.bytes(afdLength = -1L, fileSizeBytes = 12L))
    }

    @Test
    fun unknownSizeOpensTheDescriptorAlone() {
        assertNull(RetrieverSourceLength.bytes(afdLength = -1L, fileSizeBytes = 0L))
        assertNull(RetrieverSourceLength.bytes(afdLength = -1L, fileSizeBytes = -5L))
    }
}
