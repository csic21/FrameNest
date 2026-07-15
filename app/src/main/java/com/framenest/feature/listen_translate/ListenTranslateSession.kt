package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenTranslateCue
import com.framenest.data.listen_translate.ListenTranslateJobStatus
import com.framenest.data.listen_translate.ListenTranslateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates Room cache + [ListenTranslateEngine] while the player runs.
 * Does not touch NAS; all cues stay in app-private SQLite (decision 0005).
 */
class ListenTranslateSession(
    private val repository: ListenTranslateRepository,
    private var engine: ListenTranslateEngine,
    private val scope: CoroutineScope,
    private val identity: PlaybackIdentity,
    contentKey: String = "",
    private val windowMs: Long = ListenTranslateWindows.DEFAULT_WINDOW_MS,
    private val pollIntervalMs: Long = 500L,
) {
    private val _ui = MutableStateFlow(ListenTranslateUiState())
    val uiState: StateFlow<ListenTranslateUiState> = _ui.asStateFlow()

    fun setEngine(engine: ListenTranslateEngine) {
        this.engine = engine
    }

    private val processMutex = Mutex()
    private val retryPolicy = ListenWindowRetryPolicy()
    private var contentKey: String = contentKey
    private var activationJob: Job? = null
    private var pollJob: Job? = null
    private var observeJob: Job? = null
    private var cachedCues: List<ListenTranslateCue> = emptyList()
    private var lastPositionMs: Long = 0L
    private var lastDurationMs: Long = 0L
    private var isPlaying: Boolean = false

    fun setContentKey(contentKey: String) {
        val normalized = contentKey.trim()
        if (normalized == this.contentKey) return
        this.contentKey = normalized
        if (_ui.value.enabled) {
            activate()
        }
    }

    fun setEnabled(enabled: Boolean) {
        // Preserve a preparation/processing failure when the caller disables the
        // session as part of failure cleanup. A fresh enable attempt clears it.
        _ui.update {
            it.copy(
                enabled = enabled,
                errorMessage = if (enabled) null else it.errorMessage,
            )
        }
        if (enabled) {
            activate()
        } else {
            activationJob?.cancel()
            activationJob = null
            stopPolling()
            stopObserving()
            retryPolicy.clear()
            _ui.update {
                it.copy(
                    activeCue = null,
                    overlayText = "",
                    isProcessing = false,
                    status = ListenTranslateJobStatus.Idle,
                )
            }
        }
    }

    fun setSourceLang(code: String) {
        val next = code.trim().lowercase()
        if (next == _ui.value.sourceLang) return
        _ui.update { it.copy(sourceLang = next, activeCue = null, overlayText = "") }
        if (_ui.value.enabled) {
            activate()
        }
    }

    fun setTargetLang(code: String) {
        val next = code.trim().lowercase()
        if (next == _ui.value.targetLang) return
        _ui.update { it.copy(targetLang = next, activeCue = null, overlayText = "") }
        if (_ui.value.enabled) {
            activate()
        }
    }

    fun setDisplayMode(mode: ListenDisplayMode) {
        _ui.update {
            val overlay = ListenTranslateWindows.formatOverlay(it.activeCue, mode)
            it.copy(displayMode = mode, overlayText = overlay)
        }
    }

    fun setModelsReady(ready: Boolean) {
        _ui.update { it.copy(modelsReady = ready) }
    }

    fun setInstallingModels(installing: Boolean, message: String? = null, error: String? = null) {
        _ui.update {
            it.copy(
                isInstallingModels = installing,
                message = message ?: it.message,
                errorMessage = error,
            )
        }
    }

    fun reportError(message: String) {
        _ui.update { it.copy(errorMessage = message, isInstallingModels = false) }
    }

    fun onPlaybackTick(positionMs: Long, durationMs: Long, playing: Boolean) {
        lastPositionMs = positionMs.coerceAtLeast(0L)
        lastDurationMs = durationMs.coerceAtLeast(0L)
        isPlaying = playing
        refreshActiveCue()
    }

    fun release() {
        activationJob?.cancel()
        activationJob = null
        stopPolling()
        stopObserving()
    }

    private fun activate() {
        activationJob?.cancel()
        stopPolling()
        activationJob = scope.launch {
            ensureJobAndRefresh()
            if (_ui.value.enabled) startPolling()
        }
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            val langs = _ui.value.languages
            repository.observeCues(identity, langs).collect { cues ->
                cachedCues = cues
                refreshActiveCue()
            }
        }
    }

    private fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
        cachedCues = emptyList()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (_ui.value.enabled) {
                    maybeFillAroundPosition()
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun ensureJobAndRefresh() {
        val langs = _ui.value.languages
        stopObserving()
        val job = repository.ensureJob(
            identity = identity,
            languages = langs,
            contentKey = contentKey,
            asrModel = engine.asrModelId,
            mtModel = engine.mtModelId,
            status = ListenTranslateJobStatus.Partial,
        )
        cachedCues = repository.listCues(identity, langs)
        _ui.update {
            it.copy(
                status = job.status,
                coveredUntilMs = job.coveredUntilMs,
                message = "模型：${engine.asrModelId} / ${engine.mtModelId}（本机）",
            )
        }
        startObserving()
        refreshActiveCue()
        maybeFillAroundPosition()
    }

    private fun refreshActiveCue() {
        if (!_ui.value.enabled) return
        val cue = ListenTranslateWindows.cueAt(cachedCues, lastPositionMs)
        val overlay = ListenTranslateWindows.formatOverlay(cue, _ui.value.displayMode)
        _ui.update { it.copy(activeCue = cue, overlayText = overlay) }
    }

    private suspend fun maybeFillAroundPosition() {
        if (!_ui.value.enabled) return
        val position = lastPositionMs
        val duration = lastDurationMs
        val (start, end) = ListenTranslateWindows.windowContaining(
            positionMs = position,
            windowMs = windowMs,
            durationMs = duration,
        )
        if (!ListenTranslateWindows.needsFill(cachedCues, start, end)) {
            // Prefetch next window while playing.
            if (isPlaying) {
                val nextStart = end
                if (duration <= 0L || nextStart < duration) {
                    val nextEnd = if (duration > 0L) {
                        (nextStart + windowMs).coerceAtMost(duration)
                    } else {
                        nextStart + windowMs
                    }
                    if (nextEnd > nextStart &&
                        ListenTranslateWindows.needsFill(cachedCues, nextStart, nextEnd)
                    ) {
                        processWindow(nextStart, nextEnd)
                    }
                }
            }
            return
        }
        processWindow(start, end)
    }

    private suspend fun processWindow(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        val attemptKey = ListenWindowAttemptKey(
            startMs = startMs,
            endMs = endMs,
            languages = _ui.value.languages,
        )
        if (!retryPolicy.canAttempt(attemptKey)) return
        processMutex.withLock {
            // Re-check under lock.
            if (!ListenTranslateWindows.needsFill(cachedCues, startMs, endMs)) return
            val langs = _ui.value.languages
            _ui.update {
                it.copy(
                    isProcessing = true,
                    status = ListenTranslateJobStatus.Running,
                    errorMessage = null,
                )
            }
            try {
                val result = engine.processWindow(
                    startMs = startMs,
                    endMs = endMs,
                    sourceLang = langs.sourceLang,
                    targetLang = langs.targetLang,
                )
                var speechCoversWindow = false
                if (result.textSrc.isNotBlank() || result.textTgt.isNotBlank()) {
                    val cueStart = (result.cueStartMs ?: startMs).coerceIn(startMs, endMs)
                    val cueEnd = (result.cueEndMs ?: endMs).coerceIn(cueStart, endMs)
                    speechCoversWindow = cueStart == startMs && cueEnd == endMs
                    repository.upsertCue(
                        identity = identity,
                        languages = langs,
                        startMs = cueStart,
                        endMs = cueEnd,
                        textSrc = result.textSrc,
                        textTgt = result.textTgt,
                        rev = 1,
                        contentKey = contentKey,
                    )
                    // Make ASR text visible even when the following MT stage failed.
                    // A source-only cue deliberately does not satisfy needsFill(), so
                    // the same window remains eligible for the retry backoff below.
                    cachedCues = repository.listCues(identity, langs)
                    refreshActiveCue()
                }
                result.retryableErrorMessage?.let { message ->
                    throw ListenWindowStageException(message)
                }
                if (!speechCoversWindow) {
                    // Persist the whole attempted window, including silence. Writing this
                    // after the speech cue means cancellation cannot hide completed text.
                    repository.upsertCue(
                        identity = identity,
                        languages = langs,
                        startMs = startMs,
                        endMs = endMs,
                        textSrc = "",
                        textTgt = "",
                        rev = COVERAGE_REV,
                        contentKey = contentKey,
                    )
                }
                repository.updateProgress(
                    identity = identity,
                    languages = langs,
                    coveredUntilMs = endMs,
                    status = ListenTranslateJobStatus.Partial,
                    durationMs = lastDurationMs.takeIf { it > 0L },
                )
                cachedCues = repository.listCues(identity, langs)
                retryPolicy.recordSuccess(attemptKey)
                _ui.update {
                    it.copy(
                        isProcessing = false,
                        status = ListenTranslateJobStatus.Partial,
                        coveredUntilMs = endMs.coerceAtLeast(it.coveredUntilMs),
                        message = if (result.textSrc.isBlank()) {
                            it.message
                        } else {
                            "ASR: ${engine.asrModelId} · MT: ${engine.mtModelId}"
                        },
                    )
                }
                refreshActiveCue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val msg = t.message?.take(160) ?: t.javaClass.simpleName
                if (isNonRetryableListenFailure(t)) {
                    retryPolicy.recordPermanentFailure(attemptKey)
                } else {
                    retryPolicy.recordFailure(attemptKey)
                }
                _ui.update {
                    it.copy(
                        isProcessing = false,
                        status = ListenTranslateJobStatus.Failed,
                        errorMessage = msg,
                    )
                }
                runCatching {
                    repository.updateProgress(
                        identity = identity,
                        languages = langs,
                        coveredUntilMs = _ui.value.coveredUntilMs,
                        status = ListenTranslateJobStatus.Failed,
                        lastError = msg,
                    )
                }
            }
        }
    }

    private companion object {
        const val COVERAGE_REV = 0
    }
}

internal data class ListenWindowAttemptKey(
    val startMs: Long,
    val endMs: Long,
    val languages: ListenLanguagePair,
)

/** In-memory retry backoff; successful and silent windows persist as coverage cues. */
internal class ListenWindowRetryPolicy(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 60_000L,
) {
    private data class Failure(val count: Int, val retryAtMs: Long)

    private val failures = mutableMapOf<ListenWindowAttemptKey, Failure>()

    fun canAttempt(key: ListenWindowAttemptKey): Boolean =
        failures[key]?.let { nowMs() >= it.retryAtMs } ?: true

    fun recordSuccess(key: ListenWindowAttemptKey) {
        failures.remove(key)
    }

    fun recordFailure(key: ListenWindowAttemptKey) {
        val count = (failures[key]?.count ?: 0) + 1
        val shift = (count - 1).coerceAtMost(20)
        val delayMs = (baseDelayMs * (1L shl shift)).coerceAtMost(maxDelayMs)
        failures[key] = Failure(count = count, retryAtMs = nowMs() + delayMs)
    }

    fun recordPermanentFailure(key: ListenWindowAttemptKey) {
        failures[key] = Failure(count = Int.MAX_VALUE, retryAtMs = Long.MAX_VALUE)
    }

    fun clear() {
        failures.clear()
    }
}

internal fun isNonRetryableListenFailure(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it is UnsupportedOperationException || it is ModelsNotReadyException
    }

private class ListenWindowStageException(message: String) : IllegalStateException(message)
