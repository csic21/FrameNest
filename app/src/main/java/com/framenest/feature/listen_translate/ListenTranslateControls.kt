package com.framenest.feature.listen_translate

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.data.listen_translate.ListenTranslateJobStatus

@Composable
fun ListenTranslateControls(
    uiState: ListenTranslateUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSourceLang: (String) -> Unit,
    onTargetLang: (String) -> Unit,
    onDisplayMode: (ListenDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("listen_translate_controls"),
    ) {
        Text(
            text = stringResource(R.string.listen_translate_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("listen_translate_title"),
        )
        Text(
            text = stringResource(R.string.listen_translate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = uiState.enabled || uiState.isInstallingModels,
                    enabled = !uiState.isInstallingModels,
                    role = Role.Switch,
                    onValueChange = onEnabledChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.listen_translate_enable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = uiState.enabled || uiState.isInstallingModels,
                onCheckedChange = null,
                enabled = !uiState.isInstallingModels,
                modifier = Modifier.testTag("listen_translate_enable"),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_translate_source_lang),
            style = MaterialTheme.typography.labelMedium,
        )
        LangChipRow(
            selected = uiState.sourceLang,
            onSelect = onSourceLang,
            languages = ListenTranslateLanguages.ASR_SOURCES,
            testTagPrefix = "listen_src",
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_translate_target_lang),
            style = MaterialTheme.typography.labelMedium,
        )
        LangChipRow(
            selected = uiState.targetLang,
            onSelect = onTargetLang,
            languages = ListenTranslateLanguages.ALL,
            testTagPrefix = "listen_tgt",
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_translate_display_mode),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.displayMode == ListenDisplayMode.SourceOnly,
                onClick = { onDisplayMode(ListenDisplayMode.SourceOnly) },
                label = { Text(stringResource(R.string.listen_translate_mode_src)) },
                modifier = Modifier.testTag("listen_mode_src"),
            )
            FilterChip(
                selected = uiState.displayMode == ListenDisplayMode.TargetOnly,
                onClick = { onDisplayMode(ListenDisplayMode.TargetOnly) },
                label = { Text(stringResource(R.string.listen_translate_mode_tgt)) },
                modifier = Modifier.testTag("listen_mode_tgt"),
            )
            FilterChip(
                selected = uiState.displayMode == ListenDisplayMode.Bilingual,
                onClick = { onDisplayMode(ListenDisplayMode.Bilingual) },
                label = { Text(stringResource(R.string.listen_translate_mode_both)) },
                modifier = Modifier.testTag("listen_mode_both"),
            )
        }

        if (uiState.isInstallingModels) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = uiState.message ?: stringResource(R.string.listen_translate_installing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("listen_translate_installing"),
            )
        }

        if (uiState.enabled || uiState.modelsReady || uiState.message != null ||
            uiState.errorMessage != null
        ) {
            Spacer(Modifier.height(8.dp))
            val statusLabel = when (uiState.status) {
                ListenTranslateJobStatus.Idle -> stringResource(R.string.listen_translate_status_idle)
                ListenTranslateJobStatus.Running -> stringResource(R.string.listen_translate_status_running)
                ListenTranslateJobStatus.Partial -> stringResource(R.string.listen_translate_status_partial)
                ListenTranslateJobStatus.Complete -> stringResource(R.string.listen_translate_status_complete)
                ListenTranslateJobStatus.Failed -> stringResource(R.string.listen_translate_status_failed)
            }
            val statusLine = buildString {
                append(stringResource(R.string.listen_translate_status, statusLabel))
                if (uiState.isProcessing) append(" · …")
                if (uiState.modelsReady) {
                    append(" · ")
                    append(stringResource(R.string.listen_translate_models_ready))
                }
                if (uiState.prefetchLookAheadMs > 0L) {
                    append(" · ")
                    append(
                        stringResource(
                            R.string.listen_translate_prefetch,
                            uiState.prefetchLookAheadMs / 1_000L,
                        ),
                    )
                }
                if (uiState.coveredUntilMs > 0L) {
                    append(" · ")
                    append(
                        stringResource(
                            R.string.listen_translate_scanned,
                            uiState.coveredUntilMs / 1000L,
                        ),
                    )
                }
                append(" · ")
                append(
                    stringResource(
                        R.string.listen_translate_generated_count,
                        uiState.generatedCueCount,
                    ),
                )
            }
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("listen_translate_status"),
            )
            uiState.message?.takeUnless { uiState.isInstallingModels }?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.errorMessage == null) {
                val blankMessage = when (uiState.lastBlankReason) {
                    ListenBlankReason.EmptyPcm ->
                        stringResource(R.string.listen_translate_blank_no_audio)
                    ListenBlankReason.NearSilence ->
                        stringResource(R.string.listen_translate_blank_silence)
                    ListenBlankReason.UnrecognizedSpeech ->
                        stringResource(R.string.listen_translate_blank_unrecognized)
                    null -> null
                }
                blankMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("listen_translate_blank_reason"),
                    )
                }
            }
            uiState.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("listen_translate_error"),
                )
            }
        }
    }
}

@Composable
private fun LangChipRow(
    selected: String,
    onSelect: (String) -> Unit,
    languages: List<String>,
    testTagPrefix: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (code in languages) {
            FilterChip(
                selected = selected.equals(code, ignoreCase = true),
                onClick = { onSelect(code) },
                label = { Text(ListenTranslateLanguages.label(code)) },
                modifier = Modifier.testTag("${testTagPrefix}_$code"),
            )
        }
    }
}
