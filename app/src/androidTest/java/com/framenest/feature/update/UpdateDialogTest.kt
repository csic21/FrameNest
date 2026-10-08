package com.framenest.feature.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()
    private val manifest = UpdateManifest(1, "com.framenest", "0.6.0-internal", 11, "v0.6.0-internal", "修复播放体验\n新增应用内更新", 26,
        listOf(UpdateAsset("arm64-v8a", 1024 * 1024, "a".repeat(64), "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-arm64-v8a.apk")))

    @Test fun availableDialogShowsNotesAndOnlyDownloadsAfterTap() {
        var downloads = 0
        compose.setContent {
            MaterialTheme {
                UpdateDialog(UpdateUiState(UpdatePhase.Available, manifest, manifest.assets.single()), {}, { downloads++ }, {}, {}, {}, true)
            }
        }
        compose.onNodeWithTag("update_notes").assertIsDisplayed()
        assertEquals(0, downloads)
        compose.onNodeWithTag("update_download").performClick()
        assertEquals(1, downloads)
    }

    @Test fun progressHasAnExplicitCancelAction() {
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                UpdateDialog(UpdateUiState(UpdatePhase.Downloading, manifest, manifest.assets.single(), downloadedBytes = 524288), {}, {}, {}, { cancels++ }, {}, true)
            }
        }
        compose.onNodeWithTag("update_progress").assertIsDisplayed()
        compose.onNodeWithText("取消下载").performClick()
        assertEquals(1, cancels)
    }

    @Test fun readyWithoutPermissionExplainsUserActionAndDoesNotStartAutomatically() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                UpdateDialog(UpdateUiState(UpdatePhase.Ready, manifest, manifest.assets.single()), {}, {}, {}, {}, { requests++ }, false)
            }
        }
        compose.onNodeWithText("允许安装来源").assertIsDisplayed()
        assertEquals(0, requests)
        compose.onNodeWithTag("update_install").performClick()
        assertEquals(1, requests)
    }
}
