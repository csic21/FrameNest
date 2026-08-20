package com.framenest.data.server

import com.framenest.smb.SmbError
import com.framenest.smb.SmbException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSessionRetryPolicyTest {

    @Test
    fun disconnectedOrNetwork_retries() {
        assertTrue(
            BrowseSessionRetryPolicy.shouldRetry(
                SmbException(SmbError.Disconnected("Not connected")),
            ),
        )
        assertTrue(
            BrowseSessionRetryPolicy.shouldRetry(
                SmbException(SmbError.Network("Connection timed out")),
            ),
        )
    }

    @Test
    fun authNotFoundPermissionUnknownOrCancel_doNotRetry() {
        assertFalse(
            BrowseSessionRetryPolicy.shouldRetry(SmbException(SmbError.Auth())),
        )
        assertFalse(
            BrowseSessionRetryPolicy.shouldRetry(SmbException(SmbError.NotFound())),
        )
        assertFalse(
            BrowseSessionRetryPolicy.shouldRetry(SmbException(SmbError.Permission())),
        )
        assertFalse(
            BrowseSessionRetryPolicy.shouldRetry(SmbException(SmbError.Unknown())),
        )
        assertFalse(
            BrowseSessionRetryPolicy.shouldRetry(CancellationException("cancelled")),
        )
        assertFalse(BrowseSessionRetryPolicy.shouldRetry(IllegalStateException("nope")))
    }
}
