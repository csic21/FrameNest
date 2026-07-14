package com.framenest.feature.browser

import com.framenest.data.settings.BrowseLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLayoutModeTest {

    @Test
    fun fromStorage_defaultsToList() {
        assertEquals(BrowseLayoutMode.LIST, BrowseLayoutMode.fromStorage(null))
        assertEquals(BrowseLayoutMode.LIST, BrowseLayoutMode.fromStorage(""))
        assertEquals(BrowseLayoutMode.LIST, BrowseLayoutMode.fromStorage("list"))
        assertEquals(BrowseLayoutMode.LIST, BrowseLayoutMode.fromStorage("unknown"))
    }

    @Test
    fun fromStorage_parsesGridCaseInsensitive() {
        assertEquals(BrowseLayoutMode.GRID, BrowseLayoutMode.fromStorage("grid"))
        assertEquals(BrowseLayoutMode.GRID, BrowseLayoutMode.fromStorage("GRID"))
        assertEquals(BrowseLayoutMode.GRID, BrowseLayoutMode.fromStorage(" Grid "))
    }

    @Test
    fun storageValue_roundTrips() {
        assertEquals(
            BrowseLayoutMode.LIST,
            BrowseLayoutMode.fromStorage(BrowseLayoutMode.LIST.storageValue()),
        )
        assertEquals(
            BrowseLayoutMode.GRID,
            BrowseLayoutMode.fromStorage(BrowseLayoutMode.GRID.storageValue()),
        )
    }

    @Test
    fun gridColumns_phoneTwo_tabletThreeOrFour() {
        assertEquals(2, browseGridColumnCount(360f))
        assertEquals(2, browseGridColumnCount(599f))
        assertEquals(3, browseGridColumnCount(600f))
        assertEquals(3, browseGridColumnCount(839f))
        assertEquals(4, browseGridColumnCount(840f))
        assertEquals(4, browseGridColumnCount(1200f))
    }
}
