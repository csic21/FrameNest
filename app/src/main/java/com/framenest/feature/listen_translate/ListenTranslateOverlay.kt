package com.framenest.feature.listen_translate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.data.listen_translate.ListenTranslateCue
import kotlinx.coroutines.delay

/** Avoid briefly flashing a status line between fast source and target publications. */
internal const val LISTEN_TRANSLATION_HINT_DELAY_MS = 600L

/**
 * Bottom-centered listen-translate caption overlay (app-drawn, not libVLC SPU).
 */
@Composable
fun ListenTranslateOverlay(
    text: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 20.dp,
) {
    CaptionOverlay(
        caption = text.takeIf(String::isNotBlank)?.let { CaptionSnapshot(legacy = it) },
        modifier = modifier,
        bottomPadding = bottomPadding,
    )
}

/** Each language has its own line budget; a long source can never hide its translation. */
@Composable
fun ListenTranslateOverlay(
    cue: ListenTranslateCue?,
    displayMode: ListenDisplayMode,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 20.dp,
    currentCaptionUnconfirmed: Boolean = false,
    expansionResetKey: Any? = null,
    expansionEnabled: Boolean = true,
) {
    val translationMissing = isListenCaptionTranslationMissing(cue, displayMode)
    var showTranslationHint by remember(
        cue?.identity, cue?.languages, cue?.startMs, cue?.endMs, cue?.textSrc, displayMode,
        translationMissing, expansionResetKey,
    ) { mutableStateOf(false) }
    LaunchedEffect(cue?.identity, cue?.languages, cue?.startMs, cue?.endMs, cue?.textSrc, displayMode,
        translationMissing, expansionResetKey) {
        if (translationMissing && displayMode == ListenDisplayMode.Bilingual) {
            delay(LISTEN_TRANSLATION_HINT_DELAY_MS)
            showTranslationHint = true
        }
    }
    val targetMissingHint = translationMissing && !currentCaptionUnconfirmed &&
        (displayMode == ListenDisplayMode.TargetOnly || showTranslationHint)
    val source = cue?.textSrc?.takeIf {
        it.isNotBlank() && displayMode != ListenDisplayMode.TargetOnly
    }
    val target = cue?.textTgt?.takeIf {
        it.isNotBlank() && displayMode != ListenDisplayMode.SourceOnly && it != source
    }
    CaptionOverlay(
        caption = if (source != null || target != null || targetMissingHint) {
            CaptionSnapshot(source = source, target = target, unconfirmed = currentCaptionUnconfirmed,
                translationMissing = translationMissing && !currentCaptionUnconfirmed)
        } else null,
        modifier = modifier,
        bottomPadding = bottomPadding,
        expansionResetKey = expansionResetKey,
        expansionEnabled = expansionEnabled,
        translationHintVisible = targetMissingHint,
    )
}

private data class CaptionSnapshot(
    val source: String? = null,
    val target: String? = null,
    val legacy: String? = null,
    val unconfirmed: Boolean = false,
    val translationMissing: Boolean = false,
)

@Composable
private fun CaptionOverlay(
    caption: CaptionSnapshot?,
    modifier: Modifier,
    bottomPadding: Dp,
    expansionResetKey: Any? = null,
    expansionEnabled: Boolean = true,
    translationHintVisible: Boolean = false,
) {
    // Remember, rather than saveable: leaving this player must discard the snapshot.
    // Normal cue updates/gaps keep an open snapshot readable; explicit resets clear it.
    var expanded by remember(expansionResetKey, expansionEnabled) { mutableStateOf<CaptionSnapshot?>(null) }
    var sourceOverflow by remember(caption?.source) { mutableStateOf(false) }
    var targetOverflow by remember(caption?.target) { mutableStateOf(false) }
    var legacyOverflow by remember(caption?.legacy) { mutableStateOf(false) }
    if (caption != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { }
                    .testTag("listen_translate_overlay"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (caption.unconfirmed) {
                    Text(
                        stringResource(R.string.listen_translate_caption_unconfirmed),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("listen_caption_pending"),
                    )
                }
                caption.source?.let {
                    LimitedCaptionText(it, 2, "listen_caption_source") { sourceOverflow = it }
                }
                caption.target?.let {
                    LimitedCaptionText(it, 2, "listen_caption_target") { targetOverflow = it }
                }
                if (caption.translationMissing && translationHintVisible) {
                    Text(
                        stringResource(R.string.listen_translate_translation_missing),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("listen_caption_translation_missing"),
                    )
                }
                caption.legacy?.let {
                    // Compatibility callers provide one opaque string; never infer language by newline.
                    LimitedCaptionText(it, 4, "listen_caption_legacy") { legacyOverflow = it }
                }
                if (sourceOverflow || targetOverflow || legacyOverflow) {
                    TextButton(
                        onClick = { expanded = caption },
                        enabled = expansionEnabled,
                        modifier = Modifier.testTag("listen_caption_expand"),
                    ) {
                        Text(
                            stringResource(R.string.listen_translate_expand_caption),
                            color = Color.White.copy(alpha = if (expansionEnabled) 1f else 0.6f),
                        )
                    }
                }
            }
        }
    }
    expanded?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { expanded = null },
            title = { Text(stringResource(R.string.listen_translate_full_caption_title)) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("listen_caption_full_scroll"),
                    ) {
                        Text(
                            stringResource(R.string.listen_translate_full_caption_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (snapshot.unconfirmed) {
                            Text(
                                stringResource(R.string.listen_translate_caption_unconfirmed),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("listen_caption_full_pending"),
                            )
                        }
                        snapshot.source?.let {
                            Text(stringResource(R.string.listen_translate_mode_src), style = MaterialTheme.typography.labelMedium)
                            Text(it, modifier = Modifier.testTag("listen_caption_full_source"))
                        }
                        snapshot.target?.let {
                            Text(stringResource(R.string.listen_translate_mode_tgt), style = MaterialTheme.typography.labelMedium)
                            Text(it, modifier = Modifier.testTag("listen_caption_full_target"))
                        }
                        if (snapshot.translationMissing) {
                            Text(
                                stringResource(R.string.listen_translate_snapshot_translation_missing),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("listen_caption_full_translation_missing"),
                            )
                        }
                        snapshot.legacy?.let {
                            Text(it, modifier = Modifier.testTag("listen_caption_full_legacy"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { expanded = null },
                    modifier = Modifier.testTag("listen_caption_full_close"),
                ) {
                    Text(stringResource(R.string.listen_translate_close_caption))
                }
            },
            modifier = Modifier.testTag("listen_caption_full_dialog"),
        )
    }
}

@Composable
private fun LimitedCaptionText(text: String, maxLines: Int, tag: String, onOverflow: (Boolean) -> Unit) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { onOverflow(it.hasVisualOverflow) },
        modifier = Modifier.testTag(tag),
    )
}
