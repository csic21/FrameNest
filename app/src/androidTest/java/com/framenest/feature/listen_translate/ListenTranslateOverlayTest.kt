package com.framenest.feature.listen_translate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Requested widths; run on both phone and tablet hosts to validate actual viewport constraints. */
@RunWith(Parameterized::class)
class ListenTranslateOverlayTest(private val widthDp: Int) {
    @get:Rule val compose = createComposeRule()

    // Full-width glyphs exercise the measured v2 99/109-character envelope conservatively.
    private val longSource = "日".repeat(99)
    private val longTarget = "中".repeat(109)
    private val longCue = cue(longSource, longTarget)

    @Test
    fun longBilingualCaptionKeepsIndependentTwoLineBudgetsAndExplicitExpansion() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp).height(300.dp)) {
                    ListenTranslateOverlay(longCue, ListenDisplayMode.Bilingual)
                }
            }
        }
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
        for (tag in listOf("listen_caption_source", "listen_caption_target")) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val layout = textLayout(tag)
            assertEquals(2, layout.lineCount)
            assertTrue(layout.hasVisualOverflow)
        }
        val sourceBounds = compose.onNodeWithTag("listen_caption_source", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithTag("listen_caption_target", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(sourceBounds.bottom <= targetBounds.top)
        compose.onNodeWithTag("listen_caption_expand").assertIsDisplayed().performClick()
        compose.onNodeWithTag("listen_caption_full_dialog").assertIsDisplayed()
        compose.onNodeWithTag("listen_caption_full_source").performScrollTo().assertTextEquals(longSource)
        assertTrue(textLayout("listen_caption_full_source").lineCount > 2)
        compose.onNodeWithTag("listen_caption_full_target").performScrollTo().assertTextEquals(longTarget)
        assertTrue(textLayout("listen_caption_full_target").lineCount > 2)
        compose.onNodeWithTag("listen_caption_full_close").performClick()
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
        compose.onNodeWithTag("listen_caption_expand").performClick()
        compose.onNodeWithTag("listen_caption_full_dialog").assertIsDisplayed()
    }

    @Test
    fun expansionHoldsSnapshotAcrossCueChangesButClearsOnResetAndExit() {
        val current = mutableStateOf<ListenTranslateCue?>(longCue)
        val epoch = mutableStateOf(0)
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    Box(Modifier.width(widthDp.dp).height(300.dp)) {
                        ListenTranslateOverlay(
                            cue = current.value,
                            displayMode = ListenDisplayMode.Bilingual,
                            currentCaptionUnconfirmed = true,
                            expansionResetKey = epoch.value,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("listen_caption_pending", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("listen_caption_expand").performClick()
        compose.onNodeWithTag("listen_caption_full_pending").assertIsDisplayed()
        compose.runOnIdle { current.value = cue("次の句段", "下一个句段") }
        compose.onNodeWithTag("listen_caption_full_source").performScrollTo().assertTextEquals(longSource)
        compose.runOnIdle { current.value = null }
        compose.onNodeWithTag("listen_caption_full_dialog").assertIsDisplayed()
        compose.onNodeWithTag("listen_caption_full_target").performScrollTo().assertTextEquals(longTarget)
        compose.runOnIdle { epoch.value++ }
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
        compose.runOnIdle { current.value = longCue }
        compose.onNodeWithTag("listen_caption_expand").performClick()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
    }

    @Test
    fun displayModesUseCueFieldsWithoutSplittingEmbeddedNewlines() {
        val mode = mutableStateOf(ListenDisplayMode.Bilingual)
        val source = "原文の前半\n原文の後半"
        val target = "译文第一行\n译文第二行"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp).height(300.dp)) {
                    ListenTranslateOverlay(cue(source, target), mode.value)
                }
            }
        }
        compose.onNodeWithTag("listen_caption_source", useUnmergedTree = true).assertTextEquals(source)
        compose.onNodeWithTag("listen_caption_target", useUnmergedTree = true).assertTextEquals(target)
        compose.onNodeWithTag("listen_caption_expand").assertDoesNotExist()
        compose.runOnIdle { mode.value = ListenDisplayMode.SourceOnly }
        compose.onNodeWithTag("listen_caption_source", useUnmergedTree = true).assertTextEquals(source)
        compose.onNodeWithTag("listen_caption_target", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { mode.value = ListenDisplayMode.TargetOnly }
        compose.onNodeWithTag("listen_caption_source", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("listen_caption_target", useUnmergedTree = true).assertTextEquals(target)
    }

    @Test
    fun lockedControlsDisableExpansionAndCloseAnExistingDialog() {
        val allowed = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp).height(300.dp)) {
                    ListenTranslateOverlay(longCue, ListenDisplayMode.Bilingual, expansionEnabled = allowed.value)
                }
            }
        }
        compose.onNodeWithTag("listen_caption_expand").assertIsNotEnabled()
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
        compose.runOnIdle { allowed.value = true }
        compose.onNodeWithTag("listen_caption_expand").performClick()
        compose.onNodeWithTag("listen_caption_full_dialog").assertIsDisplayed()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithTag("listen_caption_full_dialog").assertDoesNotExist()
    }

    @Test
    fun textOnlyOverloadRemainsOpaqueAndSupportsFullExpansion() {
        val text = mutableStateOf("旧调用字幕")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp).height(300.dp)) {
                    ListenTranslateOverlay(text = text.value)
                }
            }
        }
        compose.onNodeWithTag("listen_translate_overlay").assertTextEquals(text.value)
        compose.runOnIdle { text.value = ("同一种语言的多行字幕\n").repeat(8) }
        compose.onNodeWithTag("listen_caption_expand").performClick()
        compose.onNodeWithTag("listen_caption_full_legacy").assertTextEquals(text.value)
        compose.onNodeWithTag("listen_caption_full_source").assertDoesNotExist()
        compose.onNodeWithTag("listen_caption_full_target").assertDoesNotExist()
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun cue(source: String, target: String) = ListenTranslateCue(
        id = 1L,
        identity = PlaybackIdentity("test", "share", "video.mkv"),
        languages = ListenLanguagePair("ja", "zh"),
        startMs = 0L, endMs = 25_000L,
        textSrc = source, textTgt = target, rev = 1,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "caption width {0}dp")
        fun widths(): List<Array<Int>> = listOf(arrayOf(360), arrayOf(840))
    }
}
