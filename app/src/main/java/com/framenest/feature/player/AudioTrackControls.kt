package com.framenest.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import com.framenest.R
import com.framenest.player.PlayerTrack

/**
 * Compact audio-track picker. Kernel already enumerates tracks; this is the UI gap.
 */
@Composable
fun AudioTrackControls(
    tracks: List<PlayerTrack>,
    selectedTrackId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("audio_track_controls"),
    ) {
        Text(
            text = stringResource(R.string.player_audio_tracks),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("audio_track_section_title"),
        )
        if (tracks.isEmpty()) {
            Text(
                text = stringResource(R.string.player_audio_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("audio_track_none"),
            )
        } else {
            tracks.forEach { track ->
                val selected = track.id == selectedTrackId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(track.id) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp)
                        .testTag("audio_track_${track.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
