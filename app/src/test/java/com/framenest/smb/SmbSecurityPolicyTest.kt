package com.framenest.smb

import com.framenest.core.model.SavedServer
import com.framenest.data.server.SmbUiMessages
import com.framenest.player.PlayerErrorMapper
import com.hierynomus.smbj.SmbConfig
import org.junit.Assert.*
import org.junit.Test

class SmbSecurityPolicyTest {
    @Test fun defaultIsSignedAndEncrypted() {
        val config = SmbjClient.defaultConfig()
        assertTrue(config.isSigningRequired)
        assertTrue(config.isSigningEnabled)
        assertTrue(config.isEncryptData)
        assertTrue(SmbCredentials("test", username = "", password = charArrayOf()).requireEncryption)
        assertTrue(SavedServer("id", "NAS", "test", username = "u", credentialAlias = "alias").requireEncryption)
    }
    @Test fun explicitCompatibilityStillRequiresSigning() {
        val config = SmbjClient.secureConfig(SmbConfig.builder().withSigningRequired(false).build(), false)
        assertTrue(config.isSigningRequired)
        assertTrue(config.isSigningEnabled)
        assertFalse(config.isEncryptData)
    }
    @Test fun negotiationAndSessionRejectInsecureCapabilitiesWithoutFallback() {
        assertTrue(runCatching { requireSmbEncryptionCapability(false) }.exceptionOrNull() is SmbException)
        requireSmbEncryptionCapability(true)
        for (values in listOf(listOf(true, true, false, false), listOf(false, false, false, false), listOf(true, true, true, true))) {
            assertTrue(runCatching { requireSmbSessionSecurity(values[0], values[1], values[2], values[3]) }.exceptionOrNull() is SmbException)
        }
        requireSmbSessionSecurity(true, true, false, true)
        requireSmbSessionSecurity(false, true, false, false)
    }
    @Test fun securityFailureGivesActionableNonRetryingError() {
        val error = SmbError.Security()
        assertTrue(SmbUiMessages.fromSmbError(error).contains("服务器编辑"))
        assertFalse(PlayerErrorMapper.fromSmb(error).retryable)
    }
}
