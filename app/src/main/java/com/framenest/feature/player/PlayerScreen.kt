package com.framenest.feature.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.framenest.R
import com.framenest.core.model.PlaybackRequest
import com.framenest.feature.listen_translate.ListenDisplayMode
import com.framenest.feature.listen_translate.ListenTranslateControls
import com.framenest.feature.listen_translate.ListenTranslateOverlay
import com.framenest.feature.listen_translate.ListenTranslateUiState
import com.framenest.feature.subtitle.ExternalSubtitleOption
import com.framenest.feature.subtitle.SubtitleControls
import com.framenest.feature.subtitle.SubtitleUiState
import com.framenest.player.BufferingPolicy
import com.framenest.player.PlaybackRates
import com.framenest.player.PlayerController
import com.framenest.player.PlayerRotationPolicy
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
 * - **Portrait and landscape**: video always fills a stable viewport; top bar +
 *   controls overlay the surface so chrome show/hide and seek-state text do not
 *   resize the VLC surface (no position / scale jump).
 * Video scale (BestFit by default) is re-applied on rotation so landscape
 * sources are not stretched when the surface size changes.
 *
 * FN-17 locks:
 * - **Controls lock**: hide chrome, swallow gestures, back unlocks first.
 * - **Orientation lock**: freeze current rotation via [Activity.requestedOrientation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    request: PlaybackRequest,
    onBack: () -> Unit,
    onOpenSibling: (siblingPath: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val activity = remember(context) { context.findActivity() }
    val vm: PlayerViewModel = viewModel(
        key = "${request.identity.serverId}|${request.identity.share}|${request.identity.path}",
        factory = PlayerViewModel.Factory(app, request),
    )
    val state by vm.playerState.collectAsStateWithLifecycle()
    val subtitleUi by vm.subtitleUiState.collectAsStateWithLifecycle()
    val listenUi by vm.listenTranslateUiState.collectAsStateWithLifecycle()
    val siblingNav by vm.siblingNavState.collectAsStateWithLifecycle()
    var showSubtitles by remember { mutableStateOf(false) }
    var showListenTranslate by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var orientationLocked by remember { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }
    var autoNextArmed by remember { mutableStateOf(false) }
    var chromeInteractionVersion by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    // Orientation chrome only — not a device-model / width-bucket check.
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Pause + save when app backgrounds; leave path also saves via BackHandler.
                Lifecycle.Event.ON_STOP -> {
                    if (
                        PlayerRotationPolicy.shouldPauseOnStop(
                            isChangingConfigurations = activity?.isChangingConfigurations == true,
                        )
                    ) {
                        vm.onLeaveOrBackground()
                    }
                }
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
    // Controls-lock also immerses so a locked portrait session is not edged with chrome.
    PlayerImmersiveEffect(enabled = landscape || controlsLocked)

    // Freeze / unfreeze activity orientation for the lifetime of this destination.
    PlayerOrientationLockEffect(orientationLocked = orientationLocked)

    // Keep the screen on while actively playing so the device does not lock /
    // dim mid-video. Cleared the moment playback leaves the Playing phase or
    // when the player leaves composition. No WAKE_LOCK permission required.
    val keepScreenOnView = LocalView.current
    DisposableEffect(state.phase, keepScreenOnView) {
        keepScreenOnView.keepScreenOn = state.phase == PlayerState.Phase.Playing
        onDispose { keepScreenOnView.keepScreenOn = false }
    }

    // Error must always expose Retry — drop the control lock automatically.
    LaunchedEffect(state.phase, controlsLocked) {
        if (controlsLocked && PlayerLockPolicy.shouldAutoUnlock(state.phase)) {
            controlsLocked = false
            showUnlockHint = false
            chromeVisible = true
        }
    }

    LaunchedEffect(showUnlockHint, controlsLocked) {
        if (showUnlockHint && controlsLocked) {
            delay(PlayerLockPolicy.UNLOCK_HINT_MS)
            showUnlockHint = false
        }
    }

    // Continuous play: after natural end, auto-open next sibling if present.
    // Cancelled if the user replays or leaves Ended before the delay elapses.
    LaunchedEffect(state.phase, siblingNav.nextPath) {
        if (state.phase == PlayerState.Phase.Ended && siblingNav.nextPath != null) {
            autoNextArmed = true
            delay(AUTO_NEXT_DELAY_MS)
            if (vm.playerState.value.phase == PlayerState.Phase.Ended) {
                val next = siblingNav.nextPath
                if (next != null) {
                    vm.onLeave()
                    onOpenSibling(next)
                }
            }
            autoNextArmed = false
        } else {
            autoNextArmed = false
        }
    }

    val leave: () -> Unit = {
        vm.onLeave()
        onBack()
    }

    val openSibling: (String) -> Unit = { siblingPath ->
        autoNextArmed = false
        vm.onLeave()
        onOpenSibling(siblingPath)
    }

    val unlockControls: () -> Unit = {
        controlsLocked = false
        showUnlockHint = false
        chromeVisible = true
    }

    val lockControls: () -> Unit = {
        controlsLocked = true
        showUnlockHint = false
        chromeVisible = false
        showSubtitles = false
        showListenTranslate = false
        showAudioTracks = false
    }

    BackHandler {
        when (PlayerLockPolicy.consumeBack(controlsLocked)) {
            PlayerLockPolicy.BackAction.Unlock -> unlockControls()
            PlayerLockPolicy.BackAction.Leave -> leave()
        }
    }

    // Keep chrome visible when not actively playing so users can always reach controls.
    // Locked sessions force chrome off (unlock affordance is a separate overlay).
    val showChrome = PlayerLockPolicy.showChrome(
        controlsLocked = controlsLocked,
        chromeVisible = chromeVisible,
        phase = state.phase,
    )
    val showBottomPanels =
        showChrome && (showListenTranslate || showSubtitles || showAudioTracks)

    val markChromeInteraction: () -> Unit = {
        if (!controlsLocked) {
            chromeVisible = true
            chromeInteractionVersion++
        }
    }

    // Normal players get out of the way during playback. Any new interaction
    // restarts the timer; modal controls, paused/error states and lock mode opt out.
    LaunchedEffect(
        state.phase,
        chromeVisible,
        controlsLocked,
        showBottomPanels,
        chromeInteractionVersion,
    ) {
        if (
            PlayerChromePolicy.shouldAutoHide(
                phase = state.phase,
                chromeVisible = chromeVisible,
                controlsLocked = controlsLocked,
                panelOpen = showBottomPanels,
            )
        ) {
            delay(PlayerChromePolicy.AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    val toggleListen: () -> Unit = {
        showListenTranslate = !showListenTranslate
        if (showListenTranslate) {
            showSubtitles = false
            showAudioTracks = false
            markChromeInteraction()
        }
    }
    val toggleSubtitles: () -> Unit = {
        showSubtitles = !showSubtitles
        if (showSubtitles) {
            showListenTranslate = false
            showAudioTracks = false
            markChromeInteraction()
        }
    }
    val toggleAudioTracks: () -> Unit = {
        showAudioTracks = !showAudioTracks
        if (showAudioTracks) {
            showListenTranslate = false
            showSubtitles = false
            markChromeInteraction()
        }
    }
    val onToggleChrome: () -> Unit = {
        if (controlsLocked) {
            showUnlockHint = true
        } else {
            chromeVisible = !chromeVisible
            if (chromeVisible) {
                chromeInteractionVersion++
            } else {
                showSubtitles = false
                showListenTranslate = false
                showAudioTracks = false
            }
        }
    }

    // FN-46: the VLC surface owns one fixed full-window viewport in both
    // orientations. Chrome is always an overlay; neither its visibility nor a
    // transient seek/buffer status can feed a new size into VLCVideoLayout.
    PlayerViewport(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player_screen"),
        viewportTag = if (landscape) "player_landscape_shell" else "player_portrait_shell",
        surface = {
            // This call stays at one stable composition position. Changing
            // orientation or chrome visibility never disposes/re-bounds the
            // AndroidView/VLC surface.
            PlayerSurfaceStack(
                controller = vm.controller,
                state = state,
                listenUi = listenUi,
                chromeVisible = showChrome,
                controlsLocked = controlsLocked,
                onToggleChrome = onToggleChrome,
                onSkipBy = { vm.skipBy(it) },
                onPlay = { vm.play() },
                onRetry = { vm.retry() },
                modifier = Modifier.fillMaxSize(),
            )
        },
        topChrome = if (showChrome) {
            {
                PlayerTopBar(
                    title = vm.displayName,
                    overlay = true,
                    onBack = leave,
                    onToggleListen = toggleListen,
                    onToggleSubtitles = toggleSubtitles,
                    onToggleAudioTracks = toggleAudioTracks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(
                            if (landscape) {
                                "player_landscape_top_bar"
                            } else {
                                "player_portrait_top_bar"
                            },
                        ),
                )
            }
        } else {
            null
        },
        bottomChrome = if (showChrome) {
            {
                PlayerControls(
                    state = state,
                    siblingNav = siblingNav,
                    autoNextArmed = autoNextArmed,
                    onPlay = { vm.play() },
                    onPause = { vm.pause() },
                    onSeek = { vm.seekTo(it) },
                    onCycleVideoScale = { vm.cycleVideoScaleMode() },
                    onCyclePlaybackRate = { vm.cyclePlaybackRate() },
                    onLockControls = lockControls,
                    orientationLocked = orientationLocked,
                    onToggleOrientationLock = {
                        orientationLocked = !orientationLocked
                    },
                    onPrevious = siblingNav.previousPath?.let { p -> { openSibling(p) } },
                    onNext = siblingNav.nextPath?.let { p -> { openSibling(p) } },
                    overlay = true,
                    onUserInteraction = markChromeInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .testTag(
                            if (landscape) {
                                "player_landscape_bottom_chrome"
                            } else {
                                "player_portrait_bottom_chrome"
                            },
                        ),
                )
            }
        } else {
            null
        },
        overlay = {
            if (controlsLocked && showUnlockHint) {
                LockedUnlockOverlay(
                    onUnlock = unlockControls,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (landscape) 32.dp else 24.dp),
                )
            }
        },
    )

    if (showBottomPanels) {
        ModalBottomSheet(
            onDismissRequest = {
                showListenTranslate = false
                showSubtitles = false
                showAudioTracks = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("player_settings_sheet"),
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                PlayerBottomPanels(
                    showListenTranslate = showListenTranslate,
                    showSubtitles = showSubtitles,
                    showAudioTracks = showAudioTracks,
                    listenUi = listenUi,
                    subtitleUi = subtitleUi,
                    embeddedTracks = state.subtitleTracks.filter { it.id >= 0 },
                    audioTracks = state.audioTracks.filter { it.id >= 0 },
                    selectedAudioTrackId = state.selectedAudioTrackId,
                    onListenEnabled = { vm.setListenTranslateEnabled(it) },
                    onSourceLang = { vm.setListenSourceLang(it) },
                    onTargetLang = { vm.setListenTargetLang(it) },
                    onDisplayMode = { vm.setListenDisplayMode(it) },
                    onSelectOff = { vm.selectSubtitleOff() },
                    onSelectEmbedded = { vm.selectEmbeddedSubtitle(it) },
                    onSelectExternal = { vm.selectExternalSubtitle(it) },
                    onDelayDeltaMs = { vm.adjustSubtitleDelayMs(it) },
                    onFontRelSize = { vm.setSubtitleFontRelSize(it) },
                    onSelectAudio = { vm.selectAudioTrack(it) },
                    overlay = false,
                )
            }
        }
    }
}

/**
 * Apply [PlayerLockPolicy.orientationRequest] to the host Activity. Always restores
 * [ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED] when leaving the player so other
 * destinations are not left orientation-locked.
 */
@Composable
private fun PlayerOrientationLockEffect(orientationLocked: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(orientationLocked, activity) {
        val act = activity
        if (act == null) {
            onDispose { }
        } else {
            act.requestedOrientation =
                PlayerLockPolicy.orientationRequest(orientationLocked)
            onDispose {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

@Composable
private fun LockedUnlockOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlockCd = stringResource(R.string.player_unlock_controls_cd)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("player_unlock_overlay"),
    ) {
        Text(
            text = stringResource(R.string.player_locked_hint),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("player_locked_hint"),
        )
        Spacer(Modifier.height(8.dp))
        IconButton(
            onClick = onUnlock,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = unlockCd }
                .testTag("player_unlock"),
        ) {
            Icon(
                imageVector = Icons.Filled.LockOpen,
                contentDescription = stringResource(R.string.player_unlock_controls),
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
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
    onToggleAudioTracks: () -> Unit,
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
            val listenCd = stringResource(R.string.listen_translate_title)
            val audioTracksCd = stringResource(R.string.player_audio_tracks_cd)
            val subtitlesCd = stringResource(R.string.subtitle_section_title)
            IconButton(
                onClick = onToggleListen,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = listenCd }
                    .testTag("player_listen_translate"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = null,
                )
            }
            IconButton(
                onClick = onToggleAudioTracks,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = audioTracksCd }
                    .testTag("player_audio_tracks"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Audiotrack,
                    contentDescription = null,
                )
            }
            IconButton(
                onClick = onToggleSubtitles,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = subtitlesCd }
                    .testTag("player_subtitles"),
            ) {
                Icon(
                    imageVector = Icons.Filled.ClosedCaption,
                    contentDescription = null,
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
    controlsLocked: Boolean,
    onToggleChrome: () -> Unit,
    onSkipBy: (Long) -> Unit,
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
    val lockedCd = stringResource(R.string.player_locked_hint)

    val context = LocalContext.current
    val hostView = LocalView.current
    val gestureController = remember(context, hostView) {
        BrightnessVolumeController(context, hostView)
    }
    var gestureIndicator by remember { mutableStateOf<PlayerGesture?>(null) }
    var skipIndicator by remember { mutableStateOf<SkipIndicator?>(null) }
    // Restore screen brightness to the system value once the player leaves the
    // surface (e.g. navigates back). Volume is a real system setting and is
    // intentionally left at whatever the user set.
    DisposableEffect(gestureController) {
        onDispose { gestureController.restoreBrightness() }
    }
    LaunchedEffect(skipIndicator) {
        if (skipIndicator != null) {
            delay(700)
            skipIndicator = null
        }
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
            // Control lock swallows all surface input except "show unlock".
            // Error still wins so Retry remains reachable after auto-unlock.
            controlsLocked && state.phase != PlayerState.Phase.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onToggleChrome)
                        .semantics { contentDescription = lockedCd }
                        .testTag("player_locked_touch"),
                )
            }
            state.phase == PlayerState.Phase.Error -> {
                ErrorOverlay(
                    message = state.error?.message
                        ?: stringResource(R.string.player_error_generic),
                    retryable = state.error?.retryable == true,
                    onRetry = onRetry,
                )
            }
            state.isSeeking && state.firstFrameReady -> {
                SeekingOverlay()
            }
            state.phase == PlayerState.Phase.Playing && state.firstFrameReady -> {
                // Tap toggles chrome; double-tap left/right skips ±10s; vertical
                // drag left = brightness, right = volume.
                PlayerGestureLayer(
                    toggleCd = toggleCd,
                    onToggleChrome = onToggleChrome,
                    onSkipBack = {
                        onSkipBy(-SkipSeekMath.SKIP_DELTA_MS)
                        skipIndicator = SkipIndicator.Back
                    },
                    onSkipForward = {
                        onSkipBy(SkipSeekMath.SKIP_DELTA_MS)
                        skipIndicator = SkipIndicator.Forward
                    },
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
                BufferingIndicator(
                    state = state,
                    fallbackLabel = stringResource(R.string.player_loading),
                    testTag = "player_phase_loading",
                )
            }
        }

        // Mid-stream rebuffer / post-seek fill while a decoded frame is already up.
        // Preparing uses the branch above; skip when locked so unlock chrome stays clean.
        if (!controlsLocked &&
            !state.isSeeking &&
            BufferingPolicy.showOverlay(state) &&
            state.phase != PlayerState.Phase.Preparing &&
            state.phase != PlayerState.Phase.Idle
        ) {
            BufferingIndicator(
                state = state,
                fallbackLabel = stringResource(R.string.player_buffering),
                testTag = "player_buffering",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (listenUi.enabled && listenUi.overlayText.isNotBlank() &&
            state.phase != PlayerState.Phase.Error
        ) {
            ListenTranslateOverlay(
                text = listenUi.overlayText,
                modifier = Modifier.align(Alignment.BottomCenter),
                bottomPadding = if (state.selectedSubtitleTrackId != null) 72.dp else 20.dp,
            )
        }

        // Brightness / volume feedback shown briefly while the user drags.
        gestureIndicator?.let { gesture ->
            GestureIndicator(
                gesture = gesture,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        skipIndicator?.let { skip ->
            SkipIndicatorOverlay(
                skip = skip,
                modifier = Modifier.align(
                    if (skip == SkipIndicator.Back) Alignment.CenterStart
                    else Alignment.CenterEnd,
                ),
            )
        }
    }
}

@Composable
private fun SeekingOverlay() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SEEKING_INDICATOR_DELAY_MS)
        visible = true
    }
    if (!visible) return

    val label = stringResource(R.string.player_phase_seeking)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { contentDescription = label }
            .testTag("player_seeking"),
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BufferingIndicator(
    state: PlayerState,
    fallbackLabel: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val percent = state.bufferPercent.toInt().coerceIn(0, 100)
    val label = if (state.isBuffering && percent > 0) {
        stringResource(R.string.player_buffering_percent, percent)
    } else {
        fallbackLabel
    }
    val cd = if (state.isBuffering) {
        stringResource(R.string.player_buffering_cd, percent)
    } else {
        label
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .semantics { contentDescription = cd }
            .testTag(testTag),
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PlayerBottomPanels(
    showListenTranslate: Boolean,
    showSubtitles: Boolean,
    showAudioTracks: Boolean,
    listenUi: ListenTranslateUiState,
    subtitleUi: SubtitleUiState,
    embeddedTracks: List<PlayerTrack>,
    audioTracks: List<PlayerTrack>,
    selectedAudioTrackId: Int?,
    onListenEnabled: (Boolean) -> Unit,
    onSourceLang: (String) -> Unit,
    onTargetLang: (String) -> Unit,
    onDisplayMode: (ListenDisplayMode) -> Unit,
    onSelectOff: () -> Unit,
    onSelectEmbedded: (Int) -> Unit,
    onSelectExternal: (ExternalSubtitleOption) -> Unit,
    onDelayDeltaMs: (Long) -> Unit,
    onFontRelSize: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
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
    if (showAudioTracks) {
        AudioTrackControls(
            tracks = audioTracks,
            selectedTrackId = selectedAudioTrackId,
            onSelect = onSelectAudio,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
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
    siblingNav: SiblingNavUiState,
    autoNextArmed: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleVideoScale: () -> Unit,
    onCyclePlaybackRate: () -> Unit,
    onLockControls: () -> Unit,
    orientationLocked: Boolean,
    onToggleOrientationLock: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    overlay: Boolean,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    val position = state.positionMs.coerceIn(0L, if (duration > 0) duration else state.positionMs)
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    // Keep the thumb local while dragging and issue exactly one seek on
    // release. This gives local and SMB files the same seek behavior and avoids
    // overlapping random reads on the NAS.
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
    val rateLabel = PlaybackRates.label(state.playbackRate)
    val rateCd = stringResource(R.string.player_playback_rate_cd, rateLabel)
    val lockCd = stringResource(R.string.player_lock_controls_cd)
    val orientationCd = stringResource(
        if (orientationLocked) {
            R.string.player_orientation_unlock_cd
        } else {
            R.string.player_orientation_lock_cd
        },
    )
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

    val prevCd = stringResource(
        R.string.player_prev_episode_cd,
        siblingNav.previousName ?: "",
    )
    val nextCd = stringResource(
        R.string.player_next_episode_cd,
        siblingNav.nextName ?: "",
    )

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
        if (state.isBuffering && state.phase == PlayerState.Phase.Playing) {
            val pct = state.bufferPercent.toInt().coerceIn(0, 100)
            Text(
                text = stringResource(R.string.player_buffering_percent, pct),
                style = MaterialTheme.typography.labelMedium,
                color = onBgVariant,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("player_status_buffering"),
            )
        }
        if (autoNextArmed && siblingNav.nextName != null) {
            Text(
                text = stringResource(R.string.player_auto_next_hint),
                style = MaterialTheme.typography.labelMedium,
                color = onBgVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("player_auto_next_hint"),
            )
        }
        siblingNav.positionLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onBgVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("player_sibling_position"),
            )
        }
        Slider(
            value = displayProgress,
            onValueChange = { fraction ->
                onUserInteraction()
                scrubbing = true
                scrubFraction = fraction.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                if (!scrubbing) return@Slider
                val targetMs = scrubSeekTargetMs(duration, scrubFraction)
                // End local scrub state before dispatching. onSeek synchronously
                // changes playback phase; dispatching first can recompose/disable
                // this Slider while it still owns the gesture and invoke finish
                // repeatedly with intermediate fractions.
                scrubbing = false
                if (duration > 0L) onSeek(targetMs)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        onUserInteraction()
                        onPrevious?.invoke()
                    },
                    enabled = onPrevious != null,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = prevCd }
                        .testTag("player_prev_episode"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.player_prev_episode),
                        tint = if (onPrevious != null) onBg else onBgVariant,
                    )
                }
                IconButton(
                    onClick = {
                        onUserInteraction()
                        onNext?.invoke()
                    },
                    enabled = onNext != null,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics { contentDescription = nextCd }
                        .testTag("player_next_episode"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.player_next_episode),
                        tint = if (onNext != null) onBg else onBgVariant,
                    )
                }
            }
        }
        PlayerActionButtons(
            playing = state.canPause,
            playEnabled = state.canPause || state.canPlay || state.phase == PlayerState.Phase.Error,
            orientationLocked = orientationLocked,
            rateLabel = rateLabel,
            rateCd = rateCd,
            scaleLabel = scaleLabel,
            scaleCd = scaleCd,
            playCd = playCd,
            pauseCd = pauseCd,
            lockCd = lockCd,
            orientationCd = orientationCd,
            onBg = onBg,
            onPlay = onPlay,
            onPause = onPause,
            onLockControls = onLockControls,
            onToggleOrientationLock = onToggleOrientationLock,
            onCyclePlaybackRate = onCyclePlaybackRate,
            onCycleVideoScale = onCycleVideoScale,
            onUserInteraction = onUserInteraction,
        )
    }
}

@Composable
private fun PlayerActionButtons(
    playing: Boolean,
    playEnabled: Boolean,
    orientationLocked: Boolean,
    rateLabel: String,
    rateCd: String,
    scaleLabel: String,
    scaleCd: String,
    playCd: String,
    pauseCd: String,
    lockCd: String,
    orientationCd: String,
    onBg: Color,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onLockControls: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    onCyclePlaybackRate: () -> Unit,
    onCycleVideoScale: () -> Unit,
    onUserInteraction: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val twoRows = PlayerControlLayoutPolicy.useTwoActionRows(
            widthDp = maxWidth.value,
            fontScale = fontScale,
        )
        val primary: @Composable () -> Unit = {
            PlayerPlayAction(
                playing = playing,
                enabled = playEnabled,
                contentDescription = if (playing) pauseCd else playCd,
                tint = onBg,
                onClick = {
                    onUserInteraction()
                    if (playing) onPause() else onPlay()
                },
            )
            PlayerLockAction(
                contentDescription = lockCd,
                tint = onBg,
                onClick = {
                    onUserInteraction()
                    onLockControls()
                },
            )
            PlayerOrientationAction(
                orientationLocked = orientationLocked,
                contentDescription = orientationCd,
                tint = onBg,
                onClick = {
                    onUserInteraction()
                    onToggleOrientationLock()
                },
            )
        }
        val secondary: @Composable () -> Unit = {
            PlayerTextAction(
                label = rateLabel,
                contentDescription = rateCd,
                icon = Icons.Filled.Speed,
                testTag = "player_playback_rate",
                tint = onBg,
                onClick = {
                    onUserInteraction()
                    onCyclePlaybackRate()
                },
            )
            PlayerTextAction(
                label = scaleLabel,
                contentDescription = scaleCd,
                icon = Icons.Filled.AspectRatio,
                testTag = "player_video_scale",
                tint = onBg,
                onClick = {
                    onUserInteraction()
                    onCycleVideoScale()
                },
            )
        }

        if (twoRows) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) { primary() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) { secondary() }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary()
                secondary()
            }
        }
    }
}

@Composable
private fun PlayerPlayAction(
    playing: Boolean,
    enabled: Boolean,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.contentDescription = contentDescription }
            .testTag(if (playing) "player_pause" else "player_play"),
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = tint,
        )
    }
}

@Composable
private fun PlayerLockAction(
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.contentDescription = contentDescription }
            .testTag("player_lock_controls"),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = tint)
    }
}

@Composable
private fun PlayerOrientationAction(
    orientationLocked: Boolean,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.contentDescription = contentDescription }
            .testTag("player_orientation_lock"),
    ) {
        Icon(
            imageVector = if (orientationLocked) {
                Icons.Filled.ScreenLockRotation
            } else {
                Icons.Filled.ScreenRotation
            },
            contentDescription = null,
            tint = tint,
        )
    }
}

@Composable
private fun PlayerTextAction(
    label: String,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    tint: Color,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.size(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

internal fun scrubSeekTargetMs(durationMs: Long, fraction: Float): Long {
    if (durationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
}
/** Delay before auto-opening the next same-directory video after Ended. */
private const val AUTO_NEXT_DELAY_MS = 1_500L
/** Avoid flashing a spinner when a local paused seek resolves almost immediately. */
private const val SEEKING_INDICATOR_DELAY_MS = 180L

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
    if (state.isSeeking) return stringResource(R.string.player_phase_seeking)
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
        if (state.isBuffering && state.phase == PlayerState.Phase.Playing) {
            append(" · ")
            append(stringResource(R.string.player_buffering))
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
 * Overlay on the playing surface that turns a tap into a chrome toggle, a
 * double-tap into ±10s skip, and a vertical drag into brightness/volume.
 * Splits the surface in half for level control: drag up/down on the **left**
 * changes brightness, on the **right** changes media volume.
 *
 * Uses a single [pointerInput] that distinguishes tap vs. drag manually so the
 * gestures do not steal events from each other. Drag distance is reported
 * **cumulatively from press** (not per-frame) so [BrightnessVolumeController]
 * can map against a fixed baseline.
 *
 * Single-tap chrome toggle is delayed by [SkipSeekMath.DOUBLE_TAP_WINDOW_MS]
 * so a second tap can still become a skip without flashing chrome.
 */
@Composable
private fun PlayerGestureLayer(
    toggleCd: String,
    onToggleChrome: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onGestureStart: (isBrightness: Boolean) -> Unit,
    onGestureDrag: (isBrightness: Boolean, totalDeltaPx: Float, rangePx: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Holder so the pointerInput block can cancel/reschedule chrome without
    // restarting the gesture detector on every recomposition.
    val chromeJob = remember { mutableStateOf<Job?>(null) }
    val lastTapAtMs = remember { mutableLongStateOf(0L) }

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
                                    // A drag cancels a pending single-tap chrome toggle.
                                    chromeJob.value?.cancel()
                                    chromeJob.value = null
                                    lastTapAtMs.longValue = 0L
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
                                    val nowMs = SystemClock.uptimeMillis()
                                    val action = SkipSeekMath.classifyTap(
                                        nowMs = nowMs,
                                        x = downX,
                                        widthPx = size.width.toFloat(),
                                        lastTapAtMs = lastTapAtMs.longValue,
                                    )
                                    when (action) {
                                        SurfaceTapAction.SkipBack -> {
                                            chromeJob.value?.cancel()
                                            chromeJob.value = null
                                            lastTapAtMs.longValue = 0L
                                            onSkipBack()
                                        }
                                        SurfaceTapAction.SkipForward -> {
                                            chromeJob.value?.cancel()
                                            chromeJob.value = null
                                            lastTapAtMs.longValue = 0L
                                            onSkipForward()
                                        }
                                        SurfaceTapAction.SingleTap -> {
                                            chromeJob.value?.cancel()
                                            lastTapAtMs.longValue = nowMs
                                            chromeJob.value = scope.launch {
                                                delay(SkipSeekMath.DOUBLE_TAP_WINDOW_MS)
                                                lastTapAtMs.longValue = 0L
                                                onToggleChrome()
                                            }
                                        }
                                    }
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

private enum class SkipIndicator { Back, Forward }

@Composable
private fun SkipIndicatorOverlay(skip: SkipIndicator, modifier: Modifier = Modifier) {
    val label = when (skip) {
        SkipIndicator.Back -> stringResource(R.string.player_skip_back_label)
        SkipIndicator.Forward -> stringResource(R.string.player_skip_forward_label)
    }
    val cd = when (skip) {
        SkipIndicator.Back -> stringResource(R.string.player_skip_back)
        SkipIndicator.Forward -> stringResource(R.string.player_skip_forward)
    }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = cd }
            .testTag(
                if (skip == SkipIndicator.Back) "player_skip_back" else "player_skip_forward",
            ),
    )
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
