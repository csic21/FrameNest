package com.framenest.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbShareCandidatesTest {

    @Test
    fun merge_putsUserKnownFirst_andDropsAdminShares() {
        val merged = SmbShareCandidates.merge(listOf("MyShare", "IPC$", "  media  "))
        assertEquals("MyShare", merged.first())
        assertTrue(merged.any { it.equals("media", ignoreCase = true) })
        assertFalse(merged.any { it.contains("$") })
        // de-dupe media from user + common
        assertEquals(1, merged.count { it.equals("media", ignoreCase = true) })
    }

    @Test
    fun common_includesHomeNasNames() {
        assertTrue(SmbShareCandidates.COMMON.contains("media"))
        assertTrue(SmbShareCandidates.COMMON.contains("videos"))
    }
}
