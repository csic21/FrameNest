package com.framenest.feature.player

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framenest.R
import com.framenest.core.model.PlaybackRequest
import com.framenest.feature.subtitle.SubtitleControls
import com.framenest.player.PlayerState

/**
 * Product player UI: loading / ready / playing / paused / ended / error + retry.
 *
 * Opens with prepare → first decoded frame paused ([PlayerState.firstFrameReady]);
 * user taps play to start (product requirement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PlayerViewModel = viewModel(
        key = "${request.identity.serverId}|${request.identity.share}|${request.identity.path}",
        factory = PlayerViewModel.Factory(app, request),
    )
    val state by vm.playerState.collectAsStateWithLifecycle()
    val subtitleUi by vm.subtitleUiState.collectAsStateWithLifecycle()
    var showSubtitles by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onLeaveOrBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onLeaveOrBackground()
        }
    }

    BackHandler {
        vm.onLeaveOrBackground()
        onBack()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("player_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = vm.displayName,
                        modifier = Modifier.testTag("player_title"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            vm.onLeaveOrBackground()
                            onBack()
                        },
                        modifier = Modifier.testTag("player_back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSubtitles = !showSubtitles },
                        modifier = Modifier
                            .semantics { contentDescription = "player_subtitles" }
                            .testTag("player_subtitles"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ClosedCaption,
                            contentDescription = stringResource(R.string.subtitle_section_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .semantics { contentDescription = "player_video_surface" }
                    .testTag("player_video_surface"),
                contentAlignment = Alignment.Center,
            ) {
                VlcVideoSurface(
                    controller = vm.controller,
                    modifier = Modifier.fillMaxSize(),
                )

                when (state.phase) {
                    PlayerState.Phase.Idle,
                    PlayerState.Phase.Preparing,
                    -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.player_loading),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("player_phase_loading"),
                            )
                        }
                    }
                    PlayerState.Phase.Ready -> {
                        // First frame is on the surface; prompt user to play.
                        PlayOverlay(
                            onPlay = { vm.play() },
                            label = stringResource(R.string.player_tap_to_play),
                        )
                    }
                    PlayerState.Phase.Paused,
                    PlayerState.Phase.Ended,
                    -> {
                        PlayOverlay(
                            onPlay = { vm.play() },
                            label = if (state.phase == PlayerState.Phase.Ended) {
                                stringResource(R.string.player_replay)
                            } else {
                                stringResource(R.string.player_tap_to_play)
                            },
                        )
                    }
                    PlayerState.Phase.Playing -> {
                        // Tap video to pause.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { vm.pause() },
                                )
                                .testTag("player_playing_touch"),
                        )
                    }
                    PlayerState.Phase.Error -> {
                        ErrorOverlay(
                            message = state.error?.message
                                ?: stringResource(R.string.player_error_generic),
                            retryable = state.error?.retryable == true,
                            onRetry = { vm.retry() },
                        )
                    }
                }
            }

            PlayerControls(
                state = state,
                onPlay = { vm.play() },
                onPause = { vm.pause() },
                onSeek = { vm.seekTo(it) },
            )

            if (showSubtitles) {
                SubtitleControls(
                    uiState = subtitleUi,
                    embeddedTracks = state.subtitleTracks.filter { it.id >= 0 },
                    onSelectOff = { vm.selectSubtitleOff() },
                    onSelectEmbedded = { vm.selectEmbeddedSubtitle(it) },
                    onSelectExternal = { vm.selectExternalSubtitle(it) },
                    onDelayDeltaMs = { vm.adjustSubtitleDelayMs(it) },
                    onFontRelSize = { vm.setSubtitleFontRelSize(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

@Composable
private fun PlayOverlay(
    onPlay: () -> Unit,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onPlay)
            .padding(24.dp)
            .testTag("player_play_overlay"),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(24.dp)
            .testTag("player_error_overlay"),
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("player_error_message"),
        )
        if (retryable) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("player_retry"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.player_retry))
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = state.positionMs.coerceIn(0L, if (duration > 0) duration else state.positionMs)
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("player_controls"),
    ) {
        Text(
            text = phaseLabel(state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .semantics { contentDescription = "player_status" }
                .testTag("player_status"),
        )
        Slider(
            value = progress,
            onValueChange = { fraction ->
                if (duration > 0) {
                    onSeek((fraction * duration).toLong())
                }
            },
            enabled = (state.isSeekable || duration > 0) &&
                state.phase != PlayerState.Phase.Error &&
                state.phase != PlayerState.Phase.Idle &&
                state.phase != PlayerState.Phase.Preparing,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "player_seek" }
                .testTag("player_seek"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatMs(position)} / ${formatMs(duration)}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("player_time"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onPlay,
                    enabled = state.canPlay || state.phase == PlayerState.Phase.Ready,
                    modifier = Modifier
                        .semantics { contentDescription = "player_play" }
                        .testTag("player_play"),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                }
                IconButton(
                    onClick = onPause,
                    enabled = state.canPause,
                    modifier = Modifier
                        .semantics { contentDescription = "player_pause" }
                        .testTag("player_pause"),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(state: PlayerState): String {
    val phase = when (state.phase) {
        PlayerState.Phase.Idle -> stringResource(R.string.player_phase_idle)
        PlayerState.Phase.Preparing -> stringResource(R.string.player_phase_loading)
        PlayerState.Phase.Ready -> stringResource(R.string.player_phase_ready)
        PlayerState.Phase.Playing -> stringResource(R.string.player_phase_playing)
        PlayerState.Phase.Paused -> stringResource(R.string.player_phase_paused)
        PlayerState.Phase.Ended -> stringResource(R.string.player_phase_ended)
        PlayerState.Phase.Error -> stringResource(R.string.player_phase_error)
    }
    return buildString {
        append(phase)
        if (state.firstFrameReady) append(" · frame")
        state.error?.let { append(" · ${it.code}") }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
