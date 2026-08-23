package com.framenest.feature.servers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framenest.core.model.SavedServer
import com.framenest.ui.theme.FrameNestTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun serverRow_readsHumanLabel_notInternalId_andDeleteExplainsLocalData() {
        val server = SavedServer(
            id = "internal-id-42",
            name = "客厅 NAS",
            host = "192.168.1.8",
            username = "viewer",
            credentialAlias = "credential-alias",
        )
        composeRule.setContent {
            FrameNestTheme {
                ServersScreen(
                    state = ServersUiState(servers = listOf(server)),
                    useListDetail = false,
                    onSelect = {},
                    onOpenBrowse = {},
                    onAdd = {},
                    onScanLan = {},
                    onEdit = {},
                    onDelete = {},
                    onDismissEditor = {},
                    onUpdateEditor = {},
                    onTest = {},
                    onSave = {},
                    onClearError = {},
                    onDismissDiscovery = {},
                    onStopDiscovery = {},
                    onDeepPortScan = {},
                    onSelectDiscovered = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "客厅 NAS，服务器 192.168.1.8",
        ).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithContentDescription(
                "server_item_internal-id-42",
            ).fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("server_row_delete_internal-id-42").performClick()
        composeRule.onNodeWithText(
            "将删除「客厅 NAS」、保存的凭证，以及这台服务器在本机的播放记录和听译结果。此操作不可撤销。",
        ).assertIsDisplayed()
    }
}
