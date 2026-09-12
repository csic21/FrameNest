package com.framenest.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RawRes
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed [PlayerController].
 *
 * First-frame contract:
 * 1. [prepare] creates Media with HW decoder enabled when available
 * 2. starts playback once a video layout is attached
 * 3. on first [MediaPlayer.Event.Vout] with count > 0, pauses and marks
 *    [PlayerState.firstFrameReady] so UI can show a decoded frame before play
 *
 * Data paths (decision 0001 / 0007):
 * - Product SMB playback: [MediaSource.Smb] with options-only credentials, no URL userinfo
 * - Seekable descriptor: short-lived extraction/diagnostic callers only
 *
 * Failures are redacted before logging.
 */
class VlcPlayerController(
    appContext: Context,
    private val enableHwDecoder: Boolean = true,
) : PlayerController {

    private val appContext = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(
        PlayerState(hwDecoderRequested = enableHwDecoder),
    )
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var viewsAttached: Boolean = false

    private var pendingSource: MediaSource? = null
    /** Source retained for replay or recovery after a seek reaches EOF. */
    private var currentSource: MediaSource? = null
    /** Applied as VLC's per-media `:start-time` option by [createMedia]. */
    private var pendingStartPositionMs: Long = 0L
    /** Direct SMB resumes with one fast seek when playback starts, not `:start-time`. */
    private var pendingSmbResumePositionMs: Long = 0L
    private var interruptedSeekTargetMs: Long? = null
    private var seekAfterReopenMs: Long? = null
    /** Actual decoder policy for the currently prepared source. */
    private var currentHwDecoderRequested: Boolean = enableHwDecoder
    /** Paused-state target after the next first frame; null means keep playing. */
    private var pauseAfterFirstFramePhase: PlayerState.Phase? = null
    private var awaitingFirstFramePause: Boolean = false
    /**
     * True from [prepare] until the user calls [play]. Keeps phase at Ready after the
     * first decoded frame and ignores stale Playing/EndReached events that would
     * otherwise flash Playing → Paused or jump straight to Ended.
     */
    private var holdForUserPlay: Boolean = false
    /**
     * The latest user/system playback intent. libVLC's pause call is toggle-like, so
     * delayed Playing/Paused events must be reconciled against intent instead of
     * blindly becoming the UI source of truth.
     */
    private var playRequested: Boolean = false
    /**
     * Set when [MediaPlayer.Event.EndReached] arrives while still holding for the user
     * (stale EOF after first-frame pause). [play] restarts from 0 instead of no-op.
     */
    private var endedWhileHolding: Boolean = false
    private var released: Boolean = false
    /** Each input owns its listener; posted events from an older input are discarded. */
    private var inputGeneration = 0L
    private var inputNeedsStop = false
    private var inputStopThread: Thread? = null
    private val remoteSeeks = SeekRequestQueue()
    private val remoteSeekRunnable = Runnable { drainRemoteSeek() }
    private var loadTimeoutArmed = false
    private val loadTimeoutRunnable = Runnable {
        loadTimeoutArmed = false
        if (!released) {
            reportExternalError(
                PlayerError(
                    PlayerError.Code.OpenFailed,
                    "视频加载超时，请检查网络后重试",
                    retryable = true,
                ),
            )
        }
    }
    /** Ignore all input events during teardown and after a terminal state. */
    private var suppressAllTerminalEvents: Boolean = false
    /**
     * True after [MediaPlayer.Event.Opening] for the media started by the latest
     * [tryStartPendingIfReady]. Residual EndReached before Opening is always ignored.
     */
    private var openedCurrentMedia: Boolean = false
    /**
     * After seek while paused/Ready, briefly play so libVLC decodes a frame at the
     * new position, then re-pause. Without this, [setTime] updates the clock only.
     */
    private val seekPreview = SeekPreviewSession()
    /** True while the user is dragging the slider or swiping to scrub. */
    private var scrubbing: Boolean = false
    /** Player-local volume held at zero so preview seeks never leak audio. */
    private var seekPreviewRestoreVolume: Int? = null
    private val seekPreviewPauseRunnable = Runnable { finishSeekPreview(reason = "timeout") }
    private val seekPreviewSettleRunnable = Runnable { finishSeekPreview(reason = "settled") }

    /**
     * Single source of truth for resetting the transient playback-control flags.
     *
     * Called from [prepare] (opening=true) and every teardown / error / release path
     * (opening=false). Centralizing this fixes a class of bugs where individual sites
     * hand-copied a *subset* of the flags and left stale ones set (e.g. a leftover
     * [holdForUserPlay] after a fallback, or a seek preview not cleared on
     * [failOpen]) — which produced phantom "Playing ignored (seek preview)" /
     * "Playing ignored (hold)" events that froze playback.
     *
     * Does NOT touch [suppressAllTerminalEvents]:
     * those are intentional per-call policy, and every caller sets them explicitly for
     * the teardown/open it is about to perform. The seek preview / target are cleared
     * and the preview runnables removed via [cancelSeekPreview].
     */
    private fun resetTransientFlags(opening: Boolean) {
        clearLoadTimeout()
        remoteSeeks.reset()
        interruptedSeekTargetMs = null
        seekAfterReopenMs = null
        mainHandler.removeCallbacks(remoteSeekRunnable)
        playRequested = false
        awaitingFirstFramePause = opening
        holdForUserPlay = opening
        endedWhileHolding = false
        openedCurrentMedia = false
        scrubbing = false
        cancelSeekPreview(pausePlayer = false)
        pauseAfterFirstFramePhase = if (opening) PlayerState.Phase.Ready else null
        slaveSubtitles.reset()
    }
    /** Owns the raw-resource or proxy descriptor until native input has stopped. */
    private var ownedMediaAfd: AssetFileDescriptor? = null
    private val slaveSubtitles = SlaveSubtitleTracker()
    private var subtitleDelayMs: Long = 0L
    private var subtitleFontRelSize: Int = DEFAULT_SUBTITLE_FONT_REL_SIZE
    private var videoScaleMode: VideoScaleMode = VideoScaleMode.BestFit
    private var playbackRate: Float = PlaybackRates.DEFAULT

    private fun eventListener(generation: Long) = MediaPlayer.EventListener { event ->
        // Serialize all event handling onto the main thread. The transient flags
        // below are read/written by main-thread methods (play/pause/seekTo/prepare);
        // handling events here on the libVLC thread caused visibility races. StateFlow
        // itself is thread-safe, but the gating flags are not — post to the main looper
        // so the controller behaves like a single-threaded state machine. release()
        // removes all callbacks, so queued events after release are dropped via the
        // guard inside handleEvent.
        mainHandler.post {
            if (!released && generation == inputGeneration && !suppressAllTerminalEvents) {
                handleEvent(event)
            }
        }
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                openedCurrentMedia = true
                _state.update { it.copy(phase = PlayerState.Phase.Preparing, error = null) }
            }
            MediaPlayer.Event.Buffering -> {
                // libVLC progress 0..100. Do not change phase — rebuffer stays Playing.
                val snap = BufferingPolicy.fromEventProgress(event.buffering)
                _state.update {
                    it.copy(
                        isBuffering = snap.isBuffering,
                        bufferPercent = snap.percent,
                    )
                }
                if (snap.isBuffering) {
                    if (playRequested || awaitingFirstFramePause || seekPreview.active) armLoadTimeout()
                } else if (_state.value.firstFrameReady) {
                    clearLoadTimeout()
                    if (seekPreview.active && currentSource is MediaSource.Smb) {
                        // A preview timeout may have yielded while the NAS was
                        // buffering. Give the decoded frame time to settle now.
                        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
                        mainHandler.postDelayed(seekPreviewPauseRunnable, SEEK_PREVIEW_TIMEOUT_MS)
                    }
                }
            }
            MediaPlayer.Event.Playing -> {
                if (suppressAllTerminalEvents) return@handleEvent
                // Seek-preview playback is internal and must run long enough to paint
                // the target frame before finishSeekPreview() pauses it again.
                if (seekPreview.active) {
                    Log.i(TAG, "Playing ignored (seek preview)")
                    return@handleEvent
                }
                // Hold first-frame gate until user explicitly calls play(): late Playing
                // events after Vout+pause must not flip Ready → Playing or leave the
                // native player running behind a paused UI.
                if (awaitingFirstFramePause || holdForUserPlay) {
                    if (
                        PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                            firstFrameReady = _state.value.firstFrameReady,
                            playRequested = playRequested,
                            seekPreviewActive = seekPreview.active,
                        )
                    ) {
                        pauseNativeIfPlaying("first-frame hold")
                    }
                    Log.i(
                        TAG,
                        "Playing ignored (awaitingFirst=$awaitingFirstFramePause holdForUser=$holdForUserPlay)",
                    )
                    return@handleEvent
                }
                if (
                    PlaybackIntentPolicy.shouldForcePauseOnPlayingEvent(
                        firstFrameReady = _state.value.firstFrameReady,
                        playRequested = playRequested,
                        seekPreviewActive = seekPreview.active,
                    )
                ) {
                    pauseNativeIfPlaying("pause intent")
                    Log.i(TAG, "Playing ignored (pause requested)")
                    return@handleEvent
                }
                _state.update {
                    it.copy(
                        phase = PlayerState.Phase.Playing,
                        durationMs = mediaPlayer?.length?.coerceAtLeast(0L) ?: it.durationMs,
                    )
                }
            }
            MediaPlayer.Event.Paused -> {
                if (suppressAllTerminalEvents) return@handleEvent
                // A delayed Paused event from the user's original pause can arrive
                // after a paused seek has started its muted frame decode. Keep the
                // preview alive; finishSeekPreview clears the session before issuing
                // the intentional final pause, so that event takes the normal path.
                if (seekPreview.active) {
                    val player = mediaPlayer
                    if (player != null && !player.isPlaying) {
                        runCatching { player.play() }
                    }
                    Log.i(TAG, "Paused ignored (seek preview still locating target)")
                    return@handleEvent
                }
                if (!PlaybackIntentPolicy.shouldAcceptPausedEvent(playRequested)) {
                    resumeNativeIfRequested("stale Paused event")
                    Log.i(TAG, "Paused reconciled (play requested)")
                    return@handleEvent
                }
                // Single-direction: only the Playing→Paused transition is driven by this
                // event. The first-frame Ready state is set by [onFirstVout], not here;
                // re-deriving phase from a stale Paused (e.g. after seek-preview while
                // phase == Ended) used to rewrite Ended → Paused and break replay.
                _state.update {
                    if (it.phase == PlayerState.Phase.Playing) it.copy(phase = PlayerState.Phase.Paused) else it
                }
            }
            MediaPlayer.Event.Stopped -> {
                // no-op; release path handles teardown
            }
            MediaPlayer.Event.EndReached -> {
                if (suppressAllTerminalEvents) {
                    Log.i(TAG, "EndReached ignored (suppressed during close/release)")
                    return@handleEvent
                }
                if (!openedCurrentMedia) {
                    Log.i(TAG, "EndReached ignored (no Opening for current media yet)")
                    return@handleEvent
                }
                handleEndReached()
            }
            MediaPlayer.Event.EncounteredError -> {
                if (suppressAllTerminalEvents) {
                    Log.i(TAG, "EncounteredError ignored (suppressed during close/release)")
                    return@handleEvent
                }
                // Cancel seek-preview settle/timeout so a late finishSeekPreview
                // cannot overwrite Error → Paused.
                val message = CredentialRedactor.redact("Playback failed (libVLC EncounteredError)")
                reportExternalError(PlayerError(PlayerError.Code.PlaybackError, message, retryable = true))
            }
            MediaPlayer.Event.TimeChanged -> {
                val time = event.timeChanged.coerceAtLeast(0L)
                remoteSeeks.inFlightTargetMs?.let { target ->
                    if (kotlin.math.abs(time - target) <= SEEK_PREVIEW_TOLERANCE_MS) {
                        remoteSeeks.markSettled()
                        drainRemoteSeek()
                    }
                }
                _state.update { it.copy(positionMs = remoteSeeks.latestTargetMs ?: time) }
                if (seekPreview.active && seekPreview.targetMs >= 0L) {
                    val delta = kotlin.math.abs(time - seekPreview.targetMs)
                    if (delta <= SEEK_PREVIEW_TOLERANCE_MS) {
                        // Clock reached target — but the HW decoder has not necessarily
                        // painted the new frame to the SurfaceView yet (SMB / large MP4
                        // lag by tens to hundreds of ms). Pausing here freezes the
                        // previous frame while the clock shows the new position. Wait
                        // FRAME_SETTLE_MS for the frame to land before re-pausing.
                        scheduleSeekPreviewSettle()
                    }
                }
            }
            MediaPlayer.Event.LengthChanged -> {
                val length = event.lengthChanged.coerceAtLeast(0L)
                _state.update { it.copy(durationMs = length) }
            }
            MediaPlayer.Event.SeekableChanged -> {
                _state.update { it.copy(isSeekable = event.seekable) }
            }
            MediaPlayer.Event.Vout -> {
                if (event.voutCount > 0) {
                    onFirstVout()
                }
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                refreshTracks()
            }
        }
    }

    override fun attachVideoLayout(container: ViewGroup) {
        if (released) return
        ensureEngine()
        // VideoHelper.updateVideoSurfaces() resolves Activity via VLCVideoLayout.context.
        // Using applicationContext makes every setVideoScale / aspect cycle a silent no-op.
        val layout = obtainVideoLayout(container)
        if (layout.parent !== container) {
            (layout.parent as? ViewGroup)?.removeView(layout)
            container.removeAllViews()
            container.addView(
                layout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val player = mediaPlayer ?: return
        if (!viewsAttached) {
            // args: layout, displayManager, subtitles, useTextureView
            player.attachViews(layout, null, true, false)
            viewsAttached = true
            // VideoHelper exists only after attach — set scale once, not on every recomposition.
            applyVideoScale(player)
        }
        tryStartPendingIfReady()
    }

    /**
     * Prefer a UI/Activity context so libVLC can measure the window and apply scale modes.
     * Recreate the layout if a previous instance was built with applicationContext.
     */
    private fun obtainVideoLayout(container: ViewGroup): VLCVideoLayout {
        val hostContext = container.context
        val existing = videoLayout
        val existingActivity = existing?.context?.findActivity()
        val hostActivity = hostContext.findActivity()
        if (
            existing != null &&
            !PlayerRotationPolicy.shouldRecreateVideoLayout(existingActivity, hostActivity)
        ) {
            return existing
        }
        if (existing != null) {
            Log.i(TAG, "Recreating VLCVideoLayout for the current Activity window")
            if (viewsAttached) {
                runCatching { mediaPlayer?.detachViews() }
                viewsAttached = false
            }
            (existing.parent as? ViewGroup)?.removeView(existing)
            videoLayout = null
        }
        return VLCVideoLayout(hostContext).also { videoLayout = it }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    override fun detachVideoLayout(container: ViewGroup) {
        if (released) return
        // AndroidView replacement can attach the shared VLC layout to its new host
        // before the old host's onRelease arrives. Never let that stale callback
        // detach the newly attached surface.
        val layout = videoLayout
        if (!PlayerRotationPolicy.shouldDetachVideoLayout(layout?.parent, container)) return
        val player = mediaPlayer
        if (player != null && viewsAttached) {
            runCatching { player.detachViews() }
            viewsAttached = false
        }
        container.removeView(layout)
    }

    override fun prepare(source: MediaSource, startPositionMs: Long) {
        if (released) {
            _state.update {
                it.copy(
                    phase = PlayerState.Phase.Error,
                    error = PlayerError(PlayerError.Code.Released, "Player released"),
                )
            }
            return
        }
        when (source) {
            is MediaSource.Smb -> {
                if (SmbMediaUri.embedsCredentials(source.uri)) {
                    _state.update {
                        it.copy(
                            phase = PlayerState.Phase.Error,
                            error = PlayerError(
                                PlayerError.Code.InvalidSource,
                                "SMB URI must not embed credentials",
                            ),
                        )
                    }
                    return
                }
            }
            else -> Unit
        }

        ensureEngine()
        discardPendingDescriptor(except = source)
        invalidateInputEvents()
        resetTransientFlags(opening = true)
        currentSource = source
        currentHwDecoderRequested = enableHwDecoder
        pendingSource = source
        val safeStartMs = startPositionMs.coerceAtLeast(0L)
        pendingSmbResumePositionMs = if (source is MediaSource.Smb) safeStartMs else 0L
        pendingStartPositionMs = if (source is MediaSource.Smb) 0L else safeStartMs
        suppressAllTerminalEvents = false
        _state.update {
            PlayerState(
                phase = PlayerState.Phase.Preparing,
                hwDecoderRequested = currentHwDecoderRequested,
                firstFrameReady = false,
                subtitleDelayMs = subtitleDelayMs,
                subtitleFontRelSize = subtitleFontRelSize,
                videoScaleMode = videoScaleMode,
                playbackRate = playbackRate,
            )
        }
        tryStartPendingIfReady()
    }

    /**
     * Stops current media and closes any owned seekable AFD without releasing the engine.
     * Call before tearing down the SMB session that backs a [MediaSource.SeekableDescriptor].
     */
    fun closeCurrentMedia() {
        if (released) return
        discardPendingDescriptor()
        invalidateInputEvents()
        pendingSource = null
        currentSource = null
        pendingStartPositionMs = 0L
        pendingSmbResumePositionMs = 0L
        resetTransientFlags(opening = false)
        suppressAllTerminalEvents = true
        stopInputThenOpenPending()
        _state.update {
            PlayerState(
                phase = PlayerState.Phase.Idle,
                hwDecoderRequested = enableHwDecoder,
                subtitleDelayMs = subtitleDelayMs,
                subtitleFontRelSize = subtitleFontRelSize,
                videoScaleMode = videoScaleMode,
                playbackRate = playbackRate,
            )
        }
    }

    /**
     * Surfaces an open/session error without going through libVLC (e.g. SMB connect).
     * Message must already be safe / redacted by the caller.
     */
    fun reportExternalError(error: PlayerError) {
        if (released) return
        discardPendingDescriptor()
        invalidateInputEvents()
        pendingSource = null
        resetTransientFlags(opening = false)
        val safe = error.copy(message = CredentialRedactor.redact(error.message))
        Log.w(TAG, "external error code=${safe.code} msg=${safe.message}")
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Error,
                error = safe,
                firstFrameReady = false,
                isBuffering = false,
                isSeeking = false,
            )
        }
        stopInputThenOpenPending()
    }

    override fun play() {
        if (released) return
        val player = mediaPlayer ?: return
        val phase = _state.value.phase
        val length = _state.value.durationMs
        val atEnd = length > 0L && _state.value.positionMs >=
            (length - END_EPSILON_MS).coerceAtLeast(0L)
        if (phase == PlayerState.Phase.Ended || endedWhileHolding ||
            (atEnd && remoteSeeks.latestTargetMs == null &&
                (phase == PlayerState.Phase.Ready || phase == PlayerState.Phase.Paused))
        ) {
            val source = currentSource ?: return
            // An EOF input cannot be resumed with setTime. Reopen it from zero and
            // preserve this explicit play request through the first-frame gate.
            prepare(source, 0L)
            playRequested = true
            holdForUserPlay = false
            pauseAfterFirstFramePhase = null
            return
        }
        if (phase == PlayerState.Phase.Error ||
            phase == PlayerState.Phase.Idle ||
            phase == PlayerState.Phase.Preparing
        ) {
            // Nothing useful to resume; caller should retry/prepare again.
            Log.w(TAG, "play() ignored in phase=$phase")
            return
        }
        // User-initiated play cancels pause-scrub preview and continues for real.
        // A pending zero is an explicit seek-to-start, not the history "no resume"
        // sentinel. An already issued seek can simply continue when Play is pressed.
        val queuedSeekTarget = remoteSeeks.latestTargetMs.takeIf { remoteSeeks.hasPending }
            ?: interruptedSeekTargetMs
        interruptedSeekTargetMs = null
        remoteSeeks.reset()
        mainHandler.removeCallbacks(remoteSeekRunnable)
        scrubbing = false
        cancelSeekPreview(pausePlayer = false)
        playRequested = true
        awaitingFirstFramePause = false
        holdForUserPlay = false
        pauseAfterFirstFramePhase = null
        suppressAllTerminalEvents = false
        val time = player.time.coerceAtLeast(0L)
        endedWhileHolding = false
        Log.i(TAG, "play() phase=$phase time=$time length=$length")
        val resumeTarget = queuedSeekTarget ?: pendingSmbResumePositionMs.takeIf { it > 0L }
        if (resumeTarget != null) {
            val targetMs = resumeTarget
            pendingSmbResumePositionMs = 0L
            val applied = runCatching { player.setTime(targetMs, /* fast = */ true) }
                .getOrDefault(-1L)
            _state.update { it.copy(positionMs = targetMs) }
            Log.i(TAG, "SMB resume fast seek target=$targetMs applied=$applied")
        }
        applyPlaybackRate(player)
        player.play()
        if (_state.value.isBuffering) armLoadTimeout()
        _state.update { it.copy(phase = PlayerState.Phase.Playing, error = null) }
    }

    override fun pause() {
        if (released) return
        val player = mediaPlayer ?: return
        if (_state.value.phase == PlayerState.Phase.Preparing) {
            // Audio-focus/lifecycle pause can arrive while an EOF replay is opening.
            playRequested = false
            holdForUserPlay = true
            pauseAfterFirstFramePhase = PlayerState.Phase.Paused
            return
        }
        if (_state.value.phase != PlayerState.Phase.Playing &&
            !seekPreview.active && remoteSeeks.latestTargetMs == null
        ) return
        playRequested = false
        clearLoadTimeout()
        interruptedSeekTargetMs = remoteSeeks.latestTargetMs ?: interruptedSeekTargetMs
        remoteSeeks.reset()
        mainHandler.removeCallbacks(remoteSeekRunnable)
        scrubbing = false
        // MediaPlayer.pause() is toggle-like. Never call it when native playback is
        // already paused, otherwise the UI pause action can resume the video.
        if (player.isPlaying) {
            player.pause()
        }
        cancelSeekPreview(pausePlayer = false)
        _state.update {
            it.copy(
                phase = if (it.firstFrameReady) PlayerState.Phase.Paused else it.phase,
            )
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val phase = _state.value.phase
        val source = currentSource
        val isSmb = source is MediaSource.Smb
        if (phase == PlayerState.Phase.Error || phase == PlayerState.Phase.Idle ||
            phase == PlayerState.Phase.Preparing
        ) {
            return
        }
        val player = mediaPlayer ?: return
        if (!player.isSeekable && _state.value.durationMs <= 0L) {
            Log.w(TAG, "seekTo ignored (not seekable, duration unknown)")
            return
        }
        val duration = player.length.takeIf { it > 0 } ?: _state.value.durationMs
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        // Playing seeks proceed normally. Paused seeks deliberately keep one preview
        // session alive across repeated drags so its final re-pause cannot be lost.
        val preservePausedIntent = PlaybackIntentPolicy.shouldPreservePausedIntentOnSeek(phase)
        if (!preservePausedIntent) {
            cancelSeekPreview(/* pausePlayer = */ false)
        }
        _state.update { it.copy(positionMs = clamped) }
        pendingSmbResumePositionMs = 0L
        interruptedSeekTargetMs = null
        if (isSmb) {
            remoteSeeks.submit(clamped)
            drainRemoteSeek()
            return
        }
        performSeek(
            player = player,
            targetMs = clamped,
            fast = isSmb,
            phase = phase,
            preservePausedIntent = preservePausedIntent,
        )
    }

    private fun drainRemoteSeek() {
        mainHandler.removeCallbacks(remoteSeekRunnable)
        if (released) return
        val target = remoteSeeks.poll(SystemClock.elapsedRealtime())
        if (target != null) {
            val player = mediaPlayer ?: return
            val phase = _state.value.phase
            performSeek(
                player, target, fast = true, phase = phase,
                preservePausedIntent = PlaybackIntentPolicy.shouldPreservePausedIntentOnSeek(phase),
            )
        }
        // A timer also expires a seek whose fast keyframe landed outside the clock
        // tolerance. There is never a FIFO of obsolete network seeks to drain.
        if (remoteSeeks.latestTargetMs != null) {
            mainHandler.postDelayed(remoteSeekRunnable, REMOTE_SEEK_POLL_MS)
        }
    }

    /**
     * Repaint the resting frame after the SurfaceView surface was destroyed by an
     * app-background transition.
     *
     * Same-position muted decode via the paused-seek preview path. Unlike [seekTo],
     * this never clears [pendingSmbResumePositionMs] so a Ready state holding an
     * unapplied SMB resume keeps it for the user's first play().
     */
    fun repaintCurrentFrame() {
        if (released) return
        val snap = _state.value
        if (!snap.firstFrameReady) return
        if (snap.phase != PlayerState.Phase.Ready && snap.phase != PlayerState.Phase.Paused) return
        val player = mediaPlayer ?: return
        if (remoteSeeks.latestTargetMs != null) return
        if (!player.isSeekable && snap.durationMs <= 0L) return
        val duration = player.length.takeIf { it > 0 } ?: snap.durationMs
        val target = snap.positionMs.coerceIn(0L, if (duration > 0) duration else snap.positionMs)
        _state.update { it.copy(positionMs = target) }
        if (currentSource is MediaSource.Smb) {
            remoteSeeks.submit(target)
            drainRemoteSeek()
            return
        }
        performSeek(
            player = player,
            targetMs = target,
            fast = false,
            phase = snap.phase,
            preservePausedIntent = true,
        )
    }

    override fun setScrubbing(active: Boolean) {
        if (released) return
        if (scrubbing == active) return
        scrubbing = active
        val player = mediaPlayer
        if (active) {
            if (player != null) muteSeekPreview(player)
        } else if (
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = false,
                seekPreviewActive = seekPreview.active,
            )
        ) {
            restoreSeekPreviewVolume(player)
        }
    }

    private fun performSeek(
        player: MediaPlayer,
        targetMs: Long,
        fast: Boolean,
        phase: PlayerState.Phase,
        preservePausedIntent: Boolean,
    ) {
        val applied = runCatching { player.setTime(targetMs, fast) }
            .getOrDefault(-1L)
        Log.i(
            TAG,
            "seekTo target=$targetMs applied=$applied seekable=${player.isSeekable} " +
                "phase=$phase playing=${player.isPlaying} fast=$fast",
        )
        // While paused / first-frame Ready, setTime alone does not paint a new frame
        // (HW decoder holds the last surface). Briefly play then re-pause.
        val needFramePreview = _state.value.firstFrameReady && preservePausedIntent
        if (needFramePreview) {
            startOrRetargetSeekPreview(player, targetMs)
        }
    }

    private fun startOrRetargetSeekPreview(player: MediaPlayer, targetMs: Long) {
        val shouldStartNativePreview = seekPreview.startOrRetarget(targetMs)
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        mainHandler.postDelayed(seekPreviewPauseRunnable, SEEK_PREVIEW_TIMEOUT_MS)
        _state.update { it.copy(isSeeking = true) }
        if (shouldStartNativePreview) {
            muteSeekPreview(player)
        }
        // A retarget can arrive while native preview playback is already running.
        // Keep it running and only re-arm the target/timeout; never cancel the final
        // pause obligation. If native playback stopped between events, resume it.
        if (!player.isPlaying) {
            runCatching { player.play() }
                .onFailure { t ->
                    Log.w(TAG, "seek preview play failed: ${t.message}")
                    cancelSeekPreview(pausePlayer = false)
                }
        }
    }

    /**
     * Once the clock has reached the seek target, wait [FRAME_SETTLE_MS] for the HW
     * decoder to paint the new frame before re-pausing. Without this, pausing on the
     * first [TimeChanged] inside tolerance freezes the *previous* frame while the UI
     * shows the new position ("clock moves, picture stuck"). Idempotent — every
     * TimeChanged hit re-arms the timer so we finish only after the last matching
     * clock tick, not the first. A real Vout (frame composited) preempts this via
     * [onFirstVout].
     */
    private fun scheduleSeekPreviewSettle() {
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        mainHandler.postDelayed(seekPreviewSettleRunnable, FRAME_SETTLE_MS)
    }

    private fun finishSeekPreview(reason: String) {
        if (seekPreview.active && currentSource is MediaSource.Smb && _state.value.isBuffering) {
            // Pausing before the buffer fills freezes the previous frame, and
            // clearing isBuffering here would hide the reason from the user.
            // Keep muted decoding until Buffering=100 or the load watchdog fails.
            armLoadTimeout()
            return
        }
        if (!seekPreview.clear()) return
        if (!playRequested) clearLoadTimeout()
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            runCatching { player.pause() }
        }
        if (
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = scrubbing,
                seekPreviewActive = false,
            )
        ) {
            restoreSeekPreviewVolume(player)
        }
        _state.update {
            // Never clobber terminal/open phases (Error/Ended/Preparing/Idle) that
            // may have been set while the settle timer was still armed.
            if (it.phase != PlayerState.Phase.Playing &&
                it.phase != PlayerState.Phase.Ready &&
                it.phase != PlayerState.Phase.Paused
            ) {
                return@update it.copy(isSeeking = false)
            }
            val phase = when {
                holdForUserPlay && it.firstFrameReady -> PlayerState.Phase.Ready
                it.firstFrameReady -> PlayerState.Phase.Paused
                else -> it.phase
            }
            it.copy(
                phase = phase,
                isSeeking = false,
                isBuffering = false,
            )
        }
        Log.i(TAG, "seek preview finished ($reason)")
    }

    private fun cancelSeekPreview(pausePlayer: Boolean) {
        val wasActive = seekPreview.clear()
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        if (wasActive && pausePlayer) {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                runCatching { player.pause() }
            }
        }
        if (released) {
            // Release will stop/discard this player; avoid a native volume call on
            // the navigation frame merely to restore an instance being torn down.
            seekPreviewRestoreVolume = null
        } else if (
            PlaybackIntentPolicy.shouldRestoreTransientVolume(
                scrubbing = scrubbing,
                seekPreviewActive = false,
            )
        ) {
            restoreSeekPreviewVolume(mediaPlayer)
        }
        if (wasActive || _state.value.isSeeking) {
            _state.update { it.copy(isSeeking = false) }
        }
    }

    private fun muteSeekPreview(player: MediaPlayer) {
        if (seekPreviewRestoreVolume != null) return
        val currentVolume = runCatching { player.volume }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: return
        val muted = runCatching { player.setVolume(0) }
            .getOrDefault(-1) >= 0
        if (muted) {
            seekPreviewRestoreVolume = currentVolume
        }
    }

    private fun restoreSeekPreviewVolume(player: MediaPlayer?) {
        val volume = seekPreviewRestoreVolume ?: return
        seekPreviewRestoreVolume = null
        if (player != null) {
            runCatching { player.setVolume(volume) }
                .onFailure { t -> Log.w(TAG, "seek preview volume restore failed: ${t.message}") }
        }
    }

    override fun selectAudioTrack(trackId: Int) {
        if (released) return
        val player = mediaPlayer ?: return
        if (player.setAudioTrack(trackId)) {
            _state.update { it.copy(selectedAudioTrackId = trackId) }
        }
    }

    override fun setPlaybackRate(rate: Float) {
        if (released) return
        val clamped = PlaybackRates.clamp(rate)
        playbackRate = clamped
        applyPlaybackRate(mediaPlayer)
        _state.update { it.copy(playbackRate = clamped) }
        Log.i(TAG, "playbackRate=$clamped")
    }

    override fun selectSubtitleTrack(trackId: Int) {
        if (released) return
        val player = mediaPlayer ?: return
        if (player.setSpuTrack(trackId)) {
            _state.update { it.copy(selectedSubtitleTrackId = trackId) }
        }
    }

    override fun addExternalSubtitle(pathOrUri: String, select: Boolean): Boolean {
        if (released) return false
        val player = mediaPlayer ?: return false
        val trimmed = pathOrUri.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val uri = when {
                trimmed.startsWith("file:", ignoreCase = true) ||
                    trimmed.startsWith("content:", ignoreCase = true) ||
                    trimmed.startsWith("http", ignoreCase = true) -> Uri.parse(trimmed)
                else -> Uri.fromFile(File(trimmed))
            }
            // Never pass SMB credentials here — only local/content paths.
            // Snapshot current SPU ids first so the slave that libVLC injects is
            // not listed as an embedded track.
            slaveSubtitles.captureBeforeAddSlave(currentSpuIds())
            val ok = player.addSlave(IMedia.Slave.Type.Subtitle, uri, select)
            if (ok) {
                // Give libVLC a beat to register ES, then refresh + apply delay.
                mainHandler.post {
                    if (released) return@post
                    refreshTracks()
                    applySpuDelay()
                    if (select) {
                        val lastSlave = mediaPlayer?.spuTracks
                            ?.lastOrNull { it.id >= 0 && slaveSubtitles.isSlave(it.id) }
                            ?: mediaPlayer?.spuTracks?.lastOrNull { it.id >= 0 }
                        if (lastSlave != null) {
                            mediaPlayer?.setSpuTrack(lastSlave.id)
                            _state.update { it.copy(selectedSubtitleTrackId = lastSlave.id) }
                        }
                    }
                }
            } else {
                slaveSubtitles.cancelPending()
                Log.w(TAG, "addSlave returned false for external subtitle")
            }
            ok
        } catch (t: Throwable) {
            val msg = CredentialRedactor.redact(t.message ?: "addSlave failed")
            Log.w(TAG, "addExternalSubtitle failed: $msg")
            // Do not transition player to Error — video continues.
            false
        }
    }

    override fun disableSubtitles() {
        if (released) return
        val player = mediaPlayer ?: return
        if (player.setSpuTrack(DISABLED_SPU_TRACK)) {
            _state.update { it.copy(selectedSubtitleTrackId = DISABLED_SPU_TRACK) }
        }
    }

    override fun setSubtitleDelayMs(delayMs: Long) {
        if (released) return
        subtitleDelayMs = delayMs
        applySpuDelay()
        _state.update { it.copy(subtitleDelayMs = delayMs) }
    }

    override fun setSubtitleFontRelSize(relSize: Int) {
        if (released) return
        val clamped = relSize.coerceIn(MIN_SUBTITLE_FONT_REL_SIZE, MAX_SUBTITLE_FONT_REL_SIZE)
        subtitleFontRelSize = clamped
        _state.update { it.copy(subtitleFontRelSize = clamped) }
        // Best-effort live apply; full effect is on next prepare via media options.
        runCatching {
            // getMedia() retains a native reference; release every temporary read.
            val media = mediaPlayer?.media
            try {
                media?.addOption(":freetype-rel-fontsize=$clamped")
            } finally {
                media?.release()
            }
        }
    }

    override fun setVideoScaleMode(mode: VideoScaleMode) {
        if (released) return
        if (mode == videoScaleMode && _state.value.videoScaleMode == mode) {
            // Still re-apply in case the surface was recreated with a different scale.
            mediaPlayer?.let { applyVideoScale(it) }
            return
        }
        videoScaleMode = mode
        _state.update { it.copy(videoScaleMode = mode) }
        Log.i(TAG, "videoScaleMode=$mode viewsAttached=$viewsAttached")
        mediaPlayer?.let { applyVideoScale(it) }
    }

    override fun refreshVideoSurfaces() {
        if (released || !viewsAttached) return
        // A surface rebuild (orientation/size change) re-triggers Vout; if a seek
        // preview is in flight, that Vout would land in the normal onFirstVout path
        // and overwrite the pause-at-seek-target result. Finalize the preview first
        // so the surfaced frame is exactly the seek target before we rebuild.
        if (seekPreview.active) {
            finishSeekPreview(reason = "surface-refresh")
        }
        val player = mediaPlayer ?: return
        // Layout/orientation change only — do not reassign scale (that also
        // triggers a full surface rebuild and can freeze the current frame).
        runCatching { player.updateVideoSurfaces() }
            .onFailure { t -> Log.w(TAG, "refreshVideoSurfaces failed: ${t.message}") }
    }

    override fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // ViewModel cleanup can originate on IO. Keep snapshotting and input
            // replacement on the same looper; only native teardown runs off-main.
            mainHandler.post { release() }
            return
        }
        if (released) return
        val releaseStartedAtMs = SystemClock.elapsedRealtime()
        released = true
        discardPendingDescriptor()
        inputGeneration++
        val pendingStop = inputStopThread
        pendingSource = null
        currentSource = null
        pendingStartPositionMs = 0L
        pendingSmbResumePositionMs = 0L
        resetTransientFlags(opening = false)
        suppressAllTerminalEvents = true
        mainHandler.removeCallbacksAndMessages(null)

        // Snapshot then null fields so concurrent callers see a released controller.
        val player = mediaPlayer
        mediaPlayer = null
        val layout = videoLayout
        videoLayout = null
        val wasAttached = viewsAttached
        viewsAttached = false
        val vlc = libVlc
        libVlc = null
        val afd = ownedMediaAfd
        ownedMediaAfd = null

        /** Hide/remove the SurfaceView host immediately; contains no libVLC call. */
        fun removeVideoOutputView() {
            if (layout != null) {
                layout.visibility = View.INVISIBLE
                (layout.parent as? ViewGroup)?.let { parent ->
                    runCatching { parent.removeView(layout) }
                }
            }
            Log.d(
                TAG,
                "release visible output removed " +
                    "dt=${SystemClock.elapsedRealtime() - releaseStartedAtMs}ms",
            )
        }

        /** libVLC view callbacks touch Android Views and must run on main. */
        fun detachVideoOutputOnMain() {
            if (player != null) {
                runCatching {
                    player.setEventListener(null)
                    if (wasAttached) {
                        runCatching { player.detachViews() }
                    }
                }
            }
            Log.d(
                TAG,
                "release views detached dt=${SystemClock.elapsedRealtime() - releaseStartedAtMs}ms",
            )
        }

        /**
         * Native stop can wait on the decoder or SMB input. It runs before main-thread
         * detach so libVLC's active vout has already quiesced when Surface callbacks
         * are removed.
         */
        fun stopNative() {
            // A prepare/replay may already be stopping this same native input.
            // Join that worker before final teardown; never race stop with release.
            pendingStop?.join()
            runCatching { player?.stop() }
            Log.d(
                TAG,
                "release native stop complete " +
                    "dt=${SystemClock.elapsedRealtime() - releaseStartedAtMs}ms",
            )
        }

        /** Native release and proxy AFD close always remain off-main. */
        fun releaseNative() {
            runCatching { player?.release() }
            if (afd != null) runCatching { afd.close() }
            runCatching { vlc?.release() }
            Log.d(
                TAG,
                "release complete dt=${SystemClock.elapsedRealtime() - releaseStartedAtMs}ms",
            )
        }

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) {
            removeVideoOutputView()
        } else if (!mainHandler.post(::removeVideoOutputView)) {
            Log.w(TAG, "release: could not post video view removal")
        }
        PlayerReleaseExecutor.launchPhased(
            threadName = THREAD_NAME_VLC_RELEASE,
            stopNative = ::stopNative,
            postToMain = { action ->
                if (!mainHandler.post(action)) {
                    throw IllegalStateException("main handler rejected detach")
                }
            },
            detachOnMain = ::detachVideoOutputOnMain,
            releaseNative = ::releaseNative,
            onMainPhaseTimeout = {
                Log.w(TAG, "release: main detach timed out; continuing native release")
            },
        )

        _state.value = PlayerState(
            phase = PlayerState.Phase.Idle,
            hwDecoderRequested = enableHwDecoder,
            videoScaleMode = videoScaleMode,
            playbackRate = playbackRate,
        )
    }

    private fun applyPlaybackRate(player: MediaPlayer?) {
        if (player == null) return
        runCatching { player.setRate(playbackRate) }
            .onFailure { t -> Log.w(TAG, "setRate failed: ${t.message}") }
    }

    private fun applyVideoScale(player: MediaPlayer) {
        if (!viewsAttached) {
            Log.w(TAG, "applyVideoScale skipped (views not attached) mode=$videoScaleMode")
            return
        }
        val layoutCtx = videoLayout?.context
        if (layoutCtx != null && layoutCtx.findActivity() == null) {
            Log.w(
                TAG,
                "applyVideoScale: VLCVideoLayout has non-Activity context; " +
                    "libVLC scale modes will not apply until re-attach",
            )
        }
        runCatching {
            // setVideoScale already calls VideoHelper.updateVideoSurfaces().
            // Do not call updateVideoSurfaces again — double rebuild freezes frames.
            player.videoScale = videoScaleMode.toLibVlcScaleType()
            Log.i(TAG, "applyVideoScale ok mode=$videoScaleMode")
        }.onFailure { t ->
            Log.w(TAG, "applyVideoScale failed: ${t.message}")
        }
    }

    private fun closeOwnedMediaAfd() {
        val afd = ownedMediaAfd
        ownedMediaAfd = null
        if (afd != null) {
            runCatching { afd.close() }
        }
    }

    private fun ensureEngine() {
        if (libVlc != null && mediaPlayer != null) return
        val options = ArrayList<String>().apply {
            // Prefer OpenSL ES; keep options minimal for spike reproducibility.
            add("--aout=opensles")
            add("--audio-time-stretch")
            // Reduce noise; app logs redacted errors via FrameNestPlayer.
            add("--verbose=0")
        }
        val vlc = LibVLC(appContext, options)
        libVlc = vlc
        val player = MediaPlayer(vlc)
        mediaPlayer = player
    }

    private fun invalidateInputEvents() {
        inputGeneration++
        suppressAllTerminalEvents = true
        mediaPlayer?.setEventListener(null)
    }

    private fun discardPendingDescriptor(except: MediaSource? = null) {
        val afd = (pendingSource as? MediaSource.SeekableDescriptor)?.assetFileDescriptor ?: return
        if (afd !== ownedMediaAfd &&
            afd !== (except as? MediaSource.SeekableDescriptor)?.assetFileDescriptor
        ) {
            runCatching { afd.close() }
        }
    }

    /** One stop worker per controller; newer prepare calls only replace pendingSource. */
    private fun stopInputThenOpenPending() {
        if (inputStopThread != null) return
        val player = mediaPlayer ?: return
        if (!inputNeedsStop) {
            closeOwnedMediaAfdUnlessReused()
            tryStartPendingIfReady()
            return
        }
        inputStopThread = PlayerReleaseExecutor.launch("framenest-vlc-reopen") {
            val stopped = runCatching { player.stop() }
            mainHandler.post {
                inputStopThread = null
                if (released) return@post
                if (stopped.isFailure) {
                    failOpen("Unable to stop previous playback; reopen the player")
                    return@post
                }
                // stop has joined the old native input. Only now may its descriptor
                // and Media be released, or a listener for the new input installed.
                player.media = null
                inputNeedsStop = false
                closeOwnedMediaAfdUnlessReused()
                tryStartPendingIfReady()
            }
        }
    }

    private fun closeOwnedMediaAfdUnlessReused() {
        val next = (pendingSource as? MediaSource.SeekableDescriptor)?.assetFileDescriptor
        if (ownedMediaAfd !== next) closeOwnedMediaAfd()
    }

    private fun tryStartPendingIfReady() {
        val source = pendingSource ?: return
        val player = mediaPlayer ?: return
        if (!viewsAttached) return
        armLoadTimeout()
        if (inputStopThread != null) return
        if (inputNeedsStop) {
            stopInputThenOpenPending()
            return
        }
        val startPositionMs = pendingStartPositionMs.coerceAtLeast(0L)
        pendingSource = null
        pendingStartPositionMs = 0L
        openedCurrentMedia = false
        suppressAllTerminalEvents = false
        if (source is MediaSource.SeekableDescriptor) ownedMediaAfd = source.assetFileDescriptor
        var media: Media? = null
        try {
            media = createMedia(source, startPositionMs) ?: return
            player.setEventListener(eventListener(inputGeneration))
            player.media = media
            inputNeedsStop = true
            applyPlaybackRate(player)
            player.play()
        } catch (t: Throwable) {
            failOpen(CredentialRedactor.redact(t.message ?: "Failed to open media"))
        } finally {
            // setMedia retains its own reference, including when play throws.
            media?.release()
        }
    }

    private fun armLoadTimeout() {
        if (loadTimeoutArmed) return
        loadTimeoutArmed = true
        mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun clearLoadTimeout() {
        loadTimeoutArmed = false
        mainHandler.removeCallbacks(loadTimeoutRunnable)
    }

    private fun handleEndReached() {
        val pendingTarget = remoteSeeks.latestTargetMs
        val source = currentSource
        if (source != null && remoteSeeks.hasPending && pendingTarget != null &&
            pendingTarget < (_state.value.durationMs - END_EPSILON_MS).coerceAtLeast(0L)
        ) {
            // An older seek hit EOF while the user already dragged back. Keep the
            // latest target and decode it on a fresh input instead of dropping it.
            val shouldPlay = playRequested
            val pausedPhase = _state.value.phase
            prepare(source, 0L)
            playRequested = shouldPlay
            holdForUserPlay = !shouldPlay
            pauseAfterFirstFramePhase = if (shouldPlay) null else pausedPhase
            seekAfterReopenMs = pendingTarget
            return
        }
        clearLoadTimeout()
        remoteSeeks.reset()
        mainHandler.removeCallbacks(remoteSeekRunnable)
        // EOF is already a native rest state. Clear any paused-seek preview so its
        // timeout cannot retain the locating UI or restore an obsolete phase later.
        cancelSeekPreview(pausePlayer = false)
        val player = mediaPlayer
        val pos = player?.time ?: _state.value.positionMs
        val len = player?.length ?: _state.value.durationMs
        Log.i(
            TAG,
            "EndReached pos=$pos len=$len firstFrame=${_state.value.firstFrameReady} " +
                "awaitingFirst=$awaitingFirstFramePause holdForUser=$holdForUserPlay " +
                "phase=${_state.value.phase}",
        )
        // Spurious EOF during open (bad FD / zero-length / demux) must not
        // look like a finished movie with a permanent loading spinner.
        if (awaitingFirstFramePause || !_state.value.firstFrameReady) {
            // Clear seek-preview + hold flags so a late settle cannot leave Error.
            failOpen("Playback ended before a video frame was ready")
            return
        }
        // Delayed/stale EndReached while still holding first frame (user has not
        // tapped play) must not jump Ready → Ended. Common after stop()+setMedia
        // or pause-on-Vout races with short samples / proxy FDs.
        if (holdForUserPlay) {
            endedWhileHolding = true
            Log.w(
                TAG,
                "EndReached ignored while holding first frame for user play " +
                    "(will restart on play)",
            )
            return
        }
        awaitingFirstFramePause = false
        holdForUserPlay = false
        playRequested = false
        suppressAllTerminalEvents = true
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Ended,
                isBuffering = false,
                isSeeking = false,
                positionMs = it.durationMs.takeIf { d -> d > 0 } ?: pos.coerceAtLeast(0L),
            )
        }
    }

    private fun createMedia(source: MediaSource, startPositionMs: Long = 0L): Media? {
        val vlc = libVlc ?: return null
        val media: Media = when (source) {
            is MediaSource.LocalFile -> {
                val file = File(source.path)
                if (!file.exists()) {
                    failOpen("Local file not found")
                    return null
                }
                Media(vlc, source.path)
            }
            is MediaSource.ContentUri -> Media(vlc, source.uri)
            is MediaSource.RawResource -> createRawMedia(vlc, source.resId) ?: return null
            is MediaSource.SeekableDescriptor -> {
                val afd = source.assetFileDescriptor
                val declared = afd.length
                Log.i(
                    TAG,
                    "Opening seekable descriptor label=${source.debugLabel} " +
                        "declaredLength=$declared",
                )
                // Proxy FD path (decision 0002 B): use the bare FileDescriptor for
                // every file size so nativeNewFromFd + fstat/onGetSize is the only
                // product SMB descriptor contract.
                // Keep the AFD open for the session (ownedMediaAfd).
                Media(vlc, afd.fileDescriptor)
            }
            is MediaSource.Smb -> {
                val smbMedia = Media(vlc, source.uri)
                applySmbCredentials(smbMedia, source.credentials)
                smbMedia
            }
        }
        media.setHWDecoderEnabled(currentHwDecoderRequested, /* force = */ false)
        media.setDefaultMediaPlayerOptions()
        // Remote/proxy FD benefits from a larger cache (esp. large MP4 with moov-at-end).
        media.addOption(":network-caching=3000")
        media.addOption(":file-caching=3000")
        if (startPositionMs > 0L && source !is MediaSource.Smb) {
            // VLC Android 3.x uses this input option for resume. Whole seconds are
            // intentional and match VLC's own PlaylistManager implementation.
            media.addOption(":start-time=${startPositionMs / 1_000L}")
        }
        // Prefer UTF-8 for text subs; font size via freetype relative size.
        media.addOption(":subsdec-encoding=UTF-8")
        media.addOption(":freetype-rel-fontsize=$subtitleFontRelSize")
        return media
    }

    private fun applySpuDelay() {
        val player = mediaPlayer ?: return
        // libVLC setSpuDelay uses microseconds.
        runCatching {
            player.setSpuDelay(subtitleDelayMs * 1000L)
        }
    }

    private fun createRawMedia(vlc: LibVLC, @RawRes resId: Int): Media? {
        return try {
            val afd = appContext.resources.openRawResourceFd(resId)
            // The Java binding reads the descriptor but neither keeps nor closes
            // the AssetFileDescriptor. Keep it alive through native input teardown.
            try {
                Media(vlc, afd).also { ownedMediaAfd = afd }
            } catch (t: Throwable) {
                afd.close()
                throw t
            }
        } catch (t: Throwable) {
            val msg = CredentialRedactor.redact(t.message ?: "Raw resource open failed")
            failOpen(msg)
            null
        }
    }

    private fun applySmbCredentials(media: Media, credentials: SmbCredentials?) {
        if (credentials == null) return
        // Official libVLC smbj / smb module options — never put these in the URI.
        if (credentials.username.isNotEmpty()) {
            media.addOption(":smb-user=${credentials.username}")
        }
        if (credentials.password.isNotEmpty()) {
            media.addOption(":smb-pwd=${credentials.password}")
        }
        val domain = credentials.domain
        if (!domain.isNullOrEmpty()) {
            media.addOption(":smb-domain=$domain")
        }
    }

    private fun onFirstVout() {
        if (awaitingFirstFramePause && !openedCurrentMedia) {
            Log.i(TAG, "Vout ignored before Opening for current media")
            return
        }
        if (seekPreview.active) {
            // A decoded frame at the seek target — freeze there again.
            finishSeekPreview(reason = "vout")
            refreshTracks()
            return
        }
        if (!awaitingFirstFramePause) {
            // Normal playback path: just ensure tracks are refreshed.
            refreshTracks()
            return
        }
        clearLoadTimeout()
        // Pause first so the decoded frame sticks. Keep holdForUserPlay=true until
        // user taps play (see play()).
        awaitingFirstFramePause = false
        val player = mediaPlayer
        if (!playRequested && player?.isPlaying == true) {
            player.pause()
        }
        refreshTracks()
        val length = player?.length?.coerceAtLeast(0L) ?: 0L
        val time = player?.time?.coerceAtLeast(0L) ?: 0L
        val pausedPhase = if (playRequested) PlayerState.Phase.Playing
            else pauseAfterFirstFramePhase ?: PlayerState.Phase.Ready
        pauseAfterFirstFramePhase = null
        _state.update {
            it.copy(
                phase = pausedPhase,
                firstFrameReady = true,
                isBuffering = false,
                durationMs = length.takeIf { d -> d > 0 } ?: it.durationMs,
                positionMs = time,
                isSeekable = player?.isSeekable == true,
            )
        }
        seekAfterReopenMs?.let { target ->
            seekAfterReopenMs = null
            seekTo(target)
        }
        Log.i(
            TAG,
            "first frame ready phase=$pausedPhase; time=$time length=$length " +
                "hwDecoderRequested=$currentHwDecoderRequested holdForUser=$holdForUserPlay " +
                "scale=$videoScaleMode",
        )
        // Defer scale apply one tick so VideoHelper has video size from this vout and
        // we do not rebuild the surface in the middle of the pause transition.
        mainHandler.post {
            if (released || !viewsAttached) return@post
            val p = mediaPlayer ?: return@post
            applyVideoScale(p)
        }
    }

    private fun pauseNativeIfPlaying(reason: String) {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            runCatching { player.pause() }
                .onFailure { t -> Log.w(TAG, "pause failed ($reason): ${t.message}") }
        }
    }

    private fun resumeNativeIfRequested(reason: String) {
        val player = mediaPlayer ?: return
        if (playRequested && !player.isPlaying) {
            runCatching { player.play() }
                .onFailure { t -> Log.w(TAG, "play failed ($reason): ${t.message}") }
        }
    }

    private fun currentSpuIds(): Set<Int> =
        mediaPlayer?.spuTracks
            ?.map { it.id }
            ?.filter { it >= 0 }
            ?.toSet()
            .orEmpty()

    private fun refreshTracks() {
        val player = mediaPlayer ?: return
        val slaveIds = slaveSubtitles.syncWithCurrentIds(currentSpuIds())
        val audio = player.audioTracks
            ?.map { PlayerTrack(it.id, it.name ?: "Audio ${it.id}", PlayerTrack.Kind.Audio) }
            .orEmpty()
        val subs = player.spuTracks
            ?.map {
                PlayerTrack(
                    id = it.id,
                    name = it.name ?: "Subtitle ${it.id}",
                    kind = PlayerTrack.Kind.Subtitle,
                    isExternalSlave = it.id >= 0 && it.id in slaveIds,
                )
            }
            .orEmpty()
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subs,
                selectedAudioTrackId = player.audioTrack.takeIf { id -> id >= 0 },
                selectedSubtitleTrackId = player.spuTrack,
            )
        }
    }

    private fun failOpen(message: String) {
        invalidateInputEvents()
        discardPendingDescriptor()
        pendingSource = null
        resetTransientFlags(opening = false)
        val safe = CredentialRedactor.redact(message)
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Error,
                firstFrameReady = false,
                isBuffering = false,
                isSeeking = false,
                error = PlayerError(
                    code = PlayerError.Code.OpenFailed,
                    message = safe,
                    retryable = true,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "FrameNestPlayer"
        private const val THREAD_NAME_VLC_RELEASE = "framenest-vlc-release"
        private const val DISABLED_SPU_TRACK = -1
        private const val DEFAULT_SUBTITLE_FONT_REL_SIZE = 16
        private const val MIN_SUBTITLE_FONT_REL_SIZE = 8
        private const val MAX_SUBTITLE_FONT_REL_SIZE = 32
        /** Treat as EOF when remaining time is within this window (ms). */
        private const val END_EPSILON_MS: Long = 400L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val REMOTE_SEEK_POLL_MS = 100L
        /** Max time to run play() after a paused seek before re-pausing. */
        private const val SEEK_PREVIEW_TIMEOUT_MS: Long = 750L
        /** Consider seek-preview settled when TimeChanged is this close to target. */
        private const val SEEK_PREVIEW_TOLERANCE_MS: Long = 500L
        /**
         * After the clock hits the seek target, wait this long for the HW decoder to
         * composite the frame before re-pausing. Tuned to cover SMB / large-MP4 decode
         * lag without feeling laggy; bounded above by [SEEK_PREVIEW_TIMEOUT_MS].
         */
        private const val FRAME_SETTLE_MS: Long = 200L
        /** Sample SMB URI used in docs / manual tests (no credentials). */
        fun sampleSmbUri(
            host: String = "192.168.1.10",
            share: String = "media",
            path: String = "samples/movie.mkv",
        ): Uri = SmbMediaUri.build(host, share, path)
    }
}

private fun VideoScaleMode.toLibVlcScaleType(): MediaPlayer.ScaleType = when (this) {
    VideoScaleMode.BestFit -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    VideoScaleMode.FitScreen -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    VideoScaleMode.Fill -> MediaPlayer.ScaleType.SURFACE_FILL
    VideoScaleMode.Ratio16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
    VideoScaleMode.Ratio4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
    VideoScaleMode.Original -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
}
