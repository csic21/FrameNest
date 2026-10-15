package com.framenest.feature.listen_translate.mt

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/** Polled during model loading, CPU decode and each generated token. */
@Keep
internal class HyMtCancellation(private val job: Job?) {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    fun isCancelled(): Boolean = cancelled.get() || job?.isActive == false
}

internal interface HyMtNative {
    fun open(pathUtf8: ByteArray, threads: Int, cancellation: HyMtCancellation): Long
    fun translate(handle: Long, userMessageUtf8: ByteArray, cancellation: HyMtCancellation): ByteArray
    fun close(handle: Long)
}

/** Byte arrays cross JNI as real UTF-8, preserving supplementary characters. */
@Keep
internal object HyMtJni : HyMtNative {
    private val loaded by lazy { System.loadLibrary("framenest_hymt") }

    override fun open(pathUtf8: ByteArray, threads: Int, cancellation: HyMtCancellation): Long {
        loaded
        return nativeOpen(pathUtf8, threads, cancellation)
    }
    override fun translate(handle: Long, userMessageUtf8: ByteArray, cancellation: HyMtCancellation): ByteArray =
        nativeTranslate(handle, userMessageUtf8, cancellation)
    override fun close(handle: Long) = nativeClose(handle)

    private external fun nativeOpen(pathUtf8: ByteArray, threads: Int, cancellation: HyMtCancellation): Long
    private external fun nativeTranslate(handle: Long, userMessageUtf8: ByteArray, cancellation: HyMtCancellation): ByteArray
    private external fun nativeClose(handle: Long)
}
