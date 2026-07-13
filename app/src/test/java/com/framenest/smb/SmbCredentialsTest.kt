package com.framenest.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbCredentialsTest {

    @Test
    fun toString_and_safeSummary_neverIncludePassword() {
        val creds = SmbCredentials(
            host = "192.168.0.10",
            port = 445,
            username = "media",
            password = "s3cret-value".toCharArray(),
            domain = "HOME",
        )
        val asString = creds.toString()
        val summary = creds.safeSummary()
        assertFalse(asString.contains("s3cret"))
        assertFalse(summary.contains("s3cret"))
        assertTrue(summary.contains("192.168.0.10"))
        assertTrue(summary.contains("media"))
    }

    @Test
    fun clearPassword_zeroesArray() {
        val password = "abc".toCharArray()
        val creds = SmbCredentials(
            host = "h",
            username = "u",
            password = password,
        )
        creds.clearPassword()
        assertTrue(password.all { it == '\u0000' })
    }
}
