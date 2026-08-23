package com.framenest.feature.browser

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.R
import com.framenest.core.model.RemoteEntry
import com.framenest.data.settings.BrowseLayoutMode
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Assert.assertEquals
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
    private val share = RemoteEntry(
        serverId = "server-1",
        share = "media",
        path = "",
        name = "media",
        isDirectory = true,
        isShare = true,
    )
    private val directory = RemoteEntry(
        serverId = "server-1",
        share = "media",
        path = "shows",
        name = "shows",
        isDirectory = true,
    )
    private val subtitle = RemoteEntry(
        serverId = "server-1",
        share = "media",
        path = "shows/Example.zh.srt",
        name = "Example.zh.srt",
        isDirectory = false,
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

    @Test
    fun listEntries_openSharesDirectoriesAndVideos_butNotSubtitles() {
        assertEntryActions(BrowseLayoutMode.LIST)
    }

    @Test
    fun gridEntries_openSharesDirectoriesAndVideos_butNotSubtitles() {
        assertEntryActions(BrowseLayoutMode.GRID)
    }

    private fun assertEntryActions(layoutMode: BrowseLayoutMode) {
        val opened = mutableListOf<RemoteEntry>()
        val actionable = listOf(share, directory, video)
        setBrowseContent(
            layoutMode = layoutMode,
            entries = actionable + subtitle,
            onOpenEntry = opened::add,
        )

        actionable.forEach { entry ->
            composeRule
                .onNodeWithTag("browse_item_${entry.stableKey()}")
                .assertHasClickAction()
                .performClick()
        }
        composeRule
            .onNodeWithTag("browse_item_${subtitle.stableKey()}")
            .assertHasNoClickAction()
            .assertContentDescriptionEquals(
                expectedDescription(
                    entry = subtitle,
                    typeRes = R.string.browse_type_subtitle_hint,
                ),
            )

        composeRule.runOnIdle {
            assertEquals(actionable, opened)
        }
    }

    private fun setBrowseContent(
        layoutMode: BrowseLayoutMode,
        entries: List<RemoteEntry> = listOf(video),
        onOpenEntry: (RemoteEntry) -> Unit = {},
    ) {
        composeRule.setContent {
            FrameNestTheme {
                BrowseScreen(
                    state = BrowseUiState(
                        entries = entries,
                        isShareList = false,
                    ),
                    title = "media",
                    pathLabel = "/media/shows",
                    onBack = {},
                    onRefresh = {},
                    onOpenEntry = onOpenEntry,
                    layoutMode = layoutMode,
                )
            }
        }
    }

    private fun expectedDescription(
        entry: RemoteEntry = video,
        typeRes: Int = R.string.browse_type_video,
    ): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.browse_entry_cd,
            entry.name,
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                typeRes,
            ),
        )
}
