package com.framenest.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.framenest.data.thumbnail.ThumbnailFrameGeometry
import com.framenest.data.thumbnail.ThumbnailRv32
import com.framenest.player.CredentialRedactor
import com.framenest.player.SmbMediaUri
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * One software-decode libVLC for the file currently on screen.
 *
 * It has no window. Frames are written into a buffer we own, then scaled for
 * the scrub card. The player stays open across buckets so a drag can seek
 * instead of reconnecting. Playback keeps its own hardware decoder.
 */
internal class ScrubPreviewVlc(
    private val appContext: Context,
) : AutoCloseable {
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var rawBuffer: Pointer? = null
    private var framePointer: Pointer? = null
    private var sink: FrameSink? = null
    private var setFormatCallbacks: Function? = null
    private var setCallbacks: Function? = null
    private var openedKey: String? = null
    private var closed = false
    private var broken = false
    private val sessionLock = ReentrantLock()
    @Volatile
    private var pauseWhenIdle = false

    fun frameAt(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: CharArray,
        domain: String,
        timeMs: Long,
        abandon: () -> Boolean,
    ): ScrubGrab {
        val result = try {
            sessionLock.withLock {
                if (closed || broken) return ScrubGrab.Miss
                if (abandon()) return ScrubGrab.Abandoned
                try {
                    ensureOpen(host, port, share, path, username, password, domain)
                    grab(timeMs.coerceAtLeast(0L), abandon)
                } catch (t: Throwable) {
                    if (player == null) broken = true
                    Log.w(
                        TAG,
                        "scrub vlc failed: ${t.javaClass.simpleName}: " +
                            CredentialRedactor.redact(t.message),
                    )
                    ScrubGrab.Miss
                }
            }
        } finally {
            password.fill('\u0000')
        }
        if (pauseWhenIdle) pauseOutput()
        return result
    }

    /** Safe on the UI thread: a grab in progress pauses itself when it finishes. */
    fun pauseOutput() {
        pauseWhenIdle = true
        if (!sessionLock.tryLock()) return
        try {
            pauseLocked()
        } finally {
            sessionLock.unlock()
        }
    }

    private fun pauseLocked() {
        if (!pauseWhenIdle) return
        val playback = player ?: run {
            pauseWhenIdle = false
            return
        }
        if (runCatching { playback.isPlaying }.getOrDefault(false)) {
            runCatching { playback.pause() }
        }
        pauseWhenIdle = false
    }

    override fun close() {
        sessionLock.withLock {
            closed = true
            runCatching { player?.stop() }
            releaseMedia()
            runCatching { player?.setEventListener(null) }
            runCatching { player?.release() }
            player = null
            runCatching { libVlc?.release() }
            libVlc = null
            sink = null
            openedKey = null
            rawBuffer?.let { buffer ->
                rawBuffer = null
                framePointer = null
                runCatching { Native.free(Pointer.nativeValue(buffer)) }
            }
        }
    }

    private fun ensureOpen(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: CharArray,
        domain: String,
    ) {
        val key = "$host:$port/$share/$path"
        val playback = ensurePlayer()
        val state = playback.playerState
        if (openedKey == key && currentMedia != null &&
            state != STATE_ERROR && state != STATE_ENDED && state != STATE_STOPPED
        ) {
            return
        }
        val location = SmbMediaUri.build(host, share, path, port)
        val media = Media(requireNotNull(libVlc), location)
        try {
            media.addOption(":codec=avcodec")
            media.addOption(":avcodec-hw=none")
            media.addOption(":network-caching=300")
            media.addOption(":file-caching=300")
            media.setDefaultMediaPlayerOptions()
            media.addOption(":no-audio")
            media.addOption(":no-spu")
            media.addOption(":no-sub-autodetect-file")
            media.addOption(":input-fast-seek")
            applyCredentials(media, username, password, domain)
            runCatching { playback.stop() }
            releaseMedia()
            bindMemoryOutput(playback)
            playback.media = media
            currentMedia = media
            openedKey = key
            playback.volume = 0
            playback.play()
            Log.i(TAG, "scrub vlc open scheme=${location.scheme}")
        } catch (t: Throwable) {
            if (currentMedia !== media) runCatching { media.release() }
            throw t
        }
    }

    private fun grab(targetMs: Long, abandon: () -> Boolean): ScrubGrab {
        val playback = player ?: return ScrubGrab.Miss
        val output = sink ?: return ScrubGrab.Miss
        val failed = AtomicBoolean(false)
        playback.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) failed.set(true)
        }
        playback.volume = 0
        if (playback.playerState != STATE_PLAYING) {
            runCatching { playback.play() }
        }
        val firstOpen = !output.seenFrame()
        val deadline = SystemClock.elapsedRealtime() +
            if (firstOpen) OPEN_BUDGET_MS else SEEK_BUDGET_MS
        var seekSent = false
        output.clear()
        while (SystemClock.elapsedRealtime() < deadline && !failed.get()) {
            if (abandon()) return ScrubGrab.Abandoned
            if (playback.playerState == STATE_ERROR) break
            val timeMs = playback.time
            val decodedMs = if (timeMs < 0L) -1L else timeMs
            if (!seekSent && (
                    decodedMs >= 250L ||
                        playback.playerState == STATE_PLAYING ||
                        output.seenFrame()
                    )
            ) {
                playback.setTime(targetMs, true)
                output.clear()
                seekSent = true
            }
            val shot = output.take()
            if (seekSent && shot != null && ScrubPreviewPlan.frameLanded(targetMs, decodedMs)) {
                Log.i(
                    TAG,
                    "scrub vlc ok t=${decodedMs}ms target=${targetMs}ms " +
                        "${shot.size.width}x${shot.size.height}",
                )
                return ScrubGrab.Image(toBitmap(shot))
            }
            Thread.sleep(POLL_MS)
        }
        Log.w(
            TAG,
            "scrub vlc miss state=${playback.playerState} time=${playback.time} " +
                "target=${targetMs}ms error=${failed.get()}",
        )
        return ScrubGrab.Miss
    }

    private fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        val vlc = LibVLC(
            appContext,
            arrayListOf(
                "--no-audio",
                "--no-video-title-show",
                "--no-spu",
                "--no-osd",
                "--avcodec-hw=none",
            ),
        )
        try {
            NativeLibrary.addSearchPath("vlc", appContext.applicationInfo.nativeLibraryDir)
            val library = NativeLibrary.getInstance("vlc")
            val raw = Pointer(Native.malloc((ThumbnailFrameGeometry.MAX_BYTES + 31).toLong()))
            val aligned = align32(raw)
            val created = MediaPlayer(vlc)
            libVlc = vlc
            rawBuffer = raw
            framePointer = aligned
            sink = FrameSink(aligned)
            setFormatCallbacks = library.getFunction("libvlc_video_set_format_callbacks")
            setCallbacks = library.getFunction("libvlc_video_set_callbacks")
            player = created
            return created
        } catch (t: Throwable) {
            runCatching { vlc.release() }
            throw t
        }
    }

    private fun bindMemoryOutput(playback: MediaPlayer) {
        val output = sink ?: error("scrub frame sink missing")
        val format = setFormatCallbacks ?: error("libvlc_video_set_format_callbacks missing")
        val callbacks = setCallbacks ?: error("libvlc_video_set_callbacks missing")
        val instance = playback.instance
        if (instance == 0L) error("scrub player instance missing")
        val playerPtr = Pointer(instance)
        format.invokeVoid(arrayOf(playerPtr, output.format, null))
        callbacks.invokeVoid(
            arrayOf(
                playerPtr,
                output.lock,
                null,
                output.display,
                Pointer.NULL,
            ),
        )
    }

    private fun toBitmap(frame: Captured): Bitmap {
        val bitmap = Bitmap.createBitmap(
            frame.size.width,
            frame.size.height,
            Bitmap.Config.ARGB_8888,
        )
        val pixels = IntArray(frame.size.width * frame.size.height)
        var index = 0
        for (y in 0 until frame.size.height) {
            for (x in 0 until frame.size.width) {
                pixels[index++] = ThumbnailRv32.argb(frame.bytes, frame.size.pitch, x, y)
            }
        }
        bitmap.setPixels(
            pixels,
            0,
            frame.size.width,
            0,
            0,
            frame.size.width,
            frame.size.height,
        )
        return scaleDown(bitmap)
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= MAX_EDGE_PX) return source
        val scale = MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    private fun applyCredentials(
        media: Media,
        username: String,
        password: CharArray,
        domain: String,
    ) {
        if (username.isNotEmpty()) media.addOption(":smb-user=$username")
        if (password.isNotEmpty()) media.addOption(":smb-pwd=${String(password)}")
        if (domain.isNotEmpty()) media.addOption(":smb-domain=$domain")
    }

    private fun releaseMedia() {
        val media = currentMedia
        currentMedia = null
        runCatching { media?.release() }
    }

    private class Captured(
        val bytes: ByteArray,
        val size: ThumbnailFrameGeometry.Size,
    )

    private class FrameSink(private val plane: Pointer) {
        private val snapshot = ByteArray(ThumbnailFrameGeometry.MAX_BYTES)
        private val pending = AtomicBoolean(false)
        private val displays = AtomicInteger(0)
        private var size: ThumbnailFrameGeometry.Size = ThumbnailFrameGeometry.fit(16, 9)

        val format: Callback = object : Callback {
            fun invoke(
                opaque: Pointer?,
                chroma: Pointer?,
                width: Pointer?,
                height: Pointer?,
                pitches: Pointer?,
                lines: Pointer?,
            ): Int {
                return try {
                    val fitted = ThumbnailFrameGeometry.fit(
                        width?.getInt(0) ?: 0,
                        height?.getInt(0) ?: 0,
                    )
                    synchronized(snapshot) {
                        size = fitted
                        pending.set(false)
                    }
                    chroma?.write(0, RV32, 0, 4)
                    width?.setInt(0, fitted.width)
                    height?.setInt(0, fitted.height)
                    pitches?.setInt(0, fitted.pitch)
                    lines?.setInt(0, fitted.height)
                    1
                } catch (_: Throwable) {
                    0
                }
            }
        }

        val lock: Callback = object : Callback {
            fun invoke(opaque: Pointer?, planes: Pointer?): Pointer? {
                return try {
                    planes?.setPointer(0, plane)
                    null
                } catch (_: Throwable) {
                    null
                }
            }
        }

        val display: Callback = object : Callback {
            fun invoke(opaque: Pointer?, picture: Pointer?) {
                try {
                    synchronized(snapshot) {
                        val count = size.byteCount.coerceAtMost(snapshot.size)
                        plane.read(0, snapshot, 0, count)
                        displays.incrementAndGet()
                        pending.set(true)
                    }
                } catch (_: Throwable) {
                    // The decoder thread must not escape into the player.
                }
            }
        }

        fun seenFrame(): Boolean = displays.get() > 0

        fun take(): Captured? {
            synchronized(snapshot) {
                if (!pending.get()) return null
                pending.set(false)
                val fitted = size
                return Captured(snapshot.copyOf(fitted.byteCount), fitted)
            }
        }

        fun clear() {
            synchronized(snapshot) {
                pending.set(false)
            }
        }
    }

    companion object {
        private const val TAG = "FrameNestScrubPreview"
        private const val POLL_MS = 40L
        private const val OPEN_BUDGET_MS = 8_000L
        private const val SEEK_BUDGET_MS = 6_000L
        private const val MAX_EDGE_PX = 240
        private const val STATE_PLAYING = 3
        private const val STATE_STOPPED = 5
        private const val STATE_ENDED = 6
        private const val STATE_ERROR = 7
        private val RV32 = byteArrayOf(
            'R'.code.toByte(),
            'V'.code.toByte(),
            '3'.code.toByte(),
            '2'.code.toByte(),
        )

        private fun align32(raw: Pointer): Pointer {
            val address = Pointer.nativeValue(raw)
            val misalignment = address and 31L
            val padding = if (misalignment == 0L) 0L else 32L - misalignment
            return if (padding == 0L) raw else Pointer(address + padding)
        }
    }
}

internal sealed class ScrubGrab {
    data class Image(val bitmap: Bitmap) : ScrubGrab()
    data object Miss : ScrubGrab()
    data object Abandoned : ScrubGrab()
}
