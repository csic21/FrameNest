package com.framenest.feature.player

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framenest.R
import com.framenest.core.model.PlaybackRequest
import com.framenest.player.PlayerState

/**
 * Product player UI: loading / ready / playing / paused / ended / error + retry.
 *
 * Opens with prepare → first decoded frame paused ([PlayerState.firstFrameReady]);
 * user taps play to start (product requirement).
 *
 * FN-08: landscape-friendly chrome (overlay controls), optional immersive system bars
 * while playing with chrome hidden, ≥48dp targets, TalkBack labels, system back.
 *
 * Thin hooks for later waves:
 * - Subtitle panel (FN-06) can sit above [PlayerControls] or in the landscape overlay column.
 * - First-frame still comes from the real surface; list thumbs are FN-07.
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    // Orientation is not a device-model / width-bucket check — allowed for landscape chrome.
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var chromeVisible by remember { mutableStateOf(true) }

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

    // Optional immersive: hide system bars when playing with chrome collapsed.
    val immersive = state.phase == PlayerState.Phase.Playing && !chromeVisible
    PlayerImmersiveEffect(enabled = immersive)

    val leave: () -> Unit = {
        vm.onLeaveOrBackground()
        onBack()
    }

    BackHandler(onBack = leave)

    val showChrome = chromeVisible ||
        state.phase != PlayerState.Phase.Playing

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("player_screen"),
        containerColor = Color.Black,
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        Text(
                            text = vm.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("player_title"),
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = leave,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("player_back"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (landscape) {
                            Color.Black.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        titleContentColor = if (landscape) Color.White else MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = if (landscape) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                )
            }
        },
    ) { padding ->
        if (landscape) {
            // Landscape: video fills screen; chrome overlays bottom (and optional top bar).
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("player_landscape_shell"),
            ) {
                PlayerSurfaceStack(
                    vm = vm,
                    state = state,
                    chromeVisible = showChrome,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onPlay = { vm.play() },
                    onRetry = { vm.retry() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showChrome) {
                    PlayerControls(
                        state = state,
                        onPlay = { vm.play() },
                        onPause = { vm.pause() },
                        onSeek = { vm.seekTo(it) },
                        overlay = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("player_portrait_shell"),
            ) {
                PlayerSurfaceStack(
                    vm = vm,
                    state = state,
                    chromeVisible = showChrome,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onPlay = { vm.play() },
                    onRetry = { vm.retry() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                if (showChrome) {
                    PlayerControls(
                        state = state,
                        onPlay = { vm.play() },
                        onPause = { vm.pause() },
                        onSeek = { vm.seekTo(it) },
                        overlay = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Optional immersive system bars. Restores bars on dispose / when disabled so back stack
 * destinations are not left in a permanent immersive state.
 */
@Composable
private fun PlayerImmersiveEffect(enabled: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(enabled, view) {
        val activity = context as? Activity
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            if (enabled) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun PlayerSurfaceStack(
    vm: PlayerViewModel,
    state: PlayerState,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceCd = stringResource(R.string.player_video_surface)
    val toggleCd = if (chromeVisible) {
        stringResource(R.string.player_controls_hide)
    } else {
        stringResource(R.string.player_controls_show)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .semantics { contentDescription = surfaceCd }
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
                PlayOverlay(
                    onPlay = onPlay,
                    label = stringResource(R.string.player_tap_to_play),
                )
            }
            PlayerState.Phase.Paused,
            PlayerState.Phase.Ended,
            -> {
                PlayOverlay(
                    onPlay = onPlay,
                    label = if (state.phase == PlayerState.Phase.Ended) {
                        stringResource(R.string.player_replay)
                    } else {
                        stringResource(R.string.player_tap_to_play)
                    },
                )
            }
            PlayerState.Phase.Playing -> {
                // Tap video to toggle chrome (and pause still available from controls).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleChrome,
                        )
                        .semantics { contentDescription = toggleCd }
                        .testTag("player_playing_touch"),
                )
            }
            PlayerState.Phase.Error -> {
                ErrorOverlay(
                    message = state.error?.message
                        ?: stringResource(R.string.player_error_generic),
                    retryable = state.error?.retryable == true,
                    onRetry = onRetry,
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
            .minimumInteractiveComponentSize()
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
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_retry"),
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
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = state.positionMs.coerceIn(0L, if (duration > 0) duration else state.positionMs)
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val playCd = stringResource(R.string.player_play)
    val pauseCd = stringResource(R.string.player_pause)
    val seekCd = stringResource(R.string.player_seek)
    val statusText = phaseLabel(state)
    val statusCd = stringResource(R.string.player_status_cd, statusText)

    val bg = if (overlay) {
        Color.Black.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val onBg = if (overlay) Color.White else MaterialTheme.colorScheme.onSurface
    val onBgVariant = if (overlay) {
        Color.White.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("player_controls"),
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = onBgVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .semantics { contentDescription = statusCd }
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
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = seekCd }
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
                color = onBg,
                modifier = Modifier.testTag("player_time"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onPlay,
                    enabled = state.canPlay || state.phase == PlayerState.Phase.Ready,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = playCd }
                        .testTag("player_play"),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onBg)
                }
                IconButton(
                    onClick = onPause,
                    enabled = state.canPause,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = pauseCd }
                        .testTag("player_pause"),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, tint = onBg)
                }
            }
        }
        // FN-06 hook: subtitle track / external subtitle panel can attach below controls.
        Spacer(Modifier.height(0.dp).testTag("player_subtitle_slot"))
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
        if (state.firstFrameReady) {
            append(" · ")
            append(stringResource(R.string.player_first_frame_ready))
        }
        state.error?.let { append(" · ${it.code}") }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
