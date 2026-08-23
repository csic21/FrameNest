package com.framenest.feature.subtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.player.PlayerTrack

/**
 * Compact subtitle panel: track list, delay, and font size.
 */
@Composable
fun SubtitleControls(
    uiState: SubtitleUiState,
    embeddedTracks: List<PlayerTrack>,
    onSelectOff: () -> Unit,
    onSelectEmbedded: (Int) -> Unit,
    onSelectExternal: (ExternalSubtitleOption) -> Unit,
    onDelayDeltaMs: (Long) -> Unit,
    onFontRelSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("subtitle_controls"),
    ) {
        Text(
            text = stringResource(R.string.subtitle_section_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("subtitle_section_title"),
        )

        if (uiState.scanning) {
            Text(
                text = stringResource(R.string.subtitle_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("subtitle_scanning"),
            )
        }

        uiState.errorMessage?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("subtitle_error"),
            )
        }

        uiState.message?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("subtitle_message"),
            )
        }

        Spacer(Modifier.height(8.dp))

        SubtitleChoiceRow(
            label = stringResource(R.string.subtitle_off),
            selected = uiState.selectedKey == SubtitleSelectionKeys.OFF,
            onClick = onSelectOff,
            testTag = "subtitle_off",
        )

        if (embeddedTracks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.subtitle_embedded),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            embeddedTracks.forEach { track ->
                val key = SubtitleSelectionKeys.embedded(track.id)
                SubtitleChoiceRow(
                    label = track.name,
                    selected = uiState.selectedKey == key,
                    onClick = { onSelectEmbedded(track.id) },
                    testTag = "subtitle_embedded_${track.id}",
                )
            }
        }

        if (uiState.externalOptions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.subtitle_external),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            uiState.externalOptions.forEach { option ->
                val tags = option.languageTags.joinToString(",").ifEmpty { "—" }
                SubtitleChoiceRow(
                    label = "${option.fileName} ($tags)",
                    selected = uiState.selectedKey == option.selectionKey,
                    onClick = { onSelectExternal(option) },
                    testTag = "subtitle_external_${option.fileName}",
                )
            }
        }

        if (embeddedTracks.isEmpty() &&
            uiState.externalOptions.isEmpty() &&
            !uiState.scanning
        ) {
            Text(
                text = stringResource(R.string.subtitle_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("subtitle_none"),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.subtitle_delay_label, uiState.delayMs),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("subtitle_delay_label"),
        )
        val decreaseDelayCd = stringResource(R.string.subtitle_delay_decrease_cd)
        val increaseDelayCd = stringResource(R.string.subtitle_delay_increase_cd)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = { onDelayDeltaMs(-100L) },
                modifier = Modifier
                    .semantics { contentDescription = decreaseDelayCd }
                    .testTag("subtitle_delay_minus"),
            ) {
                Text(stringResource(R.string.subtitle_delay_minus))
            }
            TextButton(
                onClick = { onDelayDeltaMs(100L) },
                modifier = Modifier
                    .semantics { contentDescription = increaseDelayCd }
                    .testTag("subtitle_delay_plus"),
            ) {
                Text(stringResource(R.string.subtitle_delay_plus))
            }
            TextButton(
                onClick = { onDelayDeltaMs(-uiState.delayMs) },
                enabled = uiState.delayMs != 0L,
                modifier = Modifier.testTag("subtitle_delay_reset"),
            ) {
                Text(stringResource(R.string.subtitle_delay_reset))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.subtitle_font_label),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("subtitle_font_row"),
        ) {
            SubtitleFontSizes.ALL.forEach { size ->
                val selected = uiState.fontRelSize == size
                FilterChip(
                    selected = selected,
                    onClick = { onFontRelSize(size) },
                    label = {
                        Text(
                            when (size) {
                                SubtitleFontSizes.SMALL -> stringResource(R.string.subtitle_font_small)
                                SubtitleFontSizes.LARGE -> stringResource(R.string.subtitle_font_large)
                                SubtitleFontSizes.EXTRA_LARGE ->
                                    stringResource(R.string.subtitle_font_xlarge)
                                else -> stringResource(R.string.subtitle_font_normal)
                            },
                        )
                    },
                    modifier = Modifier.testTag("subtitle_font_$size"),
                )
            }
        }
    }
}

@Composable
private fun SubtitleChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
