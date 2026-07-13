package com.framenest.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {

    @Test
    fun redactsSmbUserinfoInUrl() {
        val raw = "Failed opening smb://alice:s3cret@192.168.1.5/media/a.mkv"
        val out = CredentialRedactor.redact(raw)
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("alice"))
        assertTrue(out.contains("smb://***:***@"))
    }

    @Test
    fun redactsMediaOptionPassword() {
        val raw = "option :smb-pwd=hunter2 applied"
        val out = CredentialRedactor.redact(raw)
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains(":smb-pwd=***"))
    }

    @Test
    fun redactsPasswordKeyValue() {
        val raw = "auth failed password=topsecret domain=WORKGROUP"
        val out = CredentialRedactor.redact(raw)
        assertFalse(out.contains("topsecret"))
        assertTrue(out.contains("password=***"))
    }

    @Test
    fun nullAndEmptySafe() {
        assertEquals("", CredentialRedactor.redact(null))
        assertEquals("", CredentialRedactor.redact(""))
    }
}
