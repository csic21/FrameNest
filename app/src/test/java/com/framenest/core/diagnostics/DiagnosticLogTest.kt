package com.framenest.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogTest {

    @Before
    fun clear() {
        DiagnosticLog.clear()
    }

    @Test
    fun append_redactsPasswordLikeContent() {
        DiagnosticLog.info("Test", "login failed password=supersecret")
        val snap = DiagnosticLog.snapshot().joinToString("\n")
        assertFalse(snap.contains("supersecret"))
        assertTrue(snap.contains("Test") || snap.contains("login"))
    }
}
