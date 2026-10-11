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

    @Test
    fun quotedEscapedMultilineAndTruncatedValuesDoNotLeak() {
        for (raw in listOf(
            "password=\"first \\\" hidden-tail\" host=nas",
            "passwd='first \\' hidden-tail' host=nas",
            "pwd=\"first\nhidden-tail\" host=nas",
            "pass='first hidden-tail",
            ":smb-pwd=first hidden-tail",
            ":sout-smbj-password=\"first hidden-tail\"",
        )) {
            val safe = CredentialRedactor.redact(raw)
            assertFalse(safe.contains("hidden-tail"))
            assertFalse(safe.contains("first"))
            assertTrue(safe.contains("***"))
        }
    }

    @Test
    fun usernamesAndAtSignsInUrlAreHidden() {
        for (raw in listOf("smb://alice@nas/share", "smb://alice:p@ss@nas/share")) {
            val safe = CredentialRedactor.redact(raw)
            assertFalse(safe.contains("alice"))
            assertFalse(safe.contains("p@ss"))
            assertTrue(safe.endsWith("@nas/share"))
        }
    }

    @Test
    fun longQuotedValueIsProcessedWithoutRegexStackOverflow() {
        val raw = "password=\"" + "x".repeat(100_000) + "\" host=nas"
        assertEquals("password=*** host=nas", CredentialRedactor.redact(raw))
        assertEquals("path=/movies/pwd-test.mkv", CredentialRedactor.redact("path=/movies/pwd-test.mkv"))
    }
}
