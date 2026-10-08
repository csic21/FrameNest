package com.framenest.feature.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test

class UpdateHostTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }

    @Test fun automaticResultWaitsUntilUserLeavesPlayerAndDoesNotReappearAfterDismissal() {
        val playing = mutableStateOf(true)
        val backend = object : UpdateBackend {
            override val installedVersionCode = 10L
            override val supportedAbis = listOf("arm64-v8a")
            override val sdkInt = 36
            override suspend fun check() = UpdateManifest(1, "com.framenest", "0.6.0-internal", 11, "v0.6.0-internal", "更新说明", 26,
                listOf(UpdateAsset("arm64-v8a", 100, "a".repeat(64), "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-arm64-v8a.apk")))
            override suspend fun download(manifest: UpdateManifest, asset: UpdateAsset, progress: (Long) -> Unit): File = error("No download was requested")
            override suspend fun verify(file: File, manifest: UpdateManifest, asset: UpdateAsset) = error("No installation was requested")
            override suspend fun discard(file: File) = Unit
        }
        val store = object : UpdateCheckStore {
            override var lastAttempt = 0L
            override var dismissedVersion = 0L
        }
        val controller = UpdateController(backend, store, scope)
        compose.setContent { MaterialTheme { UpdateHost(controller, playing.value) } }
        compose.waitForIdle()
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()
        compose.runOnIdle { playing.value = false }
        compose.onNodeWithTag("update_dialog").assertIsDisplayed()
        compose.runOnIdle { controller.dismiss(); playing.value = true }
        compose.runOnIdle { playing.value = false }
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()
    }
}
