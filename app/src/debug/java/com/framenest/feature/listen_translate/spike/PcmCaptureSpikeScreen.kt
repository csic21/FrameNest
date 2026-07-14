package com.framenest.feature.listen_translate.spike

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framenest.feature.player.VlcVideoSurface
import com.framenest.player.PlayerController
import com.framenest.player.PlayerState

@Composable
fun PcmCaptureSpikeScreen(
    controller: PlayerController,
    playerState: PlayerState,
    pcmStats: PcmTapStats,
    onClose: () -> Unit,
    onRestartPcm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .semantics { contentDescription = "pcm_capture_spike_screen" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FN-10 PCM Spike",
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "pcm_spike_close" },
            ) {
                Text("Close")
            }
        }

        Text(
            text = "VLC playback + MediaCodec audio tap (decision 0005 path B)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .semantics { contentDescription = "pcm_spike_video" },
            contentAlignment = Alignment.Center,
        ) {
            VlcVideoSurface(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
            )
            if (!playerState.firstFrameReady &&
                playerState.phase == PlayerState.Phase.Preparing
            ) {
                Text("Preparing…", color = Color.White)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { controller.play() },
                modifier = Modifier.semantics { contentDescription = "pcm_spike_play" },
            ) {
                Text("Play")
            }
            Button(onClick = { controller.pause() }) {
                Text("Pause")
            }
            Button(
                onClick = onRestartPcm,
                modifier = Modifier.semantics { contentDescription = "pcm_spike_restart_tap" },
            ) {
                Text("Restart PCM")
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Player", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "phase=${playerState.phase} firstFrame=${playerState.firstFrameReady} " +
                "t=${playerState.positionMs}ms / ${playerState.durationMs}ms",
            modifier = Modifier.semantics { contentDescription = "pcm_spike_player_status" },
        )

        Spacer(Modifier.height(12.dp))

        Text("PCM tap", style = MaterialTheme.typography.titleMedium)
        val pcmText = buildString {
            appendLine("phase=${pcmStats.phase}")
            appendLine("mime=${pcmStats.mime}")
            appendLine(
                "source=${pcmStats.sourceSampleRateHz} Hz × ${pcmStats.sourceChannelCount} ch",
            )
            appendLine("pcmBytes=${pcmStats.pcmBytesReceived}")
            appendLine("mono16kSamples=${pcmStats.mono16kSamples}")
            appendLine("covered≈${pcmStats.coveredMsApprox} ms")
            appendLine("lastPts=${pcmStats.lastPresentationMs} ms")
            appendLine(
                "rms last=${"%.4f".format(pcmStats.lastRms)} peak=${"%.4f".format(pcmStats.peakRms)}",
            )
            appendLine("chunks=${pcmStats.chunks}")
            if (pcmStats.errorMessage.isNotBlank()) {
                appendLine("error=${pcmStats.errorMessage}")
            }
        }
        Text(
            text = pcmText.trimEnd(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { contentDescription = "pcm_spike_stats" },
        )
    }
}
