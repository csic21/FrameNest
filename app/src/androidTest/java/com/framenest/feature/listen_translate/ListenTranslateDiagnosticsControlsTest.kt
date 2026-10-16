package com.framenest.feature.listen_translate

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framenest.BuildConfig
import com.framenest.R
import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import android.graphics.Bitmap
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun pausedPreparationShowsActualCoverageAndCanBeCancelled() {
        val state = mutableStateOf(ListenTranslateUiState())
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    onPrepareSubtitles = { state.value = state.value.copy(preparingSubtitles = !state.value.preparingSubtitles) },
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_prepare_subtitles").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(enabled = true, preparedAheadMs = 6_000L) }
        compose.onNodeWithTag("listen_prepare_subtitles").performScrollTo().performClick()
        compose.runOnIdle { check(state.value.preparingSubtitles) }
        compose.onNodeWithTag("listen_ready_ahead").performScrollTo().assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        if (screenshot != null) {
            File(instrumentation.targetContext.cacheDir, "fn67-listen-$widthDp.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
        compose.onNodeWithTag("listen_prepare_subtitles").performScrollTo().performClick()
        compose.runOnIdle { check(!state.value.preparingSubtitles) }
    }

    @Test
    fun nonNativeBuildDoesNotExposeWholeSegmentChoiceEvenForStaleState() {
        assumeTrue(!BuildConfig.HY_MT_NATIVE)
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = ListenTranslateUiState(
                        sourceLang = "ja", targetLang = "zh",
                        highQualityAvailable = true, highQualityJapanese = true,
                    ),
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                )
            }
        }
        compose.onNodeWithTag("listen_high_quality_japanese").assertDoesNotExist()
        compose.onNodeWithTag("listen_high_quality_cost").assertDoesNotExist()
    }

    @Test
    fun highQualityChoiceIsExplicitAndShowsCostBeforeListening() {
        assumeTrue(BuildConfig.HY_MT_NATIVE)
        val state = mutableStateOf(ListenTranslateUiState(
            sourceLang = "ja", targetLang = "zh", highQualityAvailable = true,
        ))
        var enableCalls = 0
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = { enableCalls++ },
                    onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    onHighQualityJapaneseChange = { state.value = state.value.copy(highQualityJapanese = it) },
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsOff()
        compose.onNodeWithTag("listen_high_quality_cost").performScrollTo()
            .assertIsDisplayed().assertTextContains("1.06 GiB", substring = true)
            .assertTextContains("2 GiB", substring = true)
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().performClick().assertIsOn()
        compose.runOnIdle {
            check(enableCalls == 0)
            check(!state.value.enabled && !state.value.isInstallingModels)
        }
        compose.runOnIdle { state.value = state.value.copy(isInstallingModels = true, message = "Hy-MT 25%") }
        compose.onNodeWithTag("listen_high_quality_japanese").assertIsNotEnabled()
        compose.onNodeWithTag("listen_translate_installing").performScrollTo().assertTextContains("Hy-MT 25%")
        compose.runOnIdle { state.value = state.value.copy(isInstallingModels = false, enabled = true) }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun unavailableAndSourceOnlyChoicesCannotOptInButSavedChoiceCanBeCleared() {
        assumeTrue(BuildConfig.HY_MT_NATIVE)
        val state = mutableStateOf(ListenTranslateUiState(sourceLang = "ja", targetLang = "zh"))
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    onHighQualityJapaneseChange = { state.value = state.value.copy(highQualityJapanese = it) },
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("listen_high_quality_availability").performScrollTo().assertTextContains(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.listen_translate_high_quality_unavailable),
        )
        compose.runOnIdle { state.value = state.value.copy(highQualityAvailable = true, displayMode = ListenDisplayMode.SourceOnly) }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("listen_high_quality_availability").performScrollTo().assertTextContains(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.listen_translate_high_quality_source_only),
        )
        compose.runOnIdle { state.value = state.value.copy(displayMode = ListenDisplayMode.Bilingual, sourceLang = "en") }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("listen_high_quality_availability").performScrollTo().assertTextContains(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.listen_translate_high_quality_unsupported_pair),
        )
        compose.runOnIdle { state.value = state.value.copy(highQualityJapanese = true, highQualityAvailable = false) }
        compose.onNodeWithTag("listen_high_quality_japanese").performScrollTo().assertIsEnabled().performClick().assertIsOff()
    }

    @Test
    fun unconfirmedCaptionAndReadProgressRemainSeparateFromErrorsAndCoverage() {
        val state = mutableStateOf(ListenTranslateUiState(
            enabled = true, currentCaptionUnconfirmed = true,
            recordedReadThroughMs = 12_000L, errorMessage = "翻译失败",
        ))
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_caption_unconfirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("listen_ready_ahead").performScrollTo().assertTextContains("0 秒", substring = true)
        compose.onNodeWithTag("listen_audio_read_through").performScrollTo()
            .assertTextContains("12 秒（不代表字幕已确认）", substring = true)
        compose.onNodeWithTag("listen_translate_error").performScrollTo().assertTextContains("翻译失败")
        compose.runOnIdle { state.value = state.value.copy(currentCaptionUnconfirmed = false) }
        compose.onNodeWithTag("listen_caption_unconfirmed").assertDoesNotExist()
        compose.onNodeWithTag("listen_translate_error").performScrollTo().assertTextContains("翻译失败")
    }

    @Test
    fun installationRunningAndFailureFeedbackAreVisibleBesideTheSwitchWithoutScrolling() {
        val state = mutableStateOf(ListenTranslateUiState(isInstallingModels = true, message = "正在准备模型"))
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = state.value,
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        // The Row owns the merged toggleable semantics; the decorative Switch is checked separately.
        compose.onNodeWithTag("listen_translate_enable").assertExists().assertIsDisplayed().assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("listen_translate_enable_indicator", useUnmergedTree = true).assertExists().assertIsDisplayed()
        compose.onNodeWithTag("listen_translate_installing").assertIsDisplayed().assertTextContains("正在准备模型")
        assertFeedbackPanelHasNotScrolled()
        compose.runOnIdle {
            state.value = ListenTranslateUiState(enabled = true, isProcessing = true, status = ListenTranslateJobStatus.Running)
        }
        compose.onNodeWithTag("listen_translate_status").assertIsDisplayed().assertTextContains("处理中", substring = true)
        compose.onNodeWithTag("listen_translate_enable").assertIsDisplayed().assertIsOn().assertIsEnabled()
        assertFeedbackPanelHasNotScrolled()
        compose.runOnIdle {
            state.value = state.value.copy(
                isProcessing = false, status = ListenTranslateJobStatus.Failed, errorMessage = "翻译失败，请稍后重试",
                activeCue = ListenTranslateCue(
                    1L, PlaybackIdentity("test", "share", "video.mkv"), ListenLanguagePair("ja", "zh"),
                    0L, 3_000L, "原文", "", 1,
                ),
            )
        }
        compose.onNodeWithTag("listen_translation_missing").assertIsDisplayed().assertTextContains("译文尚未就绪")
        compose.onNodeWithTag("listen_translate_error").assertIsDisplayed().assertTextContains("翻译失败", substring = true)
        compose.onNodeWithTag("listen_translate_enable_indicator", useUnmergedTree = true).assertIsDisplayed()
        assertFeedbackPanelHasNotScrolled()
        val switchBounds = compose.onNodeWithTag("listen_translate_enable_indicator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val statusBounds = compose.onNodeWithTag("listen_translate_status").fetchSemanticsNode().boundsInRoot
        assertTrue(switchBounds.bottom <= statusBounds.top)
        compose.runOnIdle { state.value = state.value.copy(enabled = false, activeCue = null) }
        compose.onNodeWithTag("listen_translation_missing").assertDoesNotExist()
        compose.onNodeWithTag("listen_translate_error").assertIsDisplayed()
        compose.onNodeWithTag("listen_translate_enable").assertIsDisplayed().assertIsOff().assertIsEnabled()
        assertFeedbackPanelHasNotScrolled()
    }

    private fun assertFeedbackPanelHasNotScrolled() {
        val scroll = compose.onNodeWithTag("listen_translate_controls").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Feedback must remain visible at the initial scroll position", 0f, scroll.value(), 0f)
    }

    @Test
    fun unrecognizedSpeechCopyDoesNotMisidentifyTheSelectedEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = context.getString(R.string.listen_translate_blank_unrecognized)
        assertFalse(text.contains("Vosk"))
        assertFalse(text.contains("SenseVoice"))
        compose.setContent {
            MaterialTheme {
                ListenTranslateControls(
                    uiState = ListenTranslateUiState(enabled = true, lastBlankReason = ListenBlankReason.UnrecognizedSpeech),
                    onEnabledChange = {}, onSourceLang = {}, onTargetLang = {}, onDisplayMode = {},
                    modifier = Modifier.width(widthDp.dp).height(260.dp),
                )
            }
        }
        compose.onNodeWithTag("listen_translate_blank_reason").assertIsDisplayed().assertTextContains(text)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "panel width {0}dp")
        fun widths(): List<Array<Int>> = listOf(arrayOf(360), arrayOf(840))
    }
}
