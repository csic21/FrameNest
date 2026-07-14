package com.framenest.data.server

import com.framenest.smb.SmbError
import com.framenest.smb.SmbException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbUiMessagesTest {

    @Test
    fun mapsAuthToRetryableChineseMessage() {
        val msg = SmbUiMessages.fromSmbError(SmbError.Auth())
        assertTrue(msg.contains("认证"))
        assertFalse(msg.contains("password", ignoreCase = true))
    }

    @Test
    fun mapsNetwork() {
        val msg = SmbUiMessages.fromSmbError(SmbError.Network())
        assertTrue(msg.contains("网络"))
    }

    @Test
    fun mapsNotFoundPermissionDisconnected() {
        assertTrue(SmbUiMessages.fromSmbError(SmbError.NotFound()).contains("不存在"))
        assertTrue(SmbUiMessages.fromSmbError(SmbError.Permission()).contains("权限"))
        assertTrue(SmbUiMessages.fromSmbError(SmbError.Disconnected()).contains("断开"))
    }

    @Test
    fun fromThrowable_usesSmbException() {
        val msg = SmbUiMessages.fromThrowable(SmbException(SmbError.Auth("Authentication failed")))
        assertTrue(msg.contains("认证"))
    }

    @Test
    fun unknown_redactsPasswordAssignments() {
        val msg = SmbUiMessages.fromSmbError(
            SmbError.Unknown("failed password=supersecret host=x"),
        )
        assertFalse(msg.contains("supersecret"))
        assertTrue(msg.contains("***") || msg.contains("操作失败"))
    }
}
