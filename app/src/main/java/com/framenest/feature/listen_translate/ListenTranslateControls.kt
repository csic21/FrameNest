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
import androidx.compose.ui.unit.dp
import com.framenest.R

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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.listen_translate_enable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = uiState.enabled,
                onCheckedChange = onEnabledChange,
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

        if (uiState.enabled) {
            Spacer(Modifier.height(8.dp))
            val statusLine = buildString {
                append(stringResource(R.string.listen_translate_status, uiState.status.name))
                if (uiState.isProcessing) append(" · …")
                if (uiState.coveredUntilMs > 0L) {
                    append(" · ")
                    append(
                        stringResource(
                            R.string.listen_translate_covered,
                            uiState.coveredUntilMs / 1000L,
                        ),
                    )
                }
            }
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("listen_translate_status"),
            )
            uiState.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    testTagPrefix: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (code in ListenTranslateLanguages.ALL) {
            FilterChip(
                selected = selected.equals(code, ignoreCase = true),
                onClick = { onSelect(code) },
                label = { Text(ListenTranslateLanguages.label(code)) },
                modifier = Modifier.testTag("${testTagPrefix}_$code"),
            )
        }
    }
}
