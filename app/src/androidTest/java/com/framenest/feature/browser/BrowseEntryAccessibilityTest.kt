package com.framenest.feature.browser

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import com.framenest.core.model.RemoteEntry
import com.framenest.data.settings.BrowseLayoutMode
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseEntryAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val video = RemoteEntry(
        serverId = "server-1",
        share = "media",
        path = "shows/Example.mkv",
        name = "Example.mkv",
        isDirectory = false,
        sizeBytes = 1_024L,
        modifiedTimeMs = 42L,
    )

    @Test
    fun listEntry_exposesLocalizedNameAndTypeInsteadOfTestTag() {
        setBrowseContent(BrowseLayoutMode.LIST)

        composeRule
            .onNodeWithTag("browse_item_${video.stableKey()}")
            .assertContentDescriptionEquals(expectedDescription())
    }

    @Test
    fun gridEntry_exposesLocalizedNameAndTypeInsteadOfTestTag() {
        setBrowseContent(BrowseLayoutMode.GRID)

        composeRule
            .onNodeWithTag("browse_item_${video.stableKey()}")
            .assertContentDescriptionEquals(expectedDescription())
    }

    private fun setBrowseContent(layoutMode: BrowseLayoutMode) {
        composeRule.setContent {
            FrameNestTheme {
                BrowseScreen(
                    state = BrowseUiState(
                        entries = listOf(video),
                        isShareList = false,
                    ),
                    title = "media",
                    pathLabel = "/media/shows",
                    onBack = {},
                    onRefresh = {},
                    onOpenEntry = {},
                    layoutMode = layoutMode,
                )
            }
        }
    }

    private fun expectedDescription(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.browse_entry_cd,
            video.name,
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.browse_type_video,
            ),
        )
}
