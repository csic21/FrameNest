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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
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
    private var isBuffering: Boolean = false
    private var isForeground: Boolean = true
    private var released: Boolean = false
    private var workGeneration: Long = 0L
    private var playbackRate: Float = 1f
    private var processingRealtimeFactor: Double? = null

    fun setContentKey(contentKey: String) {
        val normalized = contentKey.trim()
        if (normalized == this.contentKey) return
        this.contentKey = normalized
        // Codec/network failures from the old audio variant must not block the new one.
        retryPolicy.clear()
        if (_ui.value.enabled) {
            activate()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (released) return
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
                    prefetchLookAheadMs = 0L,
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

    fun onPlaybackTick(
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        buffering: Boolean = false,
        playbackRate: Float = 1f,
    ) {
        if (released) return
        val normalizedPosition = positionMs.coerceAtLeast(0L)
        val farSeek = ListenPrefetchPolicy.isFarSeek(lastPositionMs, normalizedPosition, windowMs)
        val enteredBuffering = buffering && !isBuffering
        lastPositionMs = normalizedPosition
        lastDurationMs = durationMs.coerceAtLeast(0L)
        isPlaying = playing
        isBuffering = buffering
        this.playbackRate = ListenPrefetchPolicy.normalizedPlaybackRate(playbackRate)
        updateEffectiveRealtimeFactor()
        refreshActiveCue()
        if (_ui.value.enabled && (farSeek || enteredBuffering)) {
            // Cancel stale look-ahead immediately. The replacement poll either targets
            // the new seek position or waits without touching SMB while buffering.
            stopPolling()
            _ui.update { it.copy(isProcessing = false) }
            startPolling()
        }
    }

    /** Background retains enable intent/cache, but cannot start or publish window work. */
    fun setForeground(foreground: Boolean) {
        if (released || isForeground == foreground) return
        isForeground = foreground
        stopPolling()
        _ui.update { it.copy(isProcessing = false, prefetchLookAheadMs = 0L) }
        if (foreground && _ui.value.enabled && activationJob?.isActive != true) startPolling()
    }

    /** Explicit user seeks also cancel small jumps, before the next player tick arrives. */
    fun onSeek(positionMs: Long) {
        if (released) return
        lastPositionMs = positionMs.coerceAtLeast(0L)
        stopPolling()
        _ui.update { it.copy(isProcessing = false) }
        refreshActiveCue()
        if (_ui.value.enabled && activationJob?.isActive != true) startPolling()
    }

    fun release() {
        released = true
        activationJob?.cancel()
        activationJob = null
        stopPolling()
        stopObserving()
    }

    private fun activate() {
        if (released) return
        activationJob?.cancel()
        stopPolling()
        processingRealtimeFactor = null
        updateEffectiveRealtimeFactor()
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
                updateCueSummary()
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
        if (released || !isForeground) return
        if (pollJob?.isActive == true) return
        val generation = workGeneration
        pollJob = scope.launch {
            while (isActive) {
                val progressed = if (_ui.value.enabled) maybeFillAroundPosition(generation) else false
                // Drain only the bounded horizon while useful work exists. Fixed sleeping
                // after every successful window wasted 500ms and was absent from the RTF.
                if (progressed) yield() else delay(pollIntervalMs)
            }
        }
    }

    private fun stopPolling() {
        workGeneration++
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
                generatedCueCount = generatedCueCount(),
                message = "模型：${engine.asrModelId} / ${engine.mtModelId}（本机）",
            )
        }
        startObserving()
        refreshActiveCue()
    }

    private fun refreshActiveCue() {
        if (!_ui.value.enabled) return
        val cue = ListenTranslateWindows.cueAt(cachedCues, lastPositionMs)
        val overlay = ListenTranslateWindows.formatOverlay(cue, _ui.value.displayMode)
        _ui.update { it.copy(activeCue = cue, overlayText = overlay) }
    }

    private suspend fun maybeFillAroundPosition(generation: Long): Boolean {
        if (!_ui.value.enabled || !isForeground || released) return false
        if (isBuffering) {
            _ui.update { it.copy(prefetchLookAheadMs = 0L) }
            return false
        }
        val position = lastPositionMs
        val duration = lastDurationMs
        val lookAheadMs = ListenPrefetchPolicy.lookAheadMs(
            playing = isPlaying,
            buffering = isBuffering,
            realtimeFactor = processingRealtimeFactor,
            playbackRate = playbackRate,
        )
        _ui.update { it.copy(prefetchLookAheadMs = lookAheadMs) }
        val current = ListenTranslateWindows.windowContaining(position, windowMs, duration)
        val recoverCurrent = ListenTranslateWindows.needsBlankRecoveryAt(
            cues = cachedCues,
            positionMs = position,
            startMs = current.first,
            endMs = current.second,
        )
        val next = if (recoverCurrent) {
            current
        } else {
            ListenPrefetchPolicy.nextWindow(
                cues = cachedCues,
                positionMs = position,
                durationMs = duration,
                windowMs = windowMs,
                lookAheadMs = lookAheadMs,
            )
        } ?: return false
        return processWindow(
            startMs = next.first,
            endMs = next.second,
            allowBlankRecovery = recoverCurrent && next == current,
            generation = generation,
        )
    }

    private suspend fun processWindow(
        startMs: Long,
        endMs: Long,
        allowBlankRecovery: Boolean = false,
        generation: Long,
    ): Boolean {
        if (endMs <= startMs) return false
        val attemptKey = ListenWindowAttemptKey(
            startMs = startMs,
            endMs = endMs,
            languages = _ui.value.languages,
        )
        if (!retryPolicy.canAttempt(attemptKey)) return false
        return processMutex.withLock {
            ensureCurrent(generation)
            // Re-check under lock.
            val needsFill = ListenTranslateWindows.needsFill(cachedCues, startMs, endMs)
            if (!needsFill && !allowBlankRecovery) return@withLock false
            val langs = _ui.value.languages
            val windowContentKey = contentKey
            val windowEngine = engine
            val asrModel = windowEngine.asrModelId
            val mtModel = windowEngine.mtModelId
            val windowStartedAtMs = monotonicTimeMs()
            var publishedSource: ListenWindowResult? = null
            suspend fun publishSpeech(result: ListenWindowResult) {
                ensureCurrent(generation)
                val cueStart = (result.cueStartMs ?: startMs).coerceIn(startMs, endMs)
                val cueEnd = (result.cueEndMs ?: endMs).coerceIn(cueStart, endMs)
                val persistedSpeech = repository.upsertCueForExistingJob(
                    identity, langs, cueStart, cueEnd, result.textSrc, result.textTgt,
                    rev = 1, contentKey = windowContentKey, asrModel = asrModel, mtModel = mtModel,
                )
                ensureCurrent(generation)
                cachedCues = ListenCueCache.upsert(cachedCues, persistedSpeech)
                updateCueSummary()
                refreshActiveCue()
            }
            _ui.update {
                it.copy(
                    isProcessing = true,
                    status = ListenTranslateJobStatus.Running,
                    errorMessage = null,
                )
            }
            try {
                val result = windowEngine.processWindowWithProgress(
                    startMs = startMs,
                    endMs = endMs,
                    sourceLang = langs.sourceLang,
                    targetLang = langs.targetLang,
                    onSourceRecognized = { source ->
                        ensureCurrent(generation)
                        if (source.textSrc.isNotBlank()) {
                            publishSpeech(source)
                            publishedSource = source
                        }
                    },
                )
                ensureCurrent(generation)
                if (result.blankReason == ListenBlankReason.EmptyPcm) {
                    _ui.update { it.copy(lastBlankReason = ListenBlankReason.EmptyPcm) }
                    throw ListenWindowStageException(
                        "当前音轨未读取到音频，请切换音轨或重新打开视频后重试",
                    )
                }
                var speechCoversWindow = false
                if (result.textSrc.isNotBlank() || result.textTgt.isNotBlank()) {
                    val cueStart = (result.cueStartMs ?: startMs).coerceIn(startMs, endMs)
                    val cueEnd = (result.cueEndMs ?: endMs).coerceIn(cueStart, endMs)
                    speechCoversWindow = cueStart == startMs && cueEnd == endMs
                    val source = publishedSource
                    if (source == null || source.textSrc != result.textSrc ||
                        source.textTgt != result.textTgt || source.cueStartMs != result.cueStartMs ||
                        source.cueEndMs != result.cueEndMs
                    ) publishSpeech(result)
                }
                result.retryableErrorMessage?.let { message ->
                    throw ListenWindowStageException(message)
                }
                if (result.textSrc.isNotBlank() && result.textTgt.isBlank() &&
                    !langs.sourceLang.equals(langs.targetLang, ignoreCase = true)
                ) {
                    // A successful-but-empty MT result is not completed coverage. Without
                    // backoff the immediate-drain loop could repeatedly re-run the same ASR.
                    throw ListenWindowStageException("翻译未返回文本，请稍后重试")
                }
                var progressCommittedWithCoverage = false
                if (!speechCoversWindow) {
                    // Persist the whole attempted window, including silence. Writing this
                    // after the speech cue means cancellation cannot hide completed text;
                    // coverage and progress themselves commit in one Room transaction.
                    val persistedCoverage = repository.completeWindowCueForExistingJob(
                        identity = identity,
                        languages = langs,
                        startMs = startMs,
                        endMs = endMs,
                        textSrc = "",
                        textTgt = "",
                        rev = when (result.blankReason) {
                            ListenBlankReason.NearSilence -> ListenCoverageRev.CONFIRMED_SILENCE
                            ListenBlankReason.UnrecognizedSpeech ->
                                ListenCoverageRev.UNRECOGNIZED_SPEECH
                            // Engines predating blank diagnostics treated empty text as
                            // a successful silent window. Preserve that contract.
                            else -> ListenCoverageRev.CONFIRMED_SILENCE
                        },
                        coveredUntilMs = endMs,
                        durationMs = lastDurationMs.takeIf { it > 0L },
                        contentKey = windowContentKey,
                        asrModel = asrModel,
                        mtModel = mtModel,
                    )
                    ensureCurrent(generation)
                    cachedCues = ListenCueCache.upsert(cachedCues, persistedCoverage)
                    progressCommittedWithCoverage = true
                }
                if (!progressCommittedWithCoverage) {
                    repository.updateProgress(
                        identity = identity,
                        languages = langs,
                        coveredUntilMs = endMs,
                        status = ListenTranslateJobStatus.Partial,
                        durationMs = lastDurationMs.takeIf { it > 0L },
                    )
                }
                ensureCurrent(generation)
                updateCueSummary()
                if (result.blankReason == ListenBlankReason.UnrecognizedSpeech) {
                    retryPolicy.recordRecoverableBlank(attemptKey)
                } else {
                    retryPolicy.recordSuccess(attemptKey)
                }
                _ui.update {
                    it.copy(
                        isProcessing = false,
                        status = ListenTranslateJobStatus.Partial,
                        coveredUntilMs = endMs.coerceAtLeast(it.coveredUntilMs),
                        generatedCueCount = generatedCueCount(),
                        lastBlankReason = result.blankReason,
                        message = if (result.textSrc.isBlank()) {
                            it.message
                        } else {
                            "ASR: ${engine.asrModelId} · MT: ${engine.mtModelId}"
                        },
                    )
                }
                refreshActiveCue()
                processingRealtimeFactor = ListenPrefetchPolicy.updateRealtimeFactor(
                    previous = processingRealtimeFactor,
                    elapsedMs = monotonicTimeMs() - windowStartedAtMs,
                    audioMs = endMs - startMs,
                )
                updateEffectiveRealtimeFactor()
                true
            } catch (cancelled: CancellationException) {
                if (generation == workGeneration) _ui.update { it.copy(isProcessing = false) }
                throw cancelled
            } catch (t: Throwable) {
                ensureCurrent(generation)
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
                false
            }
        }
    }

    private suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (released || !isForeground || !_ui.value.enabled || generation != workGeneration) {
            throw CancellationException("stale listen-translate window")
        }
    }

    private fun updateEffectiveRealtimeFactor() {
        _ui.update { it.copy(effectiveRealtimeFactor = processingRealtimeFactor?.times(playbackRate)) }
    }

    private fun generatedCueCount(): Int =
        cachedCues.count { it.textSrc.isNotBlank() || it.textTgt.isNotBlank() }

    private fun updateCueSummary() {
        _ui.update { it.copy(generatedCueCount = generatedCueCount()) }
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
    private val maxRecoverableBlankAttempts: Int = 2,
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

    fun recordRecoverableBlank(key: ListenWindowAttemptKey) {
        val count = (failures[key]?.count ?: 0) + 1
        if (count >= maxRecoverableBlankAttempts) {
            recordPermanentFailure(key)
        } else {
            val shift = (count - 1).coerceAtMost(20)
            val delayMs = (baseDelayMs * (1L shl shift)).coerceAtMost(maxDelayMs)
            failures[key] = Failure(count = count, retryAtMs = nowMs() + delayMs)
        }
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
