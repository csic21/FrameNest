package com.framenest.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseEntryTextTest {
    @Test
    fun formatBytes_skipsMissingAndUsesLargerUnits() {
        assertNull(BrowseEntryText.formatBytes(null))
        assertNull(BrowseEntryText.formatBytes(0L))
        assertEquals("24 B", BrowseEntryText.formatBytes(24L))
        assertEquals("1.0 KB", BrowseEntryText.formatBytes(1_024L))
        assertEquals("1.5 MB", BrowseEntryText.formatBytes(1_572_864L))
        assertEquals("1.0 GB", BrowseEntryText.formatBytes(1_073_741_824L))
    }

    @Test
    fun formatDuration_usesMinutesUntilAnHour() {
        assertEquals("0:00", BrowseEntryText.formatDuration(0L))
        assertEquals("1:05", BrowseEntryText.formatDuration(65_000L))
        assertEquals("1:00:00", BrowseEntryText.formatDuration(3_600_000L))
    }
}
