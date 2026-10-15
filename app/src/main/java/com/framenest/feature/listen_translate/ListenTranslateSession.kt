package com.framenest.feature.listen_translate

import com.framenest.core.model.PlaybackIdentity
import com.framenest.data.listen_translate.ListenLanguagePair
import com.framenest.data.listen_translate.ListenProvisionalCleanup
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
 * Does not touch NAS; durable cues stay in app-private SQLite (decision 0005).
 * Uncertain recorded translations are bounded, generation-local presentation only.
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
    private val startupTimer = ListenStartupTimer(monotonicTimeMs)
    private val _ui = MutableStateFlow(ListenTranslateUiState())
    val uiState: StateFlow<ListenTranslateUiState> = _ui.asStateFlow()

    /** Called before model preparation is queued, so startup includes that wait. */
    fun beginStartupTiming(): Long {
        val token = startupTimer.begin()
        _ui.update { it.copy(startupTiming = startupTimer.sample) }
        return token
    }

    fun recordAsrPreparationTiming(token: Long, elapsedMs: Long) {
        startupTimer.recordAsrPreparation(token, elapsedMs)
        _ui.update { it.copy(startupTiming = startupTimer.sample) }
    }

    /** Capture at PCM event emission, before dispatching the event to the UI scope. */
    val startupTimingToken: Long get() = startupTimer.token

    fun recordFirstPcmTiming(token: Long, eventAtMs: Long) {
        startupTimer.recordFirstPcm(token, eventAtMs)
        _ui.update { it.copy(startupTiming = startupTimer.sample) }
    }

    private fun resetStartupTiming() {
        startupTimer.reset()
        _ui.update { it.copy(startupTiming = null) }
    }

    fun setEngine(engine: ListenTranslateEngine) {
        if (released || this.engine === engine) return
        // Invalidate the old producer before changing model/cache identity.
        stopPolling()
        this.engine = engine
        if (_ui.value.enabled) activate()
    }

    private var cacheLease: ListenTranslateRepository.CacheLease? = null
    private val processMutex = Mutex()
    private val retryPolicy = ListenWindowRetryPolicy()
    private var contentKey: String = contentKey
    private var activationJob: Job? = null
    private var pollJob: Job? = null
    private var observeJob: Job? = null
    private var cachedCues: List<ListenTranslateCue> = emptyList()
    private data class TemporaryRecordedTranslation(val source: String, val target: String)
    // At most eight validated 4,096-character targets, never merged into cachedCues/Room.
    private val temporaryRecordedTranslations = linkedMapOf<Pair<Long, Long>, TemporaryRecordedTranslation>()
    private var lastPositionMs: Long = 0L
    private var lastDurationMs: Long = 0L
    private var isPlaying: Boolean = false
    private var isBuffering: Boolean = false
    private var isForeground: Boolean = true
    private var released: Boolean = false
    @Volatile private var workGeneration: Long = 0L
    private var playbackRate: Float = 1f
    private var processingRealtimeFactor: Double? = null
    private var adaptationRealtimeFactor: Double? = null
    private var recordedRetryAtMs = 0L
    private var recordedFailures = 0
    private var recordedWorkMs = 0L
    private var recordedAdaptationWorkMs = 0L
    private var recordedAudioMs = 0L

    fun setContentKey(contentKey: String) {
        val normalized = contentKey.trim()
        if (normalized == this.contentKey) return
        resetStartupTiming()
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
            if (startupTimer.sample == null) beginStartupTiming()
            activate()
        } else {
            resetStartupTiming()
            cacheLease?.close()
            cacheLease = null
            activationJob?.cancel()
            activationJob = null
            stopPolling()
            stopObserving()
            retryPolicy.clear()
            _ui.update {
                it.copy(
                    preparingSubtitles = false,
                    preparedAheadMs = 0L,
                    activeCue = null,
                    overlayText = "",
                    currentCaptionUnconfirmed = false,
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
        resetStartupTiming()
        _ui.update { it.copy(sourceLang = next, activeCue = null, overlayText = "") }
        if (_ui.value.enabled) {
            activate()
        }
    }

    fun setTargetLang(code: String) {
        val next = code.trim().lowercase()
        if (next == _ui.value.targetLang) return
        resetStartupTiming()
        _ui.update { it.copy(targetLang = next, activeCue = null, overlayText = "") }
        if (_ui.value.enabled) {
            activate()
        }
    }

    /** Change only while stopped, before a new engine/cache identity is prepared. */
    fun setExperimentalSilenceGate(enabled: Boolean) {
        if (released || _ui.value.enabled || _ui.value.isInstallingModels) return
        _ui.update { it.copy(experimentalSilenceGate = enabled, diagnostics = null) }
    }

    fun setDisplayMode(mode: ListenDisplayMode) {
        if (mode != _ui.value.displayMode) resetStartupTiming()
        val previousLanguages = _ui.value.languages
        _ui.update {
            val overlay = ListenTranslateWindows.formatOverlay(it.activeCue, mode)
            it.copy(displayMode = mode, overlayText = overlay)
        }
        if (previousLanguages != _ui.value.languages) {
            _ui.update { it.copy(activeCue = null, overlayText = "") }
            if (_ui.value.enabled) activate()
        }
    }

    fun setPreparingSubtitles(preparing: Boolean) {
        if (released || !_ui.value.enabled || !isForeground) return
        if (_ui.value.preparingSubtitles == preparing) return
        if (preparing) {
            beginStartupTiming()
        }
        stopPolling()
        _ui.update { it.copy(preparingSubtitles = preparing, isProcessing = false) }
        refreshActiveCue()
        if (activationJob?.isActive != true) startPolling()
    }

    fun setHighQualityJapaneseChoice(enabled: Boolean, available: Boolean) {
        _ui.update { it.copy(highQualityJapanese = enabled, highQualityAvailable = available) }
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
        if (playing && _ui.value.preparingSubtitles) {
            _ui.update { it.copy(preparingSubtitles = false) }
        }
        isBuffering = buffering
        this.playbackRate = ListenPrefetchPolicy.normalizedPlaybackRate(playbackRate)
        updateEffectiveRealtimeFactor()
        if (farSeek) resetStartupTiming()
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
        resetStartupTiming()
        isForeground = foreground
        if (!foreground) _ui.update { it.copy(preparingSubtitles = false) }
        stopPolling()
        _ui.update { it.copy(isProcessing = false, prefetchLookAheadMs = 0L) }
        if (foreground && _ui.value.enabled && activationJob?.isActive != true) startPolling()
    }

    /** Explicit user seeks also cancel small jumps, before the next player tick arrives. */
    fun onSeek(positionMs: Long) {
        if (released) return
        resetStartupTiming()
        lastPositionMs = positionMs.coerceAtLeast(0L)
        _ui.update { it.copy(preparingSubtitles = false) }
        stopPolling()
        _ui.update { it.copy(isProcessing = false) }
        refreshActiveCue()
        if (_ui.value.enabled && activationJob?.isActive != true) startPolling()
    }

    fun release() {
        resetStartupTiming()
        released = true
        cacheLease?.close()
        cacheLease = null
        activationJob?.cancel()
        activationJob = null
        stopPolling()
        stopObserving()
    }

    private fun activate() {
        if (released) return
        activationJob?.cancel()
        stopPolling()
        _ui.update { it.copy(preparingSubtitles = false, preparedAheadMs = 0L) }
        processingRealtimeFactor = null
        adaptationRealtimeFactor = null
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
        clearTemporaryRecordedTranslations()
        recordedRetryAtMs = 0L
        recordedFailures = 0
        recordedWorkMs = 0L
        recordedAdaptationWorkMs = 0L
        recordedAudioMs = 0L
        _ui.update { it.copy(diagnostics = null, recordedReadThroughMs = 0L,
            currentCaptionUnconfirmed = it.activeCue?.rev == ListenCoverageRev.PROVISIONAL_SOURCE) }
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun ensureJobAndRefresh() {
        val langs = _ui.value.languages
        stopObserving()
        cacheLease?.close()
        val lease = repository.acquireLease(identity, langs)
        cacheLease = lease
        val job = repository.ensureJob(
            identity = identity,
            languages = langs,
            contentKey = contentKey,
            asrModel = engine.asrModelId,
            mtModel = cacheMtModelId(),
            status = ListenTranslateJobStatus.Partial,
            lease = lease,
        )
        currentCoroutineContext().ensureActive()
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

    private fun recordCachedStartupReadiness() {
        val langs = _ui.value.languages
        // An unrelated cached subtitle elsewhere in the movie is not startup readiness.
        val cue = ListenTranslateWindows.cueAt(
            cachedCues, lastPositionMs, includeEndFallback = !intervalCoverage(),
        )
        startupTimer.publish(
            hasSource = cue?.textSrc?.isNotBlank() == true,
            hasBilingual = cue?.rev != ListenCoverageRev.PROVISIONAL_SOURCE &&
                langs.sourceLang != langs.targetLang && cue?.textSrc?.isNotBlank() == true && cue.textTgt.isNotBlank(),
        )
    }

    private fun clearTemporaryRecordedTranslations() = synchronized(temporaryRecordedTranslations) {
        temporaryRecordedTranslations.clear()
        _ui.update { state ->
            val cue = state.activeCue
            if (cue?.rev == ListenCoverageRev.PROVISIONAL_SOURCE) {
                val sourceOnly = cue.copy(textTgt = "")
                state.copy(activeCue = sourceOnly,
                    overlayText = ListenTranslateWindows.formatOverlay(sourceOnly, state.displayMode))
            } else state
        }
    }

    private fun recordedPresentationCue(cue: ListenTranslateCue?): ListenTranslateCue? {
        if (recordedEngine() == null || cue?.rev != ListenCoverageRev.PROVISIONAL_SOURCE) return cue
        val temporary = synchronized(temporaryRecordedTranslations) {
            temporaryRecordedTranslations[cue.startMs to cue.endMs]
        }
        // Older persisted provisional targets must not reappear as trusted cache hits.
        return cue.copy(textTgt = temporary?.takeIf { it.source == cue.textSrc }?.target.orEmpty())
    }

    // Selection and UI publication share clear()'s lock: a seek cannot be followed by
    // publication of a temporary target captured just before that seek cleared memory.
    private fun refreshActiveCue() = synchronized(temporaryRecordedTranslations) {
        if (!_ui.value.enabled) return@synchronized
        temporaryRecordedTranslations.entries.removeAll { it.key.second <= lastPositionMs }
        val cue = recordedPresentationCue(ListenTranslateWindows.cueAt(
            cachedCues, lastPositionMs, includeEndFallback = !intervalCoverage(),
        ))
        val overlay = ListenTranslateWindows.formatOverlay(cue, _ui.value.displayMode)
        val ahead = if (intervalCoverage()) {
            ListenPrefetchPolicy.adaptiveContiguousAheadMs(
                completedIntervals(), lastPositionMs, lastDurationMs,
            )
        } else {
            ListenPrefetchPolicy.contiguousAheadMs(
                cachedCues, lastPositionMs, lastDurationMs, windowMs,
            )
        }
        val target = preparationTargetMs()
        if (isForeground) {
            recordCachedStartupReadiness()
            val sourceVisible = cue?.textSrc?.isNotBlank() == true &&
                _ui.value.displayMode != ListenDisplayMode.TargetOnly
            startupTimer.select(
                sourceVisible = sourceVisible,
                bilingualVisible = sourceVisible && cue.rev != ListenCoverageRev.PROVISIONAL_SOURCE &&
                    cue.textTgt.isNotBlank() &&
                    _ui.value.displayMode == ListenDisplayMode.Bilingual &&
                    _ui.value.languages.sourceLang != _ui.value.languages.targetLang,
            )
        }
        _ui.update {
            it.copy(
                activeCue = cue, overlayText = overlay, preparedAheadMs = ahead,
                currentCaptionUnconfirmed = cue?.rev == ListenCoverageRev.PROVISIONAL_SOURCE,
                startupTiming = startupTimer.sample,
                preparingSubtitles = it.preparingSubtitles && ahead < target,
            )
        }
    }

    private fun preparationTargetMs(): Long = if (lastDurationMs > 0L) {
        (lastDurationMs - lastPositionMs).coerceIn(0L, ListenPrefetchPolicy.BALANCED_LOOKAHEAD_MS)
    } else ListenPrefetchPolicy.BALANCED_LOOKAHEAD_MS

    private fun cacheMtModelId(): String =
        if (_ui.value.displayMode == ListenDisplayMode.SourceOnly) "source-only-v1" else engine.mtModelId

    private fun adaptiveMaxSpanMs(): Long? =
        engine.adaptiveMaxSpanMsFor(_ui.value.languages.sourceLang)?.takeIf { it > 0L }

    private fun completedIntervals(): List<ListenCompletedInterval> = ListenCompletedIntervals.fromCues(
        cachedCues,
        requireTranslation = !_ui.value.languages.sourceLang.equals(
            _ui.value.languages.targetLang, ignoreCase = true,
        ),
    )

    private fun recordedEngine(): RecordedListenTranslateEngine? =
        (engine as? RecordedListenTranslateEngine)?.takeIf {
            it.supportsRecordedPair(_ui.value.languages.sourceLang, _ui.value.languages.targetLang)
        }

    private fun intervalCoverage(): Boolean = adaptiveMaxSpanMs() != null || recordedEngine() != null

    /** Producer advancement never certifies the pending/uncertain audio as translated. */
    private suspend fun processRecordedAhead(
        producer: RecordedListenTranslateEngine,
        lookAheadMs: Long,
        generation: Long,
    ): Boolean {
        if (monotonicTimeMs() < recordedRetryAtMs) return false
        val remainingMs = if (lastDurationMs > 0L) {
            (lastDurationMs - lastPositionMs).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        if (remainingMs == 0L) return false
        val needed = (if (lookAheadMs > 0L) lookAheadMs else 1L).coerceAtMost(remainingMs)
        if (ListenPrefetchPolicy.adaptiveContiguousAheadMs(completedIntervals(), lastPositionMs, lastDurationMs) >= needed) return false
        return processMutex.withLock {
            ensureCurrent(generation)
            val langs = _ui.value.languages
            val lease = cacheLease
            val key = contentKey
            val asrId = producer.asrModelId
            val mtId = cacheMtModelId()
            val began = monotonicTimeMs()
            var cacheMs = 0L
            var completedAny = false
            var changedAny = false
            val previousReadThroughMs = _ui.value.recordedReadThroughMs
            fun cueChanged(startMs: Long, endMs: Long, source: String, target: String, revision: Int): Boolean =
                cachedCues.none { it.startMs == startMs && it.endMs == endMs &&
                    it.textSrc == source && it.textTgt == target && it.rev == revision }
            _ui.update { it.copy(isProcessing = true, errorMessage = null, status = ListenTranslateJobStatus.Running) }
            try {
                val progress = producer.processAhead(
                    RecordedListenRequest(generation, lastPositionMs, lastDurationMs,
                        lookAheadMs.coerceIn(0L, 30_000L), langs.sourceLang, langs.targetLang),
                ) { cue ->
                    ensureCurrent(generation)
                    require(cue.displayStartMs >= 0L && cue.displayEndMs > cue.displayStartMs)
                    require(cue.decodedThroughMs >= cue.displayEndMs)
                    require(cue.textSrc.length <= 4096 && cue.textTgt.length <= 4096)
                    if (lastDurationMs > 0L) require(cue.decodedThroughMs <= lastDurationMs)
                    val coverageStart = cue.coverageStartMs
                    val coverageEnd = cue.coverageEndMs
                    if (cue.isFinal) {
                        require(coverageStart != null && coverageEnd != null && coverageStart >= 0L && coverageEnd > coverageStart)
                        require(cue.displayStartMs >= coverageStart && cue.displayEndMs <= coverageEnd && coverageEnd <= cue.decodedThroughMs)
                        if (cue.confirmedSilence) {
                            require(cue.textSrc.isBlank() && cue.textTgt.isBlank()) {
                                "confirmed silence cannot contain speech"
                            }
                        } else require(cue.textSrc.isNotBlank() &&
                            (langs.sourceLang == langs.targetLang || cue.textTgt.isNotBlank()))
                    } else {
                        require(!cue.confirmedSilence) { "uncertain text cannot confirm silence" }
                        // A replayed source candidate must never replace an accepted final
                        // (including an exact-range blank coverage row in Room).
                        if (ListenCompletedIntervals.firstGap(completedIntervals(),
                                cue.displayStartMs, cue.displayEndMs) == null) return@processAhead
                    }
                    val supersededStart = cue.supersededStartMs
                    val supersededEnd = cue.supersededEndMs
                    require((supersededStart == null) == (supersededEnd == null))
                    if (supersededStart != null && supersededEnd != null) {
                        require(supersededStart >= 0L && supersededEnd > supersededStart &&
                            supersededEnd <= cue.decodedThroughMs)
                    }
                    val commitStarted = monotonicTimeMs()
                    val cleanup = when {
                        cue.isFinal -> ListenProvisionalCleanup(coverageStart!!, coverageEnd!!)
                        supersededStart != null -> ListenProvisionalCleanup(supersededStart, supersededEnd!!)
                        else -> null
                    }
                    changedAny = changedAny || (cleanup != null && cachedCues.any {
                        it.rev == ListenCoverageRev.PROVISIONAL_SOURCE &&
                            it.startMs >= cleanup.ownedStartMs && it.startMs < cleanup.processedThroughMs &&
                            (it.startMs != cue.displayStartMs || it.endMs != cue.displayEndMs)
                    })
                    if (cue.textSrc.isNotBlank() || cue.textTgt.isNotBlank()) {
                        val durableTarget = if (cue.isFinal) cue.textTgt else ""
                        val span = cue.displayStartMs to cue.displayEndMs
                        changedAny = changedAny || cueChanged(cue.displayStartMs, cue.displayEndMs,
                            cue.textSrc, durableTarget, if (cue.isFinal) 1 else ListenCoverageRev.PROVISIONAL_SOURCE) ||
                            (!cue.isFinal && synchronized(temporaryRecordedTranslations) {
                                temporaryRecordedTranslations[span]?.target.orEmpty() != cue.textTgt
                            })
                        val speech = repository.upsertCueForExistingJob(identity, langs,
                            cue.displayStartMs, cue.displayEndMs, cue.textSrc, durableTarget,
                            rev = if (cue.isFinal) 1 else ListenCoverageRev.PROVISIONAL_SOURCE,
                            contentKey = key, asrModel = asrId, mtModel = mtId, lease = lease,
                            provisionalCleanup = cleanup, isCurrentGeneration = { isCurrent(generation) })
                        ensureCurrent(generation)
                        cachedCues = ListenCueCache.upsert(cachedCues, speech, cleanup)
                        synchronized(temporaryRecordedTranslations) {
                            // Native work can return on a worker dispatcher while seek clears memory.
                            if (!isCurrent(generation)) throw CancellationException("stale temporary translation")
                            if (cleanup != null) temporaryRecordedTranslations.entries.removeAll {
                                it.key.first < cleanup.processedThroughMs && it.key.second > cleanup.ownedStartMs
                            }
                            temporaryRecordedTranslations.remove(span)
                            if (!cue.isFinal && cue.textTgt.isNotBlank() && cue.displayEndMs > lastPositionMs) {
                                temporaryRecordedTranslations[span] = TemporaryRecordedTranslation(cue.textSrc, cue.textTgt)
                                while (temporaryRecordedTranslations.size > 8)
                                    temporaryRecordedTranslations.remove(temporaryRecordedTranslations.keys.first())
                            }
                        }
                    }
                    if (cue.isFinal) {
                        synchronized(temporaryRecordedTranslations) {
                            temporaryRecordedTranslations.entries.removeAll {
                                it.key.first < coverageEnd!! && it.key.second > coverageStart!!
                            }
                        }
                        val sameBounds = cue.displayStartMs == coverageStart && cue.displayEndMs == coverageEnd
                        changedAny = changedAny || cueChanged(coverageStart!!, coverageEnd!!,
                            if (sameBounds) cue.textSrc else "", if (sameBounds) cue.textTgt else "", 1)
                        val completed = repository.completeWindowCueForExistingJob(identity, langs,
                            coverageStart!!, coverageEnd!!,
                            if (sameBounds) cue.textSrc else "", if (sameBounds) cue.textTgt else "", rev = 1,
                            coveredUntilMs = coverageEnd, durationMs = lastDurationMs.takeIf { it > 0L },
                            contentKey = key, asrModel = asrId, mtModel = mtId, lease = lease,
                            provisionalCleanup = cleanup, isCurrentGeneration = { isCurrent(generation) })
                        ensureCurrent(generation)
                        cachedCues = ListenCueCache.upsert(cachedCues, completed, cleanup)
                        completedAny = true
                        _ui.update { it.copy(coveredUntilMs = maxOf(it.coveredUntilMs, coverageEnd)) }
                    }
                    cacheMs += elapsedListenTimeMs(commitStarted, monotonicTimeMs())
                    startupTimer.publish(cue.textSrc.isNotBlank(), cue.isFinal &&
                        langs.sourceLang != langs.targetLang && cue.textSrc.isNotBlank() && cue.textTgt.isNotBlank())
                    if (cue.isFinal && cue.textTgt.isNotBlank()) startupTimer.recordGeneratedBilingual(cue.timings, cacheMs)
                    updateCueSummary()
                    refreshActiveCue()
                }
                ensureCurrent(generation)
                require(progress.readThroughMs >= previousReadThroughMs && progress.newAudioMs >= 0L)
                require(progress.newAudioMs <= progress.readThroughMs - previousReadThroughMs) {
                    "new audio must advance the producer read cursor"
                }
                if (lastDurationMs > 0L) require(progress.readThroughMs <= lastDurationMs)
                val elapsedMs = elapsedListenTimeMs(began, monotonicTimeMs())
                recordedWorkMs += elapsedMs
                recordedAdaptationWorkMs += ListenPrefetchPolicy.adaptationElapsedMs(
                    elapsedMs, progress.timings?.translationPreparationWaitMs,
                )
                recordedAudioMs += progress.newAudioMs
                if (recordedAudioMs > 0L) {
                    processingRealtimeFactor = recordedWorkMs.toDouble() / recordedAudioMs
                    adaptationRealtimeFactor = recordedAdaptationWorkMs.toDouble() / recordedAudioMs
                    updateEffectiveRealtimeFactor()
                }
                recordedFailures = 0
                recordedRetryAtMs = 0L
                _ui.update { it.copy(isProcessing = false, status = ListenTranslateJobStatus.Partial,
                    recordedReadThroughMs = progress.readThroughMs,
                    message = progress.message ?: it.message,
                    diagnostics = ListenPipelineDiagnostics(stages = progress.timings ?: ListenStageTimings(),
                        totalMs = elapsedListenTimeMs(began, monotonicTimeMs()), cacheCommitMs = cacheMs,
                        completed = completedAny, playbackRate = playbackRate,
                        effectiveRealtimeFactor = processingRealtimeFactor?.times(playbackRate),
                        adaptationRealtimeFactor = adaptationRealtimeFactor?.times(playbackRate))) }
                refreshActiveCue()
                // An unchanged callback or a producer's optimistic flag cannot create
                // an immediate retry loop. Zero-audio MT work still drains after a new cue.
                progress.madeProgress && (progress.readThroughMs > previousReadThroughMs || changedAny)
            } catch (cancelled: CancellationException) {
                if (generation == workGeneration) _ui.update { it.copy(isProcessing = false) }
                throw cancelled
            } catch (failure: Throwable) {
                ensureCurrent(generation)
                recordedFailures = (recordedFailures + 1).coerceAtMost(5)
                recordedRetryAtMs = monotonicTimeMs() + minOf(5_000L, 500L * (1L shl recordedFailures))
                _ui.update { it.copy(isProcessing = false, status = ListenTranslateJobStatus.Failed,
                    errorMessage = failure.message?.take(160) ?: failure.javaClass.simpleName) }
                false
            }
        }
    }

    private suspend fun maybeFillAroundPosition(generation: Long): Boolean {
        if (!_ui.value.enabled || !isForeground || released) return false
        if (isBuffering) {
            _ui.update { it.copy(prefetchLookAheadMs = 0L) }
            return false
        }
        val position = lastPositionMs
        val duration = lastDurationMs
        val lookAheadMs = if (_ui.value.preparingSubtitles) preparationTargetMs() else ListenPrefetchPolicy.lookAheadMs(
            playing = isPlaying,
            buffering = isBuffering,
            realtimeFactor = adaptationRealtimeFactor,
            playbackRate = playbackRate,
        )
        _ui.update { it.copy(prefetchLookAheadMs = lookAheadMs) }
        recordedEngine()?.let { return processRecordedAhead(it, lookAheadMs, generation) }
        val adaptiveSpan = adaptiveMaxSpanMs()
        if (adaptiveSpan != null) {
            val next = ListenPrefetchPolicy.nextAdaptiveWindow(
                intervals = completedIntervals(),
                positionMs = position,
                durationMs = duration,
                windowMs = windowMs,
                maxSpanMs = adaptiveSpan,
                lookAheadMs = lookAheadMs,
            ) ?: return false
            return processWindow(next.first, next.second, generation = generation)
        }
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
        val queuedAtMs = monotonicTimeMs()
        return processMutex.withLock {
            ensureCurrent(generation)
            // Re-check under lock.
            val adaptive = adaptiveMaxSpanMs() != null
            val needsFill = if (adaptive) {
                // If newly observed cache split this range while waiting for the lock,
                // reschedule rather than reading across a completed future boundary.
                ListenCompletedIntervals.firstGap(completedIntervals(), startMs, endMs) ==
                    ListenCompletedInterval(startMs, endMs)
            } else ListenTranslateWindows.needsFill(cachedCues, startMs, endMs)
            if (!needsFill && !allowBlankRecovery) return@withLock false
            val langs = _ui.value.languages
            val windowContentKey = contentKey
            val windowLease = cacheLease
            val windowEngine = engine
            val asrModel = windowEngine.asrModelId
            val mtModel = cacheMtModelId()
            val windowStartedAtMs = monotonicTimeMs()
            var diagnostics = ListenPipelineDiagnostics(
                queueWaitMs = elapsedListenTimeMs(queuedAtMs, windowStartedAtMs),
                windowBacklogMs = listenCueLatenessMs(lastPositionMs, startMs),
                playbackRate = playbackRate,
            )
            fun publishDiagnostics(completed: Boolean = false, failed: Boolean = false) {
                diagnostics = diagnostics.copy(
                    totalMs = elapsedListenTimeMs(windowStartedAtMs, monotonicTimeMs()),
                    completed = completed,
                    failed = failed,
                    effectiveRealtimeFactor = processingRealtimeFactor?.times(playbackRate),
                    adaptationRealtimeFactor = adaptationRealtimeFactor?.times(playbackRate),
                )
                _ui.update { it.copy(diagnostics = diagnostics) }
            }
            var publishedSource: ListenWindowResult? = null
            fun processedEnd(result: ListenWindowResult): Long {
                val actualEnd = result.processedThroughMs ?: endMs
                require(actualEnd > startMs && actualEnd <= endMs) {
                    "processedThroughMs must be inside the requested audio span"
                }
                require(adaptive || actualEnd == endMs) {
                    "partial processed spans require adaptive scheduling"
                }
                return actualEnd
            }
            fun speechBounds(result: ListenWindowResult): Pair<Long, Long> {
                val actualEnd = processedEnd(result)
                val cueStart = (result.cueStartMs ?: startMs).coerceIn(startMs, actualEnd)
                val cueEnd = (result.cueEndMs ?: actualEnd).coerceIn(cueStart, actualEnd)
                require(!adaptive || cueEnd > cueStart) { "speech cue must have a nonempty processed span" }
                return cueStart to cueEnd
            }
            fun validateSourceSpan(result: ListenWindowResult) {
                val actualEnd = processedEnd(result)
                publishedSource?.let { source ->
                    require(processedEnd(source) == actualEnd && speechBounds(source) == speechBounds(result)) {
                        "stable source and final result must use the same processed span"
                    }
                }
            }
            suspend fun publishSpeech(
                result: ListenWindowResult,
                provisional: Boolean = false,
                commitProgress: Boolean = false,
            ) {
                ensureCurrent(generation)
                val (cueStart, cueEnd) = speechBounds(result)
                val commitStartedAtMs = monotonicTimeMs()
                val revision = if (adaptive && provisional) ListenCoverageRev.PROVISIONAL_SOURCE else 1
                val cleanup = if (adaptive && !provisional) ListenProvisionalCleanup(startMs, processedEnd(result)) else null
                val persistedSpeech = if (commitProgress) {
                    repository.completeWindowCueForExistingJob(
                        identity, langs, cueStart, cueEnd, result.textSrc, result.textTgt,
                        rev = revision, coveredUntilMs = processedEnd(result),
                        durationMs = lastDurationMs.takeIf { it > 0L },
                        contentKey = windowContentKey, asrModel = asrModel, mtModel = mtModel,
                        lease = windowLease,
                        provisionalCleanup = cleanup,
                        isCurrentGeneration = { isCurrent(generation) },
                    )
                } else repository.upsertCueForExistingJob(
                    identity, langs, cueStart, cueEnd, result.textSrc, result.textTgt,
                    rev = revision, contentKey = windowContentKey, asrModel = asrModel, mtModel = mtModel,
                    lease = windowLease,
                    provisionalCleanup = cleanup,
                    isCurrentGeneration = { isCurrent(generation) },
                )
                ensureCurrent(generation)
                cachedCues = ListenCueCache.upsert(cachedCues, persistedSpeech, cleanup)
                startupTimer.publish(
                    hasSource = result.textSrc.isNotBlank(),
                    hasBilingual = result.textSrc.isNotBlank() && result.textTgt.isNotBlank() &&
                        langs.sourceLang != langs.targetLang,
                )
                if (result.textSrc.isNotBlank() && result.textTgt.isNotBlank() &&
                    langs.sourceLang != langs.targetLang
                ) {
                    startupTimer.recordGeneratedBilingual(
                        result.stageTimings,
                        diagnostics.cacheCommitMs + elapsedListenTimeMs(commitStartedAtMs, monotonicTimeMs()),
                    )
                }
                updateCueSummary()
                refreshActiveCue()
                val now = monotonicTimeMs()
                diagnostics = diagnostics.copy(
                    stages = result.stageTimings ?: diagnostics.stages,
                    cacheCommitMs = diagnostics.cacheCommitMs + elapsedListenTimeMs(commitStartedAtMs, now),
                    sourceReadyMs = diagnostics.sourceReadyMs ?: if (result.textSrc.isNotBlank()) {
                        elapsedListenTimeMs(windowStartedAtMs, now)
                    } else null,
                    sourceLatenessMs = diagnostics.sourceLatenessMs ?: if (result.textSrc.isNotBlank()) {
                        listenCueLatenessMs(lastPositionMs, cueStart)
                    } else null,
                    translationReadyMs = if (result.textTgt.isNotBlank()) {
                        elapsedListenTimeMs(windowStartedAtMs, now)
                    } else diagnostics.translationReadyMs,
                    translationLatenessMs = if (result.textTgt.isNotBlank()) {
                        listenCueLatenessMs(lastPositionMs, cueStart)
                    } else diagnostics.translationLatenessMs,
                )
                publishDiagnostics()
            }
            _ui.update {
                it.copy(
                    isProcessing = true,
                    status = ListenTranslateJobStatus.Running,
                    errorMessage = null,
                    diagnostics = diagnostics,
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
                        validateSourceSpan(source)
                        if (source.textSrc.isNotBlank()) {
                            publishSpeech(source, provisional = true)
                            publishedSource = source
                        }
                    },
                )
                ensureCurrent(generation)
                diagnostics = diagnostics.copy(stages = result.stageTimings ?: diagnostics.stages)
                if (result.blankReason == ListenBlankReason.EmptyPcm) {
                    _ui.update { it.copy(lastBlankReason = ListenBlankReason.EmptyPcm) }
                    throw ListenWindowStageException(
                        "当前音轨未读取到音频，请切换音轨或重新打开视频后重试",
                    )
                }
                val actualEndMs = processedEnd(result)
                validateSourceSpan(result)
                val translationMissing = result.textSrc.isNotBlank() && result.textTgt.isBlank() &&
                    !langs.sourceLang.equals(langs.targetLang, ignoreCase = true)
                val completedSuccessfully = result.retryableErrorMessage == null && !translationMissing
                var speechCoversWindow = false
                var progressCommittedWithCoverage = false
                if (result.textSrc.isNotBlank() || result.textTgt.isNotBlank()) {
                    val (cueStart, cueEnd) = speechBounds(result)
                    speechCoversWindow = cueStart == startMs && cueEnd == actualEndMs
                    progressCommittedWithCoverage = adaptive && completedSuccessfully && speechCoversWindow
                    val source = publishedSource
                    if (adaptive || source == null || source.textSrc != result.textSrc ||
                        source.textTgt != result.textTgt || source.cueStartMs != result.cueStartMs ||
                        source.cueEndMs != result.cueEndMs
                    ) publishSpeech(
                        result,
                        provisional = !completedSuccessfully,
                        commitProgress = progressCommittedWithCoverage,
                    )
                }
                result.retryableErrorMessage?.let { message ->
                    throw ListenWindowStageException(message)
                }
                if (translationMissing) {
                    // A successful-but-empty MT result is not completed coverage. Without
                    // backoff the immediate-drain loop could repeatedly re-run the same ASR.
                    throw ListenWindowStageException("翻译未返回文本，请稍后重试")
                }
                val coverageStartedAtMs = monotonicTimeMs()
                if (!speechCoversWindow) {
                    // Persist only the consumed span, excluding decoded read-ahead. Writing this
                    // after the speech cue means cancellation cannot hide completed text;
                    // coverage and progress themselves commit in one Room transaction.
                    val cleanup = if (adaptive && result.blankReason != ListenBlankReason.UnrecognizedSpeech) {
                        ListenProvisionalCleanup(startMs, actualEndMs)
                    } else null
                    val persistedCoverage = repository.completeWindowCueForExistingJob(
                        identity = identity,
                        languages = langs,
                        startMs = startMs,
                        endMs = actualEndMs,
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
                        coveredUntilMs = actualEndMs,
                        durationMs = lastDurationMs.takeIf { it > 0L },
                        contentKey = windowContentKey,
                        asrModel = asrModel,
                        mtModel = mtModel,
                        lease = windowLease,
                        provisionalCleanup = cleanup,
                        isCurrentGeneration = { isCurrent(generation) },
                    )
                    ensureCurrent(generation)
                    cachedCues = ListenCueCache.upsert(cachedCues, persistedCoverage, cleanup)
                    progressCommittedWithCoverage = true
                }
                if (!progressCommittedWithCoverage) {
                    repository.updateProgress(
                        identity = identity,
                        languages = langs,
                        coveredUntilMs = actualEndMs,
                        status = ListenTranslateJobStatus.Partial,
                        durationMs = lastDurationMs.takeIf { it > 0L },
                    )
                }
                ensureCurrent(generation)
                diagnostics = diagnostics.copy(
                    cacheCommitMs = diagnostics.cacheCommitMs +
                        elapsedListenTimeMs(coverageStartedAtMs, monotonicTimeMs()),
                )
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
                        coveredUntilMs = actualEndMs.coerceAtLeast(it.coveredUntilMs),
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
                val elapsedMs = elapsedListenTimeMs(windowStartedAtMs, monotonicTimeMs())
                processingRealtimeFactor = ListenPrefetchPolicy.updateRealtimeFactor(
                    previous = processingRealtimeFactor,
                    elapsedMs = elapsedMs,
                    audioMs = actualEndMs - startMs,
                )
                adaptationRealtimeFactor = ListenPrefetchPolicy.updateRealtimeFactor(
                    previous = adaptationRealtimeFactor,
                    elapsedMs = ListenPrefetchPolicy.adaptationElapsedMs(
                        elapsedMs, result.stageTimings?.translationPreparationWaitMs,
                    ),
                    audioMs = actualEndMs - startMs,
                )
                updateEffectiveRealtimeFactor()
                publishDiagnostics(completed = true)
                true
            } catch (cancelled: CancellationException) {
                if (generation == workGeneration) _ui.update { it.copy(isProcessing = false) }
                throw cancelled
            } catch (t: Throwable) {
                ensureCurrent(generation)
                diagnostics = diagnostics.copy(
                    stages = (t as? ListenPipelineException)?.stages ?: diagnostics.stages,
                )
                publishDiagnostics(failed = true)
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
        if (!isCurrent(generation)) {
            throw CancellationException("stale listen-translate window")
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        !released && isForeground && _ui.value.enabled && generation == workGeneration && cacheLease?.active == true

    private fun updateEffectiveRealtimeFactor() {
        _ui.update {
            it.copy(
                effectiveRealtimeFactor = processingRealtimeFactor?.times(playbackRate),
                adaptationRealtimeFactor = adaptationRealtimeFactor?.times(playbackRate),
            )
        }
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
