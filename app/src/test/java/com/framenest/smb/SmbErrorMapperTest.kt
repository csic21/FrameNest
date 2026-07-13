package com.framenest.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmbErrorMapperTest {

    @Test
    fun mapsUnknownHostToNetwork() {
        val error = SmbErrorMapper.map(UnknownHostException("nas.local"))
        assertTrue(error is SmbError.Network)
    }

    @Test
    fun mapsConnectExceptionToNetwork() {
        val error = SmbErrorMapper.map(ConnectException("refused"))
        assertTrue(error is SmbError.Network)
    }

    @Test
    fun mapsTimeoutToNetwork() {
        val error = SmbErrorMapper.map(SocketTimeoutException("timeout"))
        assertTrue(error is SmbError.Network)
    }

    @Test
    fun mapsLogonFailureToAuth() {
        val api = api(NtStatus.STATUS_LOGON_FAILURE, SMB2MessageCommandCode.SMB2_SESSION_SETUP)
        val error = SmbErrorMapper.map(api)
        assertTrue(error is SmbError.Auth)
    }

    @Test
    fun mapsObjectNameNotFound() {
        val api = api(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, SMB2MessageCommandCode.SMB2_CREATE)
        val error = SmbErrorMapper.map(api)
        assertTrue(error is SmbError.NotFound)
    }

    @Test
    fun mapsBadNetworkNameToNotFound() {
        val api = api(NtStatus.STATUS_BAD_NETWORK_NAME, SMB2MessageCommandCode.SMB2_TREE_CONNECT)
        val error = SmbErrorMapper.map(api)
        assertTrue(error is SmbError.NotFound)
    }

    @Test
    fun mapsAccessDeniedToPermission() {
        val api = api(NtStatus.STATUS_ACCESS_DENIED, SMB2MessageCommandCode.SMB2_CREATE)
        val error = SmbErrorMapper.map(api)
        assertTrue(error is SmbError.Permission)
    }

    @Test
    fun mapsConnectionResetToDisconnected() {
        val api = api(NtStatus.STATUS_CONNECTION_RESET, SMB2MessageCommandCode.SMB2_READ)
        val error = SmbErrorMapper.map(api)
        assertTrue(error is SmbError.Disconnected)
    }

    @Test
    fun unwrapsCauseChainToApiException() {
        val api = api(NtStatus.STATUS_LOGON_FAILURE, SMB2MessageCommandCode.SMB2_SESSION_SETUP)
        val wrapped = RuntimeException(java.util.concurrent.ExecutionException(api))
        val error = SmbErrorMapper.map(wrapped)
        assertTrue(error is SmbError.Auth)
    }

    @Test
    fun redactSecretsStripsPasswordAssignments() {
        val raw = "failed password=super-secret user=demo smb://u:p@host/share"
        val redacted = SmbErrorMapper.redactSecrets(raw)
        assertFalse(redacted.contains("super-secret"))
        assertTrue(redacted.contains("password=***"))
        assertFalse(redacted.contains("smb://u:p@"))
        assertTrue(redacted.contains("smb://***@"))
    }

    @Test
    fun smbExceptionPreservesMappedError() {
        val original = SmbError.Auth("Authentication failed")
        val mapped = SmbErrorMapper.map(SmbException(original))
        assertEquals(original, mapped)
    }

    private fun api(status: NtStatus, command: SMB2MessageCommandCode): SMBApiException =
        SMBApiException(status.value, command, null as Throwable?)
}
