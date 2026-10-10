package com.framenest.feature.listen_translate

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Requested panel widths; host constraints still apply. Run on phone and tablet devices. */
@RunWith(Parameterized::class)
class ListenTranslateDiagnosticsControlsTest(private val widthDp: Int) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun experimentalGateAndLocalDiagnosticsAreExplicitAndScrollable() {
        val state = mutableStateOf(ListenTranslateUiState())
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    onExperimentalSilenceGate = { state.value = state.value.copy(experimentalSilenceGate = it) },
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_silence_gate").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(isInstallingModels = true) }
        compose.onNodeWithTag("listen_silence_gate").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(isInstallingModels = false, enabled = true) }
        compose.onNodeWithTag("listen_silence_gate").assertIsNotEnabled()
        compose.onNodeWithTag("listen_diagnostics_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("listen_diagnostics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("listen_diagnostics_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("listen_diagnostics").assertDoesNotExist()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "panel width {0}dp")
        fun widths(): List<Array<Int>> = listOf(arrayOf(360), arrayOf(840))
    }
}
