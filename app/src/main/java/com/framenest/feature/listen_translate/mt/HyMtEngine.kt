package com.framenest.feature.listen_translate.mt

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Optional offline Hy-MT2 engine. The supplier must return the installer-verified,
 * app-private absolute path of the pinned GGUF. This engine never downloads files.
 * One context is reused, with KV state erased between independent translations.
 * Product selection and ML Kit fallback are owned by the composition layer.
 */
class HyMtEngine internal constructor(
    private val verifiedModelPath: suspend () -> String,
    private val native: HyMtNative,
    private val dispatcher: CoroutineDispatcher,
    private val threadCount: Int,
    private val modelUseGuard: HyMtModelUseGuard = HyMtModelUseGuard.shared,
) : ListenMtEngine {
    constructor(verifiedModelPath: suspend () -> String, threadCount: Int = 2) : this(
        verifiedModelPath, HyMtJni, Dispatchers.IO, threadCount,
    )

    init { require(threadCount in 1..4) { "Hy-MT2 supports one to four CPU threads" } }

    companion object {
        const val RUNTIME_VERSION = "llama-b11429-d812350-ctx1024-out256-official-prompt-v1"
    }

    private val serial = Mutex()
    private val lifecycle = Any()
    private var closed = false
    private var handle = 0L
    private var modelLease: HyMtModelUseGuard.Lease? = null
    private var active: HyMtCancellation? = null

    override fun supportsLanguage(langTag: String): Boolean = HyMtPrompt.language(langTag) != null

    override suspend fun ensureModel(sourceLang: String, targetLang: String) {
        HyMtPrompt.validatePair(sourceLang, targetLang)
        operation { cancellation -> ensureOpen(cancellation) }
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        HyMtPrompt.validatePair(sourceLang, targetLang)
        // Enforce text bounds before loading a 1.1 GB model.
        val prompt = HyMtPrompt.userMessage(text)
        return operation { cancellation ->
            if (text.isBlank()) return@operation ""
            ensureOpen(cancellation)
            val output = native.translate(handle, prompt, cancellation)
            checkCurrent(cancellation)
            check(output.size <= HyMtPrompt.MAX_OUTPUT_BYTES) { "Translation output exceeds the local model limit" }
            output.decodeToString(throwOnInvalidSequence = true).trim().also {
                check(it.isNotEmpty()) { "Local model returned no translation" }
            }
        }
    }

    /** Non-blocking while inference owns the context; its finally block releases it. */
    override fun close() {
        val release = synchronized(lifecycle) {
            if (closed) return
            closed = true
            active?.cancel()
            if (active == null) takeHandle() else null
        }
        release?.let(::releaseModel)
    }

    private suspend fun ensureOpen(cancellation: HyMtCancellation) {
        if (handle != 0L) return
        // Acquire before verification, so deletion cannot race the gap between hashing and open.
        val lease = modelUseGuard.acquire()
        var published = false
        try {
            val path = verifiedModelPath()
            checkCurrent(cancellation)
            require(File(path).isAbsolute && '\u0000' !in path) { "A verified absolute model path is required" }
            val opened = native.open(path.toByteArray(Charsets.UTF_8), threadCount, cancellation)
            check(opened != 0L) { "Local model could not be opened" }
            // Publish both owners before checking cancellation; every close path releases together.
            synchronized(lifecycle) {
                handle = opened
                modelLease = lease
                published = true
            }
            checkCurrent(cancellation)
        } catch (failure: Throwable) {
            if (published) synchronized(lifecycle) { takeHandle() }?.let(::releaseModel)
            else lease.close() // Path verification/native load failed without returning a handle.
            throw failure
        }
    }

    private suspend fun <T> operation(block: suspend (HyMtCancellation) -> T): T = withContext(dispatcher) {
        serial.withLock {
            currentCoroutineContext().ensureActive()
            val cancellation = HyMtCancellation(currentCoroutineContext()[Job])
            synchronized(lifecycle) {
                check(!closed) { "Translation engine is closed" }
                active = cancellation
            }
            try {
                val result = block(cancellation)
                checkCurrent(cancellation)
                result
            } finally {
                val release = synchronized(lifecycle) {
                    active = null
                    if (closed) takeHandle() else null
                }
                release?.let(::releaseModel)
            }
        }
    }

    private fun checkCurrent(cancellation: HyMtCancellation) {
        if (cancellation.isCancelled()) throw CancellationException("Local translation cancelled")
        synchronized(lifecycle) {
            if (closed) throw CancellationException("Translation engine is closed")
        }
    }

    /** Caller holds lifecycle. */
    private fun takeHandle(): LoadedModel? {
        if (handle == 0L) return null
        return LoadedModel(handle, checkNotNull(modelLease)).also {
            handle = 0L
            modelLease = null
        }
    }

    private fun releaseModel(model: LoadedModel) {
        native.close(model.handle)
        // If close unexpectedly throws, keep deletion blocked: release was not established.
        model.lease.close()
    }

    private data class LoadedModel(val handle: Long, val lease: HyMtModelUseGuard.Lease)
}
