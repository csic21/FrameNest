package com.framenest.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Data paths (decision 0001 / 0002):
 * - **B (preferred):** [MediaSource.SeekableDescriptor] from SMBJ + proxy FD
 * - **A (optional):** [MediaSource.Smb] with options-only credentials, no URL userinfo
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
    private var awaitingFirstFramePause: Boolean = false
    /**
     * True from [prepare] until the user calls [play]. Keeps phase at Ready after the
     * first decoded frame and ignores stale Playing/EndReached events that would
     * otherwise flash Playing → Paused or jump straight to Ended.
     */
    private var holdForUserPlay: Boolean = false
    /**
     * Set when [MediaPlayer.Event.EndReached] arrives while still holding for the user
     * (stale EOF after first-frame pause). [play] restarts from 0 instead of no-op.
     */
    private var endedWhileHolding: Boolean = false
    private var released: Boolean = false
    /**
     * When true, ignore EndReached from intentional [stop]/media=null (re-prepare /
     * close). Cleared on a short timer or first Vout — **not** on Opening, because a
     * residual EndReached from stop() often arrives *after* Opening of the new media
     * and would otherwise surface as "ended before a video frame was ready".
     */
    private var suppressEndReached: Boolean = false
    /**
     * When true, ignore both EndReached and EncounteredError (closeCurrentMedia /
     * release teardown only).
     */
    private var suppressAllTerminalEvents: Boolean = false
    /**
     * True after [MediaPlayer.Event.Opening] for the media started by the latest
     * [tryStartPendingIfReady]. Residual EndReached before Opening is always ignored.
     */
    private var openedCurrentMedia: Boolean = false
    /**
     * Number of EndReached events to drop after stop() when the previous media was
     * non-null. Covers the common residual EOF without treating a later real demux
     * failure as success.
     */
    private var ignoreEndReachedBudget: Int = 0
    /**
     * After seek while paused/Ready, briefly play so libVLC decodes a frame at the
     * new position, then re-pause. Without this, [setTime] updates the clock only.
     */
    private var seekPreviewActive: Boolean = false
    private var seekPreviewTargetMs: Long = -1L
    private val seekPreviewPauseRunnable = Runnable { finishSeekPreview(reason = "timeout") }
    private val seekPreviewSettleRunnable = Runnable { finishSeekPreview(reason = "settled") }

    /**
     * Single source of truth for resetting the transient playback-control flags.
     *
     * Called from [prepare] (opening=true) and every teardown / error / release path
     * (opening=false). Centralizing this fixes a class of bugs where individual sites
     * hand-copied a *subset* of the flags and left stale ones set (e.g. a leftover
     * [holdForUserPlay] after a fallback, or [seekPreviewActive] not cleared on
     * [failOpen]) — which produced phantom "Playing ignored (seek preview)" /
     * "Playing ignored (hold)" events that froze playback.
     *
     * Does NOT touch the suppress flags ([suppressEndReached] / [suppressAllTerminalEvents]):
     * those are intentional per-call policy, and every caller sets them explicitly for
     * the teardown/open it is about to perform. [seekPreviewActive] / target are cleared
     * and the preview runnables removed via [cancelSeekPreview].
     */
    private fun resetTransientFlags(opening: Boolean) {
        awaitingFirstFramePause = opening
        holdForUserPlay = opening
        endedWhileHolding = false
        openedCurrentMedia = false
        ignoreEndReachedBudget = 0
        cancelSeekPreview(pausePlayer = false)
    }
    /** Owned AFD from path B; closed on re-prepare / release. */
    private var ownedSeekableAfd: AssetFileDescriptor? = null
    private var subtitleDelayMs: Long = 0L
    private var subtitleFontRelSize: Int = DEFAULT_SUBTITLE_FONT_REL_SIZE
    private var videoScaleMode: VideoScaleMode = VideoScaleMode.BestFit

    private val eventListener = MediaPlayer.EventListener { event ->
        if (released) return@EventListener
        // Serialize all event handling onto the main thread. The transient flags
        // below are read/written by main-thread methods (play/pause/seekTo/prepare);
        // handling events here on the libVLC thread caused visibility races. StateFlow
        // itself is thread-safe, but the gating flags are not — post to the main looper
        // so the controller behaves like a single-threaded state machine. release()
        // removes all callbacks, so queued events after release are dropped via the
        // guard inside handleEvent.
        mainHandler.post { if (!released) handleEvent(event) }
    }

    private fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                // New media is opening. Keep suppressEndReached until the post-stop
                // grace window ends so a late EndReached from stop() is not treated
                // as "ended before first frame" for this media.
                openedCurrentMedia = true
                _state.update { it.copy(phase = PlayerState.Phase.Preparing, error = null) }
            }
            MediaPlayer.Event.Buffering -> {
                // Keep Preparing until first frame; ignore mid-stream buffering noise.
            }
            MediaPlayer.Event.Playing -> {
                if (suppressAllTerminalEvents) return@handleEvent
                // Hold first-frame gate until user explicitly calls play(): late Playing
                // events after Vout+pause must not flip Ready → Playing (then → Paused).
                if (awaitingFirstFramePause || holdForUserPlay) {
                    Log.i(
                        TAG,
                        "Playing ignored (awaitingFirst=$awaitingFirstFramePause holdForUser=$holdForUserPlay)",
                    )
                    return@handleEvent
                }
                // Seek-preview play is internal — keep UI in Paused/Ready.
                if (seekPreviewActive) {
                    Log.i(TAG, "Playing ignored (seek preview)")
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
                // Residual EOF from stop()/media=null — often arrives after Opening of
                // the next media. Drop budgeted residuals and anything still inside the
                // post-stop grace window so we do not false-error "before first frame".
                if (ignoreEndReachedBudget > 0) {
                    ignoreEndReachedBudget--
                    Log.i(
                        TAG,
                        "EndReached ignored (residual budget left=$ignoreEndReachedBudget)",
                    )
                    return@handleEvent
                }
                if (suppressEndReached) {
                    Log.i(TAG, "EndReached ignored (suppress window after stop)")
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
                // Residual errors from stop are rare; still report once current media
                // has started opening so real open failures are not swallowed.
                if (suppressEndReached && !openedCurrentMedia) {
                    Log.i(TAG, "EncounteredError ignored (residual before Opening)")
                    return@handleEvent
                }
                // Cancel seek-preview settle/timeout so a late finishSeekPreview
                // cannot overwrite Error → Paused.
                resetTransientFlags(opening = false)
                suppressEndReached = false
                val message = CredentialRedactor.redact("Playback failed (libVLC EncounteredError)")
                Log.w(TAG, message)
                _state.update {
                    it.copy(
                        phase = PlayerState.Phase.Error,
                        error = PlayerError(
                            code = PlayerError.Code.PlaybackError,
                            message = message,
                            retryable = true,
                        ),
                    )
                }
            }
            MediaPlayer.Event.TimeChanged -> {
                val time = event.timeChanged.coerceAtLeast(0L)
                _state.update { it.copy(positionMs = time) }
                if (seekPreviewActive && seekPreviewTargetMs >= 0L) {
                    val delta = kotlin.math.abs(time - seekPreviewTargetMs)
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
        if (existing != null && isActivityContext(existing.context)) {
            return existing
        }
        if (existing != null) {
            Log.i(TAG, "Recreating VLCVideoLayout with Activity context for scale modes")
            if (viewsAttached) {
                runCatching { mediaPlayer?.detachViews() }
                viewsAttached = false
            }
            (existing.parent as? ViewGroup)?.removeView(existing)
            videoLayout = null
        }
        return VLCVideoLayout(hostContext).also { videoLayout = it }
    }

    private fun isActivityContext(context: Context): Boolean {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return true
            current = current.baseContext
        }
        return false
    }

    override fun detachVideoLayout() {
        if (released) return
        val player = mediaPlayer
        if (player != null && viewsAttached) {
            runCatching { player.detachViews() }
            viewsAttached = false
        }
        videoLayout?.let { layout ->
            (layout.parent as? ViewGroup)?.removeView(layout)
        }
    }

    override fun prepare(source: MediaSource) {
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
        closeOwnedSeekableAfd()
        if (source is MediaSource.SeekableDescriptor) {
            ownedSeekableAfd = source.assetFileDescriptor
        }
        pendingSource = source
        resetTransientFlags(opening = true)
        suppressEndReached = false
        suppressAllTerminalEvents = false
        _state.update {
            PlayerState(
                phase = PlayerState.Phase.Preparing,
                hwDecoderRequested = enableHwDecoder,
                firstFrameReady = false,
                subtitleDelayMs = subtitleDelayMs,
                subtitleFontRelSize = subtitleFontRelSize,
                videoScaleMode = videoScaleMode,
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
        pendingSource = null
        resetTransientFlags(opening = false)
        suppressAllTerminalEvents = true
        suppressEndReached = true
        val player = mediaPlayer
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.media = null }
        }
        closeOwnedSeekableAfd()
        _state.update {
            PlayerState(
                phase = PlayerState.Phase.Idle,
                hwDecoderRequested = enableHwDecoder,
                subtitleDelayMs = subtitleDelayMs,
                subtitleFontRelSize = subtitleFontRelSize,
                videoScaleMode = videoScaleMode,
            )
        }
        // Clear after libVLC has delivered any async EndReached from stop().
        mainHandler.postDelayed({
            if (!released) {
                suppressAllTerminalEvents = false
                suppressEndReached = false
            }
        }, STOP_EVENT_SUPPRESS_MS)
    }

    /**
     * Surfaces an open/session error without going through libVLC (e.g. SMB connect).
     * Message must already be safe / redacted by the caller.
     */
    fun reportExternalError(error: PlayerError) {
        if (released) return
        pendingSource = null
        resetTransientFlags(opening = false)
        val safe = error.copy(message = CredentialRedactor.redact(error.message))
        Log.w(TAG, "external error code=${safe.code} msg=${safe.message}")
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Error,
                error = safe,
                firstFrameReady = false,
            )
        }
    }

    override fun play() {
        if (released) return
        val player = mediaPlayer ?: return
        val phase = _state.value.phase
        if (phase == PlayerState.Phase.Error ||
            phase == PlayerState.Phase.Idle ||
            phase == PlayerState.Phase.Preparing
        ) {
            // Nothing useful to resume; caller should retry/prepare again.
            Log.w(TAG, "play() ignored in phase=$phase")
            return
        }
        // User-initiated play cancels pause-scrub preview and continues for real.
        // Not using resetTransientFlags here: [endedWhileHolding] must survive until
        // the atOrPastEnd check below so a stale EOF while holding for the user can
        // still trigger restart-from-0. The other flags are cleared inline.
        cancelSeekPreview(pausePlayer = false)
        awaitingFirstFramePause = false
        holdForUserPlay = false
        suppressEndReached = false
        suppressAllTerminalEvents = false
        ignoreEndReachedBudget = 0
        val length = player.length.takeIf { it > 0 } ?: _state.value.durationMs
        val time = player.time.coerceAtLeast(0L)
        // Restart when already at/near EOF (stale pause after full decode, bad resume
        // seek, short sample that finished during first-frame priming, or EndReached
        // ignored while holding for the user).
        val atOrPastEnd = phase == PlayerState.Phase.Ended ||
            endedWhileHolding ||
            (length > 0L && time >= (length - END_EPSILON_MS).coerceAtLeast(0L))
        endedWhileHolding = false
        if (atOrPastEnd) {
            Log.i(TAG, "play() restart from 0 (phase=$phase time=$time length=$length)")
            // Prefer seek-to-start over stop(): stop() can drop the media/surface binding.
            runCatching { player.setTime(0L, /* fast = */ false) }
            _state.update { it.copy(positionMs = 0L) }
        } else {
            Log.i(TAG, "play() phase=$phase time=$time length=$length")
        }
        player.play()
        _state.update { it.copy(phase = PlayerState.Phase.Playing, error = null) }
    }

    override fun pause() {
        if (released) return
        val player = mediaPlayer ?: return
        if (_state.value.phase != PlayerState.Phase.Playing) return
        player.pause()
        _state.update {
            it.copy(
                phase = if (it.firstFrameReady) PlayerState.Phase.Paused else it.phase,
            )
        }
    }

    override fun seekTo(positionMs: Long, fast: Boolean) {
        if (released) return
        val player = mediaPlayer ?: return
        val phase = _state.value.phase
        if (phase == PlayerState.Phase.Error ||
            phase == PlayerState.Phase.Idle ||
            phase == PlayerState.Phase.Preparing
        ) {
            return
        }
        if (!player.isSeekable && _state.value.durationMs <= 0L) {
            Log.w(TAG, "seekTo ignored (not seekable, duration unknown)")
            return
        }
        val duration = player.length.takeIf { it > 0 } ?: _state.value.durationMs
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        // Cancel any in-flight pause-scrub preview before a new seek.
        cancelSeekPreview(/* pausePlayer = */ false)
        // Fast seeks are throttled previews while actively playing. The release seek
        // uses fast=false so the final position remains precise.
        val applied = runCatching { player.setTime(clamped, fast) }
            .getOrDefault(-1L)
        val position = if (applied >= 0L) applied else clamped
        _state.update { it.copy(positionMs = position) }
        Log.i(
            TAG,
            "seekTo target=$clamped applied=$applied seekable=${player.isSeekable} " +
                "phase=$phase playing=${player.isPlaying} fast=$fast",
        )
        // While paused / first-frame Ready, setTime alone does not paint a new frame
        // (HW decoder holds the last surface). Briefly play then re-pause.
        val needFramePreview = !fast &&
            _state.value.firstFrameReady &&
            !player.isPlaying &&
            phase != PlayerState.Phase.Playing
        if (needFramePreview) {
            startSeekPreview(player, position)
        }
    }

    private fun startSeekPreview(player: MediaPlayer, targetMs: Long) {
        seekPreviewActive = true
        seekPreviewTargetMs = targetMs
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        mainHandler.postDelayed(seekPreviewPauseRunnable, SEEK_PREVIEW_TIMEOUT_MS)
        runCatching { player.play() }
            .onFailure { t ->
                Log.w(TAG, "seek preview play failed: ${t.message}")
                cancelSeekPreview(pausePlayer = false)
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
        if (!seekPreviewActive) return
        seekPreviewActive = false
        seekPreviewTargetMs = -1L
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            runCatching { player.pause() }
        }
        _state.update {
            // Never clobber terminal/open phases (Error/Ended/Preparing/Idle) that
            // may have been set while the settle timer was still armed.
            if (it.phase != PlayerState.Phase.Playing &&
                it.phase != PlayerState.Phase.Ready &&
                it.phase != PlayerState.Phase.Paused
            ) {
                return@update it
            }
            val phase = when {
                holdForUserPlay && it.firstFrameReady -> PlayerState.Phase.Ready
                it.firstFrameReady -> PlayerState.Phase.Paused
                else -> it.phase
            }
            it.copy(phase = phase)
        }
        Log.i(TAG, "seek preview finished ($reason)")
    }

    private fun cancelSeekPreview(pausePlayer: Boolean) {
        val wasActive = seekPreviewActive || seekPreviewTargetMs >= 0L
        seekPreviewActive = false
        seekPreviewTargetMs = -1L
        mainHandler.removeCallbacks(seekPreviewPauseRunnable)
        mainHandler.removeCallbacks(seekPreviewSettleRunnable)
        if (!wasActive) return
        if (pausePlayer) {
            val player = mediaPlayer
            if (player != null && player.isPlaying) {
                runCatching { player.pause() }
            }
        }
    }

    override fun selectAudioTrack(trackId: Int) {
        if (released) return
        val player = mediaPlayer ?: return
        if (player.setAudioTrack(trackId)) {
            _state.update { it.copy(selectedAudioTrackId = trackId) }
        }
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
            val ok = player.addSlave(IMedia.Slave.Type.Subtitle, uri, select)
            if (ok) {
                // Give libVLC a beat to register ES, then refresh + apply delay.
                mainHandler.post {
                    if (released) return@post
                    refreshTracks()
                    applySpuDelay()
                    if (select) {
                        val tracks = mediaPlayer?.spuTracks
                        val last = tracks?.lastOrNull()
                        if (last != null && last.id >= 0) {
                            mediaPlayer?.setSpuTrack(last.id)
                            _state.update { it.copy(selectedSubtitleTrackId = last.id) }
                        }
                    }
                }
            } else {
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
            mediaPlayer?.media?.addOption(":freetype-rel-fontsize=$clamped")
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
        if (seekPreviewActive) {
            finishSeekPreview(reason = "surface-refresh")
        }
        val player = mediaPlayer ?: return
        // Layout/orientation change only — do not reassign scale (that also
        // triggers a full surface rebuild and can freeze the current frame).
        runCatching { player.updateVideoSurfaces() }
            .onFailure { t -> Log.w(TAG, "refreshVideoSurfaces failed: ${t.message}") }
    }

    override fun release() {
        if (released) return
        released = true
        pendingSource = null
        resetTransientFlags(opening = false)
        suppressAllTerminalEvents = true
        suppressEndReached = true
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
        val afd = ownedSeekableAfd
        ownedSeekableAfd = null

        /**
         * View detach / removeView must run on the main thread.
         * stop() is best-effort so the surface stops painting before pop animation.
         */
        fun detachAndStop() {
            if (player != null) {
                runCatching {
                    player.setEventListener(null)
                    if (wasAttached) {
                        runCatching { player.detachViews() }
                    }
                    player.stop()
                }
            }
            if (layout != null) {
                val parent = layout.parent as? ViewGroup
                if (parent != null) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        runCatching { parent.removeView(layout) }
                    } else {
                        mainHandler.post { runCatching { parent.removeView(layout) } }
                    }
                }
            }
        }

        /** Native release + proxy AFD close can block on SMB; never block the UI thread. */
        fun releaseNative() {
            if (player != null) {
                runCatching { player.release() }
            }
            if (afd != null) {
                runCatching { afd.close() }
            }
            runCatching { vlc?.release() }
        }

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val needsMainDetach = wasAttached || layout?.parent != null
        when {
            onMain -> {
                detachAndStop()
                // Keep popBackStack animation smooth: heavy native/AFD work off main.
                Thread(
                    { releaseNative() },
                    "framenest-vlc-release",
                ).apply {
                    isDaemon = true
                    start()
                }
            }
            needsMainDetach -> {
                val done = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        detachAndStop()
                    } finally {
                        done.countDown()
                    }
                }
                if (!done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "release: main detach timed out; continuing native release")
                    runCatching { player?.setEventListener(null) }
                    runCatching { player?.stop() }
                }
                releaseNative()
            }
            else -> {
                // Typical leave path: AndroidView already detached surfaces.
                detachAndStop()
                releaseNative()
            }
        }

        _state.value = PlayerState(
            phase = PlayerState.Phase.Idle,
            hwDecoderRequested = enableHwDecoder,
            videoScaleMode = videoScaleMode,
        )
    }

    private fun applyVideoScale(player: MediaPlayer) {
        if (!viewsAttached) {
            Log.w(TAG, "applyVideoScale skipped (views not attached) mode=$videoScaleMode")
            return
        }
        val layoutCtx = videoLayout?.context
        if (layoutCtx != null && !isActivityContext(layoutCtx)) {
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

    private fun closeOwnedSeekableAfd() {
        val afd = ownedSeekableAfd
        ownedSeekableAfd = null
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
        player.setEventListener(eventListener)
        mediaPlayer = player
    }

    private fun tryStartPendingIfReady() {
        val source = pendingSource ?: return
        val player = mediaPlayer ?: return
        if (!viewsAttached) {
            // Wait until Compose attaches VLCVideoLayout so first frame can paint.
            return
        }
        pendingSource = null
        // stop()/media=null can emit EndReached asynchronously — often *after*
        // Opening of the next media. Drop residual EOF and keep a short suppress
        // window; do not clear that suppress on Opening (see event listener).
        openedCurrentMedia = false
        val hadMedia = player.media != null
        suppressEndReached = true
        runCatching { player.stop() }
        runCatching { player.media = null }
        ignoreEndReachedBudget = if (hadMedia) 1 else 0
        mainHandler.postDelayed({
            if (released) return@postDelayed
            if (suppressEndReached) {
                suppressEndReached = false
                // stop() never delivered EndReached — drop unused residual budget so a
                // later real demux EOF is not swallowed.
                if (ignoreEndReachedBudget > 0) {
                    Log.i(TAG, "clearing unused EndReached residual budget")
                    ignoreEndReachedBudget = 0
                }
                Log.i(TAG, "EndReached suppress window ended")
            }
        }, STOP_EVENT_SUPPRESS_MS)
        val media = createMedia(source) ?: return
        try {
            player.media = media
            // MediaPlayer retains; release local ref.
            media.release()
            player.play()
        } catch (t: Throwable) {
            media.release()
            holdForUserPlay = false
            awaitingFirstFramePause = false
            suppressEndReached = false
            ignoreEndReachedBudget = 0
            val msg = CredentialRedactor.redact(t.message ?: "Failed to open media")
            Log.w(TAG, "prepare failed: $msg")
            _state.update {
                it.copy(
                    phase = PlayerState.Phase.Error,
                    error = PlayerError(PlayerError.Code.OpenFailed, msg, retryable = true),
                )
            }
        }
    }

    private fun handleEndReached() {
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
            resetTransientFlags(opening = false)
            val message = CredentialRedactor.redact(
                "Playback ended before a video frame was ready",
            )
            Log.w(TAG, message)
            _state.update {
                it.copy(
                    phase = PlayerState.Phase.Error,
                    error = PlayerError(
                        code = PlayerError.Code.OpenFailed,
                        message = message,
                        retryable = true,
                    ),
                    firstFrameReady = false,
                )
            }
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
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Ended,
                positionMs = it.durationMs.takeIf { d -> d > 0 } ?: pos.coerceAtLeast(0L),
            )
        }
    }

    private fun createMedia(source: MediaSource): Media? {
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
                // Proxy FD path (decision 0002 B):
                // - Prefer bare FileDescriptor (nativeNewFromFd + fstat/onGetSize).
                // - Media(AFD) uses nativeNewFromFdWithOffsetLength; for files ≥4GiB
                //   that path has produced VLC "stream: read error" / "cannot peek"
                //   / EncounteredError on real NAS samples (~7.5GiB mp4).
                // Keep the AFD open for the session (ownedSeekableAfd).
                Media(vlc, afd.fileDescriptor)
            }
            is MediaSource.Smb -> {
                val smbMedia = Media(vlc, source.uri)
                applySmbCredentials(smbMedia, source.credentials)
                smbMedia
            }
        }
        media.setHWDecoderEnabled(enableHwDecoder, /* force = */ false)
        media.setDefaultMediaPlayerOptions()
        // Remote/proxy FD benefits from a larger cache (esp. large MP4 with moov-at-end).
        media.addOption(":network-caching=3000")
        media.addOption(":file-caching=3000")
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
            // Media(ILibVLC, AssetFileDescriptor) takes ownership of the fd.
            Media(vlc, afd)
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
        // First video output means residual stop() EOF is no longer relevant.
        suppressEndReached = false
        ignoreEndReachedBudget = 0
        if (seekPreviewActive) {
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
        // Pause first so the decoded frame sticks. Keep holdForUserPlay=true until
        // user taps play (see play()).
        awaitingFirstFramePause = false
        val player = mediaPlayer
        player?.pause()
        refreshTracks()
        val length = player?.length?.coerceAtLeast(0L) ?: 0L
        val time = player?.time?.coerceAtLeast(0L) ?: 0L
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Ready,
                firstFrameReady = true,
                durationMs = length.takeIf { d -> d > 0 } ?: it.durationMs,
                positionMs = time,
                isSeekable = player?.isSeekable == true,
            )
        }
        Log.i(
            TAG,
            "first frame ready (paused); time=$time length=$length " +
                "hwDecoderRequested=$enableHwDecoder holdForUser=$holdForUserPlay " +
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

    private fun refreshTracks() {
        val player = mediaPlayer ?: return
        val audio = player.audioTracks
            ?.map { PlayerTrack(it.id, it.name ?: "Audio ${it.id}", PlayerTrack.Kind.Audio) }
            .orEmpty()
        val subs = player.spuTracks
            ?.map { PlayerTrack(it.id, it.name ?: "Subtitle ${it.id}", PlayerTrack.Kind.Subtitle) }
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
        resetTransientFlags(opening = false)
        suppressEndReached = false
        val safe = CredentialRedactor.redact(message)
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Error,
                firstFrameReady = false,
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
        private const val DISABLED_SPU_TRACK = -1
        private const val DEFAULT_SUBTITLE_FONT_REL_SIZE = 16
        private const val MIN_SUBTITLE_FONT_REL_SIZE = 8
        private const val MAX_SUBTITLE_FONT_REL_SIZE = 32
        /** Treat as EOF when remaining time is within this window (ms). */
        private const val END_EPSILON_MS: Long = 400L
        /**
         * How long to ignore EndReached after stop()/media=null when starting a new
         * media. Residual EOF commonly arrives after Opening of the next item.
         */
        private const val STOP_EVENT_SUPPRESS_MS: Long = 400L
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
