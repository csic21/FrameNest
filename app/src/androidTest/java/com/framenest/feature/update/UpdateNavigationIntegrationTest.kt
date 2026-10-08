package com.framenest.feature.update

import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import com.framenest.navigation.FrameNestApp
import com.framenest.navigation.FrameNestRoutes
import com.framenest.ui.theme.FrameNestTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Tests the actual root host/Settings/navigation wiring without update networking or installation. */
class UpdateNavigationIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun cleanup() { scope.cancel() }

    @Test fun compact_settingsCheckUsesRootHostAndDefersLateResultOnPlayerRoute() {
        verifySettingsAndPlayerGate(tablet = false)
    }

    @Test fun medium_settingsCheckUsesRootHostAndDefersLateResultOnPlayerRoute() {
        verifySettingsAndPlayerGate(tablet = true)
    }

    private fun verifySettingsAndPlayerGate(tablet: Boolean) {
        val backend = DeferredBackend()
        // Keep the automatic check within its daily limit so only the real Settings button can check.
        val store = object : UpdateCheckStore {
            override var lastAttempt = 1_000L
            override var dismissedVersion = 0L
        }
        val controller = UpdateController(backend, store, scope) { 1_000L }
        lateinit var nav: NavHostController
        compose.setContent {
            FrameNestTheme {
                nav = rememberNavController()
                FrameNestApp(
                    windowAdaptiveInfo = WindowAdaptiveInfo(
                        windowSizeClass = WindowSizeClass(
                            minWidthDp = if (tablet) WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND else 0,
                            minHeightDp = if (tablet) WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND else 0,
                        ),
                        windowPosture = Posture(),
                    ),
                    navigationSuiteType = if (tablet) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
                    navController = nav,
                    updateController = controller,
                )
            }
        }

        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.runOnIdle { assertEquals(0, backend.checks) }
        compose.onNodeWithTag("settings_check_update").performScrollTo().performClick()
        compose.onNodeWithTag("update_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, backend.checks)
            assertEquals(UpdatePhase.Checking, controller.state.value.phase)
            // The selected top-level tab remains Settings. Only the actual route identifies playback.
            // A missing server exercises the real destination without starting media or accessing a NAS.
            nav.navigate(FrameNestRoutes.player("update-test-missing-server", "share", "file.mkv"))
        }
        compose.onNodeWithTag("nav_suite_none").assertIsDisplayed()
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()

        compose.runOnIdle { backend.result.complete(updateManifest()) }
        compose.waitUntil(timeoutMillis = 5_000) { controller.state.value.phase == UpdatePhase.Available }
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(FrameNestRoutes.PLAYER, nav.currentDestination?.route) }

        compose.runOnIdle { check(nav.popBackStack()) }
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("update_dialog").assertIsDisplayed()
        compose.onNodeWithTag("update_notes").assertIsDisplayed()
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()

        // Dismissal survives navigation; the Settings entry explicitly reopens the shared result.
        compose.onNodeWithTag("nav_servers").performClick()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("update_dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings_check_update").performScrollTo().performClick()
        compose.onNodeWithTag("update_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, backend.checks)
            assertEquals(UpdatePhase.Available, controller.state.value.phase)
        }
    }

    private class DeferredBackend : UpdateBackend {
        override val installedVersionCode = 10L
        override val supportedAbis = listOf("arm64-v8a")
        override val sdkInt = 36
        val result = CompletableDeferred<UpdateManifest>()
        var checks = 0
            private set

        override suspend fun check(): UpdateManifest {
            checks++
            return result.await()
        }

        override suspend fun download(manifest: UpdateManifest, asset: UpdateAsset, progress: (Long) -> Unit): File =
            error("This navigation test must not download an APK")

        override suspend fun verify(file: File, manifest: UpdateManifest, asset: UpdateAsset) =
            error("This navigation test must not install an APK")

        override suspend fun discard(file: File) = Unit
    }

    private fun updateManifest() = UpdateManifest(
        schemaVersion = 1,
        applicationId = "com.framenest",
        versionName = "0.6.0-internal",
        versionCode = 11,
        tag = "v0.6.0-internal",
        notes = "导航集成测试更新说明",
        minSdk = 26,
        assets = listOf(UpdateAsset(
            abi = "arm64-v8a",
            size = 100,
            sha256 = "a".repeat(64),
            url = "https://github.com/csic21/FrameNest/releases/download/v0.6.0-internal/FrameNest-arm64-v8a.apk",
        )),
    )
}
