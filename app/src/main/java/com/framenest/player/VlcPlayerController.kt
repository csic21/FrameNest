package com.framenest.player

import android.content.Context
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
    private var released: Boolean = false
    /** Owned AFD from path B; closed on re-prepare / release. */
    private var ownedSeekableAfd: AssetFileDescriptor? = null
    private var subtitleDelayMs: Long = 0L
    private var subtitleFontRelSize: Int = DEFAULT_SUBTITLE_FONT_REL_SIZE

    private val eventListener = MediaPlayer.EventListener { event ->
        if (released) return@EventListener
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _state.update { it.copy(phase = PlayerState.Phase.Preparing, error = null) }
            }
            MediaPlayer.Event.Buffering -> {
                // Keep Preparing until first frame; ignore mid-stream buffering noise.
            }
            MediaPlayer.Event.Playing -> {
                if (awaitingFirstFramePause) {
                    // Wait for Vout; some devices fire Playing slightly before surface paint.
                    return@EventListener
                }
                _state.update {
                    it.copy(
                        phase = PlayerState.Phase.Playing,
                        durationMs = mediaPlayer?.length?.coerceAtLeast(0L) ?: it.durationMs,
                    )
                }
            }
            MediaPlayer.Event.Paused -> {
                _state.update {
                    val phase = if (it.firstFrameReady && !awaitingFirstFramePause) {
                        if (it.phase == PlayerState.Phase.Preparing || it.phase == PlayerState.Phase.Ready) {
                            PlayerState.Phase.Ready
                        } else {
                            PlayerState.Phase.Paused
                        }
                    } else if (it.firstFrameReady) {
                        PlayerState.Phase.Ready
                    } else {
                        it.phase
                    }
                    it.copy(phase = phase)
                }
            }
            MediaPlayer.Event.Stopped -> {
                // no-op; release path handles teardown
            }
            MediaPlayer.Event.EndReached -> {
                awaitingFirstFramePause = false
                _state.update {
                    it.copy(
                        phase = PlayerState.Phase.Ended,
                        positionMs = it.durationMs,
                    )
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                awaitingFirstFramePause = false
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
        val layout = videoLayout ?: VLCVideoLayout(appContext).also { videoLayout = it }
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
        }
        tryStartPendingIfReady()
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
        awaitingFirstFramePause = true
        _state.update {
            PlayerState(
                phase = PlayerState.Phase.Preparing,
                hwDecoderRequested = enableHwDecoder,
                firstFrameReady = false,
                subtitleDelayMs = subtitleDelayMs,
                subtitleFontRelSize = subtitleFontRelSize,
            )
        }
        tryStartPendingIfReady()
    }

    /**
     * Surfaces an open/session error without going through libVLC (e.g. SMB connect).
     * Message must already be safe / redacted by the caller.
     */
    fun reportExternalError(error: PlayerError) {
        if (released) return
        awaitingFirstFramePause = false
        pendingSource = null
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
        awaitingFirstFramePause = false
        player.play()
        _state.update { it.copy(phase = PlayerState.Phase.Playing, error = null) }
    }

    override fun pause() {
        if (released) return
        val player = mediaPlayer ?: return
        player.pause()
        _state.update {
            it.copy(
                phase = if (it.firstFrameReady) PlayerState.Phase.Paused else it.phase,
            )
        }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val player = mediaPlayer ?: return
        if (!player.isSeekable && _state.value.durationMs <= 0L) return
        val duration = player.length.takeIf { it > 0 } ?: _state.value.durationMs
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        player.time = clamped
        _state.update { it.copy(positionMs = clamped) }
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

    override fun release() {
        if (released) return
        released = true
        awaitingFirstFramePause = false
        pendingSource = null
        mainHandler.removeCallbacksAndMessages(null)

        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            runCatching {
                player.setEventListener(null)
                if (viewsAttached) {
                    player.detachViews()
                    viewsAttached = false
                }
                player.stop()
            }
            runCatching { player.release() }
        }

        videoLayout?.let { layout ->
            (layout.parent as? ViewGroup)?.removeView(layout)
        }
        videoLayout = null

        closeOwnedSeekableAfd()

        val vlc = libVlc
        libVlc = null
        runCatching { vlc?.release() }

        _state.value = PlayerState(phase = PlayerState.Phase.Idle, hwDecoderRequested = enableHwDecoder)
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
            // Avoid verbose credential-bearing logs in logcat.
            add("-q")
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
        val media = createMedia(source) ?: return
        try {
            player.media = media
            // MediaPlayer retains; release local ref.
            media.release()
            player.play()
        } catch (t: Throwable) {
            media.release()
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
                Log.i(TAG, "Opening seekable descriptor label=${source.debugLabel}")
                // libVLC takes the FD; we still close our AFD wrapper on release.
                Media(vlc, source.assetFileDescriptor)
            }
            is MediaSource.Smb -> {
                val smbMedia = Media(vlc, source.uri)
                applySmbCredentials(smbMedia, source.credentials)
                smbMedia
            }
        }
        media.setHWDecoderEnabled(enableHwDecoder, /* force = */ false)
        media.setDefaultMediaPlayerOptions()
        // Slight network cache helps SMB / remote FD; harmless for local.
        media.addOption(":network-caching=1500")
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
        if (!awaitingFirstFramePause) {
            // Normal playback path: just ensure tracks are refreshed.
            refreshTracks()
            return
        }
        awaitingFirstFramePause = false
        val player = mediaPlayer
        player?.pause()
        refreshTracks()
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Ready,
                firstFrameReady = true,
                durationMs = player?.length?.coerceAtLeast(0L) ?: it.durationMs,
                positionMs = player?.time?.coerceAtLeast(0L) ?: it.positionMs,
                isSeekable = player?.isSeekable == true,
            )
        }
        Log.i(TAG, "first frame ready (paused); hwDecoderRequested=$enableHwDecoder")
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
        awaitingFirstFramePause = false
        val safe = CredentialRedactor.redact(message)
        _state.update {
            it.copy(
                phase = PlayerState.Phase.Error,
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

        /** Sample SMB URI used in docs / manual tests (no credentials). */
        fun sampleSmbUri(
            host: String = "192.168.1.10",
            share: String = "media",
            path: String = "samples/movie.mkv",
        ): Uri = SmbMediaUri.build(host, share, path)
    }
}
