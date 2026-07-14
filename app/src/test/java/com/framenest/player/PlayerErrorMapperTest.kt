package com.framenest.player

import com.framenest.smb.SmbError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerErrorMapperTest {

    @Test
    fun mapsAuthAsRetryable() {
        val err = PlayerErrorMapper.fromSmb(SmbError.Auth("bad password=secret"))
        assertEquals(PlayerError.Code.Auth, err.code)
        assertTrue(err.retryable)
        assertFalse(err.message.contains("secret"))
    }

    @Test
    fun mapsNetworkAsRetryable() {
        val err = PlayerErrorMapper.fromSmb(SmbError.Network("timeout"))
        assertEquals(PlayerError.Code.Network, err.code)
        assertTrue(err.retryable)
    }

    @Test
    fun mapsNotFoundAsNonRetryable() {
        val err = PlayerErrorMapper.fromSmb(SmbError.NotFound("missing"))
        assertEquals(PlayerError.Code.NotFound, err.code)
        assertFalse(err.retryable)
    }
}
