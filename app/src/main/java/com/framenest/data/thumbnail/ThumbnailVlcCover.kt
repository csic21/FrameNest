package com.framenest.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.framenest.player.CredentialRedactor
import com.framenest.player.SmbMediaUri
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * One reused libVLC player per thumbnail worker.
 *
 * The player has no window. libVLC writes a single small RV32 frame into a
 * buffer we own, the same way its old in-process thumbnailer did. The SMB URI
 * never contains credentials. Playback keeps its own hardware decoder.
 */
internal class ThumbnailVlcCover(
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
    private var closed = false
    private var setupFailed = false
    private val sessionLock = Any()

    fun frame(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: CharArray,
        domain: String,
        active: () -> Boolean,
    ): ThumbnailFrameExtractor.ExtractResult? {
        synchronized(sessionLock) {
            if (closed || setupFailed) return null
            if (!active()) throw CancellationException("thumbnail cover cancelled")
            val location = SmbMediaUri.build(host, share, path, port)
            return try {
                val playback = ensurePlayer()
                val media = Media(requireNotNull(libVlc), location)
                try {
                    // :codec= must precede setDefaultMediaPlayerOptions, or that
                    // call turns hardware decoding on. A memory callback needs
                    // avcodec bytes, not a MediaCodec surface.
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
                    Log.i(TAG, "vlc cover begin scheme=${location.scheme}")
                    grab(playback, media, active)
                } finally {
                    if (currentMedia === media) {
                        releaseMedia()
                    } else {
                        runCatching { media.release() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (player == null) setupFailed = true
                Log.w(
                    TAG,
                    "vlc cover failed: ${t.javaClass.simpleName}: ${CredentialRedactor.redact(t.message)}",
                )
                null
            } finally {
                password.fill('\u0000')
            }
        }
    }

    override fun close() {
        synchronized(sessionLock) {
            closed = true
            runCatching { player?.stop() }
            releaseMedia()
            runCatching { player?.setEventListener(null) }
            runCatching { player?.release() }
            player = null
            runCatching { libVlc?.release() }
            libVlc = null
            sink = null
            rawBuffer?.let { buffer ->
                rawBuffer = null
                framePointer = null
                runCatching { Native.free(Pointer.nativeValue(buffer)) }
            }
        }
    }

    private fun grab(
        playback: MediaPlayer,
        media: Media,
        active: () -> Boolean,
    ): ThumbnailFrameExtractor.ExtractResult? {
        val output = sink ?: return null
        val failed = AtomicBoolean(false)
        val duration = AtomicLong(0L)
        playback.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> failed.set(true)
                MediaPlayer.Event.LengthChanged -> {
                    val length = event.lengthChanged
                    if (length > 0L) duration.updateAndGet { current -> maxOf(current, length) }
                }
            }
        }
        playback.setVolume(0)
        releaseMedia()
        bindMemoryOutput(playback)
        playback.media = media
        currentMedia = media
        output.clear()
        playback.play()
        val targets = ThumbnailVlcPlan.seekTargetsMs()
        val deadline = SystemClock.elapsedRealtime() + ThumbnailVlcPlan.BUDGET_MS
        var step = 0
        var seekSent = false
        var arrivedAt = 0L
        var sawUnusable = false
        try {
            while (step < targets.size && SystemClock.elapsedRealtime() < deadline && !failed.get()) {
                if (!active()) throw CancellationException("thumbnail cover cancelled")
                if (playback.playerState == STATE_ERROR) break
                val timeMs = playback.time.coerceAtLeast(0L)
                val target = targets[step]
                if (!seekSent && (timeMs >= 250L || playback.playerState == STATE_PLAYING || output.seenFrame())) {
                    if (timeMs < target - SEEK_SLOP_MS) {
                        playback.setTime(target, true)
                        output.clear()
                    }
                    seekSent = true
                }
                val length = maxOf(duration.get(), playback.length.coerceAtLeast(0L))
                val threshold = acceptThreshold(target, length)
                val onTarget = seekSent && timeMs >= threshold
                val shot = output.take()
                if (shot != null && onTarget && usable(shot)) {
                    val durationMs = maxOf(duration.get(), playback.length.coerceAtLeast(0L))
                    Log.i(
                        TAG,
                        "vlc cover ok t=${timeMs}ms dur=${durationMs}ms " +
                            "${shot.size.width}x${shot.size.height} via=callback",
                    )
                    runCatching { playback.stop() }
                    return toResult(shot, timeMs, durationMs)
                }
                if (onTarget && arrivedAt == 0L) arrivedAt = SystemClock.elapsedRealtime()
                if (shot != null && onTarget) sawUnusable = true
                val waited = arrivedAt != 0L &&
                    SystemClock.elapsedRealtime() - arrivedAt >= TARGET_WAIT_MS
                if (sawUnusable && waited) {
                    step += 1
                    seekSent = false
                    arrivedAt = 0L
                    sawUnusable = false
                    output.clear()
                    continue
                }
                Thread.sleep(POLL_MS)
            }
            Log.w(
                TAG,
                "vlc cover miss state=${playback.playerState} time=${playback.time} " +
                    "frames=${output.displayCount()} error=${failed.get()}",
            )
            return null
        } finally {
            runCatching { playback.stop() }
        }
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
        val output = sink ?: error("thumbnail frame sink missing")
        val format = setFormatCallbacks ?: error("libvlc_video_set_format_callbacks missing")
        val callbacks = setCallbacks ?: error("libvlc_video_set_callbacks missing")
        val instance = playback.instance
        if (instance == 0L) error("thumbnail player instance missing")
        val playerPtr = Pointer(instance)
        // Kotlin sees JNA's Object... as a single array parameter.
        // Format callback picks the size; a fixed 16:9 buffer stretches portrait.
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

    private fun usable(frame: Captured): Boolean {
        val samples = ThumbnailBlackFrame.sampleGrid(
            frame.size.width,
            frame.size.height,
            samplesPerSide = 8,
        ) { x, y ->
            ThumbnailRv32.argb(frame.bytes, frame.size.pitch, x, y)
        }
        return !ThumbnailBlackFrame.isBlackFrame(samples) &&
            !ThumbnailBlackFrame.isLowInformation(samples)
    }

    private fun toResult(
        frame: Captured,
        timeMs: Long,
        durationMs: Long,
    ): ThumbnailFrameExtractor.ExtractResult {
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
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return ThumbnailFrameExtractor.ExtractResult(
            bitmap = bitmap,
            jpegBytes = out.toByteArray(),
            usedTimestampMs = timeMs,
            durationMs = durationMs,
        )
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

    /**
     * Holds the plane libVLC draws into. [lock] and [display] stay referenced
     * for the life of the player; native code calls them with no Java caller.
     */
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
                    // chroma is four bytes, not a C string. setString would write past it.
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

        fun displayCount(): Int = displays.get()

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
        private const val TAG = "FrameNestThumb"
        private const val POLL_MS = 40L
        private const val SEEK_SLOP_MS = 6_000L
        private const val MIN_ACCEPT_MS = 1_500L
        private const val TARGET_WAIT_MS = 2_000L
        private const val STATE_PLAYING = 3
        private const val STATE_ERROR = 6
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

        private fun acceptThreshold(target: Long, length: Long): Long {
            if (length in 1 until target) return (length / 3).coerceAtLeast(0L)
            return (target - SEEK_SLOP_MS).coerceAtLeast(MIN_ACCEPT_MS)
        }
    }
}
