package com.framenest.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.player.PlayerController
import com.framenest.player.PlayerState
import com.framenest.player.PlayerTrack

@Composable
fun PlayerSpikeScreen(
    controller: PlayerController,
    state: PlayerState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "player_spike_screen" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FN-01 Player Spike",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "player_spike_close" },
            ) {
                Text("Close")
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .semantics { contentDescription = "player_video_surface" },
            contentAlignment = Alignment.Center,
        ) {
            VlcVideoSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.firstFrameReady && state.phase == PlayerState.Phase.Preparing) {
                Text(
                    text = "Preparing…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.phase == PlayerState.Phase.Error) {
                Text(
                    text = state.error?.message ?: "Error",
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "player_status" },
        )

        val duration = state.durationMs.coerceAtLeast(0L)
        val position = state.positionMs.coerceIn(0L, if (duration > 0) duration else state.positionMs)
        val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

        Slider(
            value = progress,
            onValueChange = { fraction ->
                if (duration > 0) {
                    controller.seekTo((fraction * duration).toLong())
                }
            },
            enabled = state.isSeekable || duration > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "player_seek" },
        )

        Text(
            text = "${formatMs(position)} / ${formatMs(duration)}",
            style = MaterialTheme.typography.labelMedium,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { controller.play() },
                enabled = state.canPlay || state.phase == PlayerState.Phase.Ready,
                modifier = Modifier.semantics { contentDescription = "player_play" },
            ) {
                Text("Play")
            }
            Button(
                onClick = { controller.pause() },
                enabled = state.canPause,
                modifier = Modifier.semantics { contentDescription = "player_pause" },
            ) {
                Text("Pause")
            }
            Button(
                onClick = {
                    val target = (state.positionMs + 1_000L).coerceAtMost(
                        state.durationMs.takeIf { it > 0 } ?: (state.positionMs + 1_000L),
                    )
                    controller.seekTo(target)
                },
                enabled = state.phase != PlayerState.Phase.Idle &&
                    state.phase != PlayerState.Phase.Error,
                modifier = Modifier.semantics { contentDescription = "player_seek_plus_1s" },
            ) {
                Text("+1s")
            }
            Button(
                onClick = {
                    controller.setVideoScaleMode(state.videoScaleMode.next())
                },
                enabled = state.firstFrameReady,
                modifier = Modifier.semantics { contentDescription = "player_video_scale" },
            ) {
                Text("Scale:${state.videoScaleMode.name}")
            }
        }

        Spacer(Modifier.height(16.dp))

        TrackSection(
            title = "Audio tracks (${state.audioTracks.size})",
            tracks = state.audioTracks,
            selectedId = state.selectedAudioTrackId,
            onSelect = { controller.selectAudioTrack(it) },
        )

        Spacer(Modifier.height(8.dp))

        TrackSection(
            title = "Subtitle tracks (${state.subtitleTracks.size})",
            tracks = state.subtitleTracks,
            selectedId = state.selectedSubtitleTrackId,
            onSelect = { controller.selectSubtitleTrack(it) },
        )
    }
}

@Composable
private fun TrackSection(
    title: String,
    tracks: List<PlayerTrack>,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    if (tracks.isEmpty()) {
        Text(
            text = "(none enumerated yet)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        tracks.forEach { track ->
            val selected = track.id == selectedId
            TextButton(onClick = { onSelect(track.id) }) {
                Text(
                    text = buildString {
                        if (selected) append("✓ ")
                        append("[${track.id}] ")
                        append(track.name)
                    },
                )
            }
        }
    }
}

private fun statusLine(state: PlayerState): String = buildString {
    append("phase=${state.phase}")
    append(" · firstFrame=${state.firstFrameReady}")
    append(" · seekable=${state.isSeekable}")
    append(" · scale=${state.videoScaleMode}")
    append(" · hw=${state.hwDecoderRequested}")
    state.error?.let { append(" · error=${it.code}") }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
