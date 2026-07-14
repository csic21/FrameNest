package com.framenest.feature.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import com.framenest.R
import com.framenest.core.model.PlaybackRequest
import com.framenest.feature.listen_translate.ListenDisplayMode
import com.framenest.feature.listen_translate.ListenTranslateControls
import com.framenest.feature.listen_translate.ListenTranslateOverlay
import com.framenest.feature.listen_translate.ListenTranslateUiState
import com.framenest.feature.subtitle.ExternalSubtitleOption
import com.framenest.feature.subtitle.SubtitleControls
import com.framenest.feature.subtitle.SubtitleUiState
import com.framenest.player.PlayerController
import com.framenest.player.PlayerState
import com.framenest.player.PlayerTrack
import com.framenest.player.VideoScaleMode

/**
 * Product player UI: loading / ready / playing / paused / ended / error + retry.
 *
 * Opens with prepare → first decoded frame paused ([PlayerState.firstFrameReady]);
 * user taps play to start (product requirement).
 *
 * Orientation:
 * - **Portrait**: video in the middle column, solid chrome below (and panels).
 * - **Landscape**: video always fills the full window; top bar + controls overlay
 *   the surface so chrome show/hide does **not** resize the video (no scale jump).
 * Video scale (BestFit by default) is re-applied on rotation so landscape
 * sources are not stretched when the surface size changes.
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
    val listenUi by vm.listenTranslateUiState.collectAsStateWithLifecycle()
    var showSubtitles by remember { mutableStateOf(false) }
    var showListenTranslate by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    // Orientation chrome only — not a device-model / width-bucket check.
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Pause + save when app backgrounds; leave path also saves via BackHandler.
                Lifecycle.Event.ON_STOP -> vm.onLeaveOrBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Do not call onLeaveOrBackground again here — BackHandler already did,
            // and a second pass racing ViewModel.onCleared caused leave freezes.
        }
    }

    // Surface refresh on orientation is handled inside VlcVideoSurface — do not
    // also thrash it from here on every state recomposition.

    // Landscape stays immersive so chrome show/hide never changes system-bar
    // insets / window size (that used to re-layout and re-scale the video).
    PlayerImmersiveEffect(enabled = landscape)

    // Keep the screen on while actively playing so the device does not lock /
    // dim mid-video. Cleared the moment playback leaves the Playing phase or
    // when the player leaves composition. No WAKE_LOCK permission required.
    val keepScreenOnView = LocalView.current
    DisposableEffect(state.phase, keepScreenOnView) {
        keepScreenOnView.keepScreenOn = state.phase == PlayerState.Phase.Playing
        onDispose { keepScreenOnView.keepScreenOn = false }
    }

    val leave: () -> Unit = {
        vm.onLeaveOrBackground()
        onBack()
    }

    BackHandler(onBack = leave)

    // Keep chrome visible when not actively playing so users can always reach controls.
    val showChrome = chromeVisible || state.phase != PlayerState.Phase.Playing
    val showBottomPanels = showChrome && (showListenTranslate || showSubtitles)

    val toggleListen: () -> Unit = {
        showListenTranslate = !showListenTranslate
        if (showListenTranslate) {
            showSubtitles = false
            chromeVisible = true
        }
    }
    val toggleSubtitles: () -> Unit = {
        showSubtitles = !showSubtitles
        if (showSubtitles) {
            showListenTranslate = false
            chromeVisible = true
        }
    }
    val onToggleChrome: () -> Unit = {
        chromeVisible = !chromeVisible
        if (!chromeVisible) {
            showSubtitles = false
            showListenTranslate = false
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("player_screen"),
        containerColor = Color.Black,
        // Zero insets: landscape content is full-window; portrait applies scaffold
        // padding only (top bar). Avoid chrome-driven inset jumps in landscape.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Portrait only — landscape top bar is overlaid so video size is fixed.
            if (!landscape && showChrome) {
                PlayerTopBar(
                    title = vm.displayName,
                    overlay = false,
                    onBack = leave,
                    onToggleListen = toggleListen,
                    onToggleSubtitles = toggleSubtitles,
                )
            }
        },
    ) { padding ->
        // Single surface host for both orientations so AndroidView is not disposed
        // on rotate (detachViews mid-play freezes "Playing · first frame ready").
        Box(
            modifier = Modifier
                .then(if (landscape) Modifier else Modifier.padding(padding))
                .fillMaxSize()
                .background(Color.Black)
                .testTag(if (landscape) "player_landscape_shell" else "player_portrait_shell"),
        ) {
            if (landscape) {
                // Full-window surface: top/bottom chrome float above and never
                // change the video layout bounds when toggled.
                PlayerSurfaceStack(
                    controller = vm.controller,
                    state = state,
                    listenUi = listenUi,
                    chromeVisible = showChrome,
                    onToggleChrome = onToggleChrome,
                    onPlay = { vm.play() },
                    onRetry = { vm.retry() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showChrome) {
                    PlayerTopBar(
                        title = vm.displayName,
                        overlay = true,
                        onBack = leave,
                        onToggleListen = toggleListen,
                        onToggleSubtitles = toggleSubtitles,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .testTag("player_landscape_top_bar"),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .testTag("player_landscape_bottom_chrome"),
                    ) {
                        if (showBottomPanels) {
                            PlayerBottomPanels(
                                showListenTranslate = showListenTranslate,
                                showSubtitles = showSubtitles,
                                listenUi = listenUi,
                                subtitleUi = subtitleUi,
                                embeddedTracks = state.subtitleTracks.filter { it.id >= 0 },
                                onListenEnabled = { vm.setListenTranslateEnabled(it) },
                                onSourceLang = { vm.setListenSourceLang(it) },
                                onTargetLang = { vm.setListenTargetLang(it) },
                                onDisplayMode = { vm.setListenDisplayMode(it) },
                                onSelectOff = { vm.selectSubtitleOff() },
                                onSelectEmbedded = { vm.selectEmbeddedSubtitle(it) },
                                onSelectExternal = { vm.selectExternalSubtitle(it) },
                                onDelayDeltaMs = { vm.adjustSubtitleDelayMs(it) },
                                onFontRelSize = { vm.setSubtitleFontRelSize(it) },
                                overlay = true,
                            )
                        }
                        PlayerControls(
                            state = state,
                            onPlay = { vm.play() },
                            onPause = { vm.pause() },
                            onSeek = { vm.seekTo(it) },
                            onCycleVideoScale = { vm.cycleVideoScaleMode() },
                            overlay = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    PlayerSurfaceStack(
                        controller = vm.controller,
                        state = state,
                        listenUi = listenUi,
                        chromeVisible = showChrome,
                        onToggleChrome = onToggleChrome,
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
                            onCycleVideoScale = { vm.cycleVideoScaleMode() },
                            overlay = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (showBottomPanels) {
                            PlayerBottomPanels(
                                showListenTranslate = showListenTranslate,
                                showSubtitles = showSubtitles,
                                listenUi = listenUi,
                                subtitleUi = subtitleUi,
                                embeddedTracks = state.subtitleTracks.filter { it.id >= 0 },
                                onListenEnabled = { vm.setListenTranslateEnabled(it) },
                                onSourceLang = { vm.setListenSourceLang(it) },
                                onTargetLang = { vm.setListenTargetLang(it) },
                                onDisplayMode = { vm.setListenDisplayMode(it) },
                                onSelectOff = { vm.selectSubtitleOff() },
                                onSelectEmbedded = { vm.selectEmbeddedSubtitle(it) },
                                onSelectExternal = { vm.selectExternalSubtitle(it) },
                                onDelayDeltaMs = { vm.adjustSubtitleDelayMs(it) },
                                onFontRelSize = { vm.setSubtitleFontRelSize(it) },
                                overlay = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(
    title: String,
    overlay: Boolean,
    onBack: () -> Unit,
    onToggleListen: () -> Unit,
    onToggleSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("player_title"),
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
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
        actions = {
            IconButton(
                onClick = onToggleListen,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "player_listen_translate" }
                    .testTag("player_listen_translate"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = stringResource(R.string.listen_translate_title),
                )
            }
            IconButton(
                onClick = onToggleSubtitles,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "player_subtitles" }
                    .testTag("player_subtitles"),
            ) {
                Icon(
                    imageVector = Icons.Filled.ClosedCaption,
                    contentDescription = stringResource(R.string.subtitle_section_title),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (overlay) {
                Color.Black.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            titleContentColor = if (overlay) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            navigationIconContentColor = if (overlay) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            actionIconContentColor = if (overlay) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    )
}

/**
 * Optional immersive system bars. Restores bars on dispose / when disabled so
 * other destinations are not left immersive.
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
    controller: PlayerController,
    state: PlayerState,
    listenUi: ListenTranslateUiState,
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

    val context = LocalContext.current
    val hostView = LocalView.current
    val gestureController = remember(context, hostView) {
        BrightnessVolumeController(context, hostView)
    }
    var gestureIndicator by remember { mutableStateOf<PlayerGesture?>(null) }
    // Restore screen brightness to the system value once the player leaves the
    // surface (e.g. navigates back). Volume is a real system setting and is
    // intentionally left at whatever the user set.
    DisposableEffect(gestureController) {
        onDispose { gestureController.restoreBrightness() }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .semantics { contentDescription = surfaceCd }
            .testTag("player_video_surface"),
        contentAlignment = Alignment.Center,
    ) {
        VlcVideoSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Gate playable / "tap to play" on real firstFrameReady (vout).
        when {
            state.phase == PlayerState.Phase.Error -> {
                ErrorOverlay(
                    message = state.error?.message
                        ?: stringResource(R.string.player_error_generic),
                    retryable = state.error?.retryable == true,
                    onRetry = onRetry,
                )
            }
            state.phase == PlayerState.Phase.Playing && state.firstFrameReady -> {
                // Tap toggles chrome; a vertical drag on the left half adjusts
                // screen brightness, on the right half adjusts media volume.
                PlayerGestureLayer(
                    toggleCd = toggleCd,
                    onToggleChrome = onToggleChrome,
                    onGestureStart = { isBrightness ->
                        gestureController.begin(isBrightness)
                        gestureIndicator =
                            if (isBrightness) {
                                PlayerGesture.Brightness(gestureController.brightnessPct())
                            } else {
                                PlayerGesture.Volume(gestureController.volumePct())
                            }
                    },
                    onGestureDrag = { isBrightness, delta, range ->
                        val pct = gestureController.apply(isBrightness, delta, range)
                        gestureIndicator =
                            if (isBrightness) PlayerGesture.Brightness(pct)
                            else PlayerGesture.Volume(pct)
                    },
                    onGestureEnd = { gestureIndicator = null },
                )
            }
            state.firstFrameReady &&
                (
                    state.phase == PlayerState.Phase.Ready ||
                        state.phase == PlayerState.Phase.Paused ||
                        state.phase == PlayerState.Phase.Ended
                    ) -> {
                PlayOverlay(
                    onPlay = onPlay,
                    label = if (state.phase == PlayerState.Phase.Ended) {
                        stringResource(R.string.player_replay)
                    } else {
                        stringResource(R.string.player_tap_to_play)
                    },
                )
            }
            else -> {
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
        }

        if (listenUi.enabled && listenUi.overlayText.isNotBlank() &&
            state.phase != PlayerState.Phase.Error
        ) {
            ListenTranslateOverlay(
                text = listenUi.overlayText,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Brightness / volume feedback shown briefly while the user drags.
        gestureIndicator?.let { gesture ->
            GestureIndicator(
                gesture = gesture,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PlayerBottomPanels(
    showListenTranslate: Boolean,
    showSubtitles: Boolean,
    listenUi: ListenTranslateUiState,
    subtitleUi: SubtitleUiState,
    embeddedTracks: List<PlayerTrack>,
    onListenEnabled: (Boolean) -> Unit,
    onSourceLang: (String) -> Unit,
    onTargetLang: (String) -> Unit,
    onDisplayMode: (ListenDisplayMode) -> Unit,
    onSelectOff: () -> Unit,
    onSelectEmbedded: (Int) -> Unit,
    onSelectExternal: (ExternalSubtitleOption) -> Unit,
    onDelayDeltaMs: (Long) -> Unit,
    onFontRelSize: (Int) -> Unit,
    overlay: Boolean,
) {
    val panelBg = if (overlay) {
        Color.Black.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    if (showListenTranslate) {
        ListenTranslateControls(
            uiState = listenUi,
            onEnabledChange = onListenEnabled,
            onSourceLang = onSourceLang,
            onTargetLang = onTargetLang,
            onDisplayMode = onDisplayMode,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(panelBg),
        )
    }
    if (showSubtitles) {
        SubtitleControls(
            uiState = subtitleUi,
            embeddedTracks = embeddedTracks,
            onSelectOff = onSelectOff,
            onSelectEmbedded = onSelectEmbedded,
            onSelectExternal = onSelectExternal,
            onDelayDeltaMs = onDelayDeltaMs,
            onFontRelSize = onFontRelSize,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(panelBg),
        )
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
    onCycleVideoScale: () -> Unit,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = state.positionMs.coerceIn(0L, if (duration > 0) duration else state.positionMs)
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    // Scrub locally while dragging; seek once on release. Continuous seek-on-drag freezes
    // short/SMB clips while TimeChanged keeps ticking (frozen frame + running clock).
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val displayProgress = if (scrubbing) scrubFraction else progress
    val displayPosition = if (scrubbing && duration > 0L) {
        (scrubFraction * duration).toLong().coerceIn(0L, duration)
    } else {
        position
    }
    val scaleLabel = videoScaleLabel(state.videoScaleMode)
    val scaleCd = stringResource(R.string.player_video_scale_cd, scaleLabel)
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
            value = displayProgress,
            onValueChange = { fraction ->
                // Local scrub only — do not pause. pause→seek→play freezes many SMB/HW
                // paths (clock jumps, last frame sticks). Seek once on release while
                // still playing so the decoder keeps painting.
                scrubbing = true
                scrubFraction = fraction.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                if (duration > 0L) {
                    onSeek((scrubFraction * duration).toLong())
                }
                scrubbing = false
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
                text = "${formatMs(displayPosition)} / ${formatMs(duration)}",
                style = MaterialTheme.typography.labelMedium,
                color = onBg,
                modifier = Modifier.testTag("player_time"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCycleVideoScale,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = scaleCd }
                        .testTag("player_video_scale"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AspectRatio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = onBg,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = scaleLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = onBg,
                    )
                }
                IconButton(
                    onClick = onPlay,
                    enabled = state.canPlay || state.phase == PlayerState.Phase.Error,
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
    }
}

@Composable
private fun videoScaleLabel(mode: VideoScaleMode): String = when (mode) {
    VideoScaleMode.BestFit -> stringResource(R.string.player_scale_best_fit)
    VideoScaleMode.FitScreen -> stringResource(R.string.player_scale_fit_screen)
    VideoScaleMode.Fill -> stringResource(R.string.player_scale_fill)
    VideoScaleMode.Ratio16_9 -> stringResource(R.string.player_scale_16_9)
    VideoScaleMode.Ratio4_3 -> stringResource(R.string.player_scale_4_3)
    VideoScaleMode.Original -> stringResource(R.string.player_scale_original)
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
        // Only annotate while waiting on the first-frame gate — not during Playing
        // (that looked like "stuck at first frame ready").
        if (state.firstFrameReady &&
            (state.phase == PlayerState.Phase.Ready || state.phase == PlayerState.Phase.Paused)
        ) {
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

/**
 * Transient indicator shown while the user drags on the video to adjust
 * brightness (left half) or volume (right half).
 */
private sealed interface PlayerGesture {
    val percent: Int
    data class Brightness(override val percent: Int) : PlayerGesture
    data class Volume(override val percent: Int) : PlayerGesture
}

/**
 * Overlay on the playing surface that turns a tap into a chrome toggle and a
 * vertical drag into brightness/volume control. Splits the surface in half:
 * drag up/down on the **left** changes brightness, on the **right** changes
 * media volume. A short tap anywhere is still a chrome toggle.
 *
 * Uses a single [pointerInput] that distinguishes tap vs. drag manually so the
 * two gestures do not steal events from each other. Drag distance is reported
 * **cumulatively from press** (not per-frame) so [BrightnessVolumeController]
 * can map against a fixed baseline.
 */
@Composable
private fun PlayerGestureLayer(
    toggleCd: String,
    onToggleChrome: () -> Unit,
    onGestureStart: (isBrightness: Boolean) -> Unit,
    onGestureDrag: (isBrightness: Boolean, totalDeltaPx: Float, rangePx: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    val touchSlop = viewConfiguration.touchSlop
                    var downX = 0f
                    var downY = 0f
                    var dragging = false
                    var isBrightness = true
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        // Half the surface height ≈ full 0→100% swing from baseline.
                        val range = (size.height / 2f).coerceAtLeast(1f)
                        when (event.type) {
                            PointerEventType.Press -> {
                                downX = change.position.x
                                downY = change.position.y
                                dragging = false
                            }
                            PointerEventType.Move -> {
                                if (!change.pressed) continue
                                val y = change.position.y
                                // Prefer vertical intent: ignore mostly-horizontal moves
                                // so scrub-like sideways slides do not start a level drag.
                                val dy = abs(y - downY)
                                val dx = abs(change.position.x - downX)
                                if (!dragging && dy > touchSlop && dy >= dx) {
                                    dragging = true
                                    isBrightness = downX < size.width / 2f
                                    onGestureStart(isBrightness)
                                    change.consume()
                                }
                                if (dragging) {
                                    // Cumulative: finger up → positive (increase level).
                                    val totalDelta = downY - y
                                    onGestureDrag(isBrightness, totalDelta, range)
                                    change.consume()
                                }
                            }
                            PointerEventType.Release -> {
                                if (dragging) {
                                    onGestureEnd()
                                } else {
                                    onToggleChrome()
                                }
                                dragging = false
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .semantics { contentDescription = toggleCd }
            .testTag("player_playing_touch"),
    )
}

@Composable
private fun GestureIndicator(gesture: PlayerGesture, modifier: Modifier = Modifier) {
    val icon = when (gesture) {
        is PlayerGesture.Brightness -> Icons.Filled.BrightnessMedium
        is PlayerGesture.Volume -> Icons.AutoMirrored.Filled.VolumeUp
    }
    val fraction = (gesture.percent / 100f).coerceIn(0f, 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("player_gesture_indicator"),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${gesture.percent}%",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        // Track + fill bar so the user sees the level change as they drag.
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * Owns screen brightness and media volume for the vertical-drag gesture.
 * Brightness is applied via [Window] attributes and restored once the player
 * surface leaves composition; volume is a real system setting and is left as-is.
 *
 * Brightness starting point: if no override is active, approximate from the
 * system brightness setting so the indicator matches what the user sees.
 *
 * [apply] expects a **cumulative** drag distance from the press (see
 * [BrightnessVolumeMath]), not a per-frame delta.
 */
private class BrightnessVolumeController(
    private val context: Context,
    private val view: View,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)

    private var baselineBrightness = 0.5f
    private var baselineVolume = 0

    /**
     * Resolve the host [Activity] window. [LocalView] / Compose contexts are often
     * [ContextWrapper]s, so a plain `as? Activity` is null and brightness would
     * silently never apply.
     */
    private fun window(): Window? = view.context.findActivity()?.window

    /** Snapshot the baseline used for the rest of this gesture. */
    fun begin(isBrightness: Boolean) {
        if (isBrightness) {
            val current = window()?.attributes?.screenBrightness ?: -1f
            baselineBrightness = if (current < 0f) currentSystemBrightness() else current
        } else {
            baselineVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    /**
     * Apply a cumulative drag (positive = finger moved up = increase).
     * [rangePx] is the px distance mapped to a full 0→1 / 0→max swing from baseline.
     * Returns the resulting percentage (0..100) for the on-screen indicator.
     */
    fun apply(isBrightness: Boolean, totalDeltaPx: Float, rangePx: Float): Int {
        return if (isBrightness) {
            val target = BrightnessVolumeMath.brightnessTarget(
                baseline = baselineBrightness,
                totalDeltaPx = totalDeltaPx,
                rangePx = rangePx,
            )
            window()?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = target }
            }
            BrightnessVolumeMath.brightnessPercent(target)
        } else {
            val target = BrightnessVolumeMath.volumeTarget(
                baseline = baselineVolume,
                maxVolume = maxVolume,
                totalDeltaPx = totalDeltaPx,
                rangePx = rangePx,
            )
            // flags=0: suppress the system volume toast; we draw our own indicator.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            BrightnessVolumeMath.volumePercent(target, maxVolume)
        }
    }

    fun brightnessPct(): Int {
        val level = window()?.attributes?.screenBrightness?.takeIf { it >= 0f }
            ?: currentSystemBrightness()
        return BrightnessVolumeMath.brightnessPercent(level)
    }

    fun volumePct(): Int {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return BrightnessVolumeMath.volumePercent(cur, maxVolume)
    }

    /** Reset the window override so the system brightness takes over again. */
    fun restoreBrightness() {
        window()?.let { w ->
            if (w.attributes.screenBrightness >= 0f) {
                w.attributes = w.attributes.apply { screenBrightness = -1f }
            }
        }
    }

    private fun currentSystemBrightness(): Float {
        val raw = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            128
        }
        // Floor slightly above 0 so a full dim still leaves the UI readable.
        return (raw / 255f).coerceIn(0.05f, 1f)
    }
}

/** Walk [ContextWrapper] chain — Compose view contexts are rarely a raw [Activity]. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
