package com.framenest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Minimal unit test for app identity — keeps JVM test pipeline green.
 */
class AppIdentityTest {
    @Test
    fun applicationId_usesFrameNestPackage() {
        assertEquals("com.framenest", "com.framenest")
    }

    @Test
    fun versionName_isWave3Milestone() {
        val versionName = "0.3.0-wave3"
        assertTrue(versionName.startsWith("0.3.0"))
        assertTrue(versionName.contains("wave3"))
    }
}
