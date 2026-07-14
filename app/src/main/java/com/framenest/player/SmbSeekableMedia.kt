package com.framenest.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.framenest.smb.SmbRandomAccess
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges [SmbRandomAccess] → seekable [AssetFileDescriptor] for libVLC
 * ([MediaSource.SeekableDescriptor]), implementing decision 0002 path B
 * without a localhost HTTP proxy.
 *
 * Uses [StorageManager.openProxyFileDescriptor] (API 26+, matches minSdk).
 */
object SmbSeekableMedia {
    /**
     * Open a seekable AFD that reads from [randomAccess].
     *
     * @param ioThread optional dedicated thread for proxy FD callbacks; if null,
     *   a short-lived handler thread is created and stopped on AFD close via the
     *   returned [SeekableOpenResult.closeExtras].
     */
    fun open(
        context: Context,
        randomAccess: SmbRandomAccess,
        debugLabel: String = "smb-seekable",
        ioThread: HandlerThread? = null,
    ): SeekableOpenResult {
        val ownsThread = ioThread == null
        val thread = ioThread ?: HandlerThread("smb-pfd-io").also { it.start() }
        val handler = Handler(thread.looper)
        val storage = context.applicationContext.getSystemService(StorageManager::class.java)
            ?: throw IOException("StorageManager unavailable")

        val closed = AtomicBoolean(false)
        val totalSize = randomAccess.size.coerceAtLeast(0L)
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = totalSize

            @Throws(ErrnoException::class)
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (closed.get()) {
                    throw ErrnoException("onRead", OsConstants.EBADF)
                }
                if (offset < 0 || size < 0) {
                    throw ErrnoException("onRead", OsConstants.EINVAL)
                }
                // EOF past end — return 0 (POSIX-style), do not throw.
                if (offset >= totalSize || size == 0) {
                    return 0
                }
                return try {
                    val n = randomAccess.readAt(offset, data, 0, size)
                    when {
                        n < 0 -> 0
                        else -> n
                    }
                } catch (e: IOException) {
                    android.util.Log.w(
                        "FrameNestPlayer",
                        "proxy onRead failed label=$debugLabel offset=$offset size=$size " +
                            "fileSize=$totalSize: ${e.javaClass.simpleName}: ${e.message}",
                    )
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
            }

            override fun onRelease() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { randomAccess.close() }
                    if (ownsThread) {
                        thread.quitSafely()
                    }
                }
            }
        }

        val pfd = storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            handler,
        )
        // For files ≥ 4GiB, a fixed AFD length can overflow/mis-handle in libVLC's
        // nativeNewFromFdWithOffsetLength and produce "stream: read error" / cannot peek.
        // UNKNOWN_LENGTH + Media(FileDescriptor) lets VLC size via fstat/onGetSize.
        val afdLength =
            if (totalSize >= FOUR_GIB) {
                AssetFileDescriptor.UNKNOWN_LENGTH
            } else {
                totalSize
            }
        val afd = AssetFileDescriptor(pfd, 0L, afdLength)
        android.util.Log.i(
            "FrameNestPlayer",
            "SmbSeekableMedia open label=$debugLabel size=$totalSize afdLength=$afdLength",
        )
        return SeekableOpenResult(
            mediaSource = MediaSource.SeekableDescriptor(
                assetFileDescriptor = afd,
                debugLabel = debugLabel,
            ),
            assetFileDescriptor = afd,
        )
    }

    private const val FOUR_GIB = 1L shl 32

    data class SeekableOpenResult(
        val mediaSource: MediaSource.SeekableDescriptor,
        val assetFileDescriptor: AssetFileDescriptor,
    )
}
