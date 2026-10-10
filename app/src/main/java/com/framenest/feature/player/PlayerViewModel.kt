package com.framenest.feature.player

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.framenest.FrameNestApplication
import com.framenest.core.model.PlaybackDataSource
import com.framenest.core.model.PlaybackRequest
import com.framenest.data.history.PlaybackHistoryRepository
import com.framenest.data.history.PlaybackProgressRules
import com.framenest.data.listen_translate.ListenContentKey
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.AppDatabase
import com.framenest.data.settings.AsrEngineChoice
import com.framenest.data.settings.UserPreferences
import com.framenest.feature.listen_translate.ListenDisplayMode
import com.framenest.feature.listen_translate.ListenCacheVariant
import com.framenest.feature.listen_translate.ListenTranslateEngine
import com.framenest.feature.listen_translate.ListenTranslateSession
import com.framenest.feature.listen_translate.ListenTranslateUiState
import com.framenest.feature.listen_translate.ListenWindowResult
import com.framenest.feature.listen_translate.ModelsNotReadyException
import com.framenest.feature.listen_translate.RealListenTranslateEngine
import com.framenest.feature.listen_translate.asr.AsrEngine
import com.framenest.feature.listen_translate.asr.AsrModelSupport
import com.framenest.feature.listen_translate.asr.SherpaAsrEngine
import com.framenest.feature.listen_translate.asr.SherpaAsrModelSupport
import com.framenest.feature.listen_translate.asr.SherpaModelInstaller
import com.framenest.feature.listen_translate.asr.VoskAsrEngine
import com.framenest.feature.listen_translate.asr.VoskAsrModelSupport
import com.framenest.feature.listen_translate.asr.VoskModelInstaller
import com.framenest.feature.listen_translate.audio.ListenAudioSource
import com.framenest.feature.listen_translate.audio.ListenAudioSources
import com.framenest.feature.listen_translate.mt.MlKitMtEngine
import com.framenest.feature.subtitle.ExternalSubtitleLoader
import com.framenest.feature.subtitle.ExternalSubtitleOption
import com.framenest.feature.subtitle.SidecarSubtitleScanner
import com.framenest.feature.subtitle.SubtitleFontSizes
import com.framenest.feature.subtitle.SubtitleLanguagePrefs
import com.framenest.feature.subtitle.SubtitleMatcher
import com.framenest.feature.subtitle.SubtitleSelectionKeys
import com.framenest.feature.subtitle.SubtitleTrackLists
import com.framenest.feature.subtitle.SubtitleUiState
import com.framenest.player.CredentialRedactor
import com.framenest.player.MediaSource
import com.framenest.player.PlaybackRates
import com.framenest.player.PlayerController
import com.framenest.player.PlayerError
import com.framenest.player.PlayerErrorMapper
import com.framenest.player.PlayerState
import com.framenest.player.SmbCredentials
import com.framenest.player.SmbMediaUri
import com.framenest.player.VideoScaleMode
import com.framenest.player.VlcPlayerController
import com.framenest.smb.SmbClient
import com.framenest.smb.SmbTransportOwner
import com.framenest.smb.SmbException
import com.framenest.smb.SmbPathUtils
import com.framenest.smb.SmbjClient
import com.framenest.smb.SmbCredentials as SmbSessionCredentials
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Product player session: open remote media through libVLC direct SMB, first-frame paused,
 * progress save, retry, release coordination, subtitle selection (FN-06),
 * and listen-translate (FN-14: Vosk ASR + ML Kit MT).
 */
class PlayerViewModel(
    application: Application,
    private val request: PlaybackRequest,
    private val historyRepository: PlaybackHistoryRepository,
    private val listenTranslateRepository: ListenTranslateRepository =
        resolveListenTranslate(application),
    private val controllerFactory: (Application) -> PlayerController = { app ->
        VlcPlayerController(app, enableHwDecoder = true)
    },
    private val sidecarScanner: SidecarSubtitleScanner = SidecarSubtitleScanner(),
    private val subtitleLoader: ExternalSubtitleLoader = ExternalSubtitleLoader(
        application, sessionCacheKey = java.util.UUID.randomUUID().toString(),
    ),
    private val browseSessionReleaser: () -> Unit = {
        (application as? FrameNestApplication)?.container?.browseRepository?.releaseSession()
    },
    private val directoryFileNamesLoader: ((share: String, parentPath: String) -> List<String>)? = null,
    private val auxiliaryClientFactory: () -> SmbClient = { SmbjClient() },
    private val retryTeardown: suspend () -> Unit = {},
) : AndroidViewModel(application) {

    val controller: PlayerController = controllerFactory(application)

    // Explicit navigation exit cancels this scope immediately; configuration changes
    // keep the ViewModel/session alive. onCleared is only an idempotent fallback.
    private val sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(viewModelScope.coroutineContext + sessionJob)
    private val progressPersistence = PlaybackProgressPersistence.Shared

    private val _scrubPreviewFrames = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val scrubPreviewFrames: StateFlow<Map<Long, Bitmap>> = _scrubPreviewFrames.asStateFlow()
    private val _scrubPreviewFailed = MutableStateFlow<Set<Long>>(emptySet())
    val scrubPreviewFailed: StateFlow<Set<Long>> = _scrubPreviewFailed.asStateFlow()
    private val scrubPreviewExtractor = ScrubPreviewExtractor(application, request)
    private val scrubWarmsDuringPlayback: Boolean =
        request.dataSource !is PlaybackDataSource.SeekableSmb &&
            request.dataSource !is PlaybackDataSource.DirectSmbUrl

    @Volatile
    private var scrubFocusMs: Long = -1L
    @Volatile
    private var scrubGestureGeneration = 0L
    private val scrubPreviewScheduler: ScrubPreviewScheduler = ScrubPreviewScheduler(sessionScope) { bucket ->
        if (abandonScrubExtract(bucket)) return@ScrubPreviewScheduler ScrubLoadResult.Abandoned
        val gesture = scrubGestureGeneration
        var delivered: ScrubGrab? = null
        val grabbed = try {
            withContext(Dispatchers.IO) {
                scrubPreviewExtractor.frameAt(bucket) { abandonScrubExtract(bucket) }
                    .also { delivered = it }
            }
        } catch (cancelled: CancellationException) {
            (delivered as? ScrubGrab.Image)?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "scrub preview failed: ${t.javaClass.simpleName}")
            ScrubGrab.Miss
        }
        if (!currentCoroutineContext().isActive || !sessionJob.isActive) {
            if (grabbed is ScrubGrab.Image && !grabbed.bitmap.isRecycled) grabbed.bitmap.recycle()
            throw CancellationException()
        }
        if (gesture != scrubGestureGeneration || abandonScrubExtract(bucket)) {
            if (grabbed is ScrubGrab.Image && !grabbed.bitmap.isRecycled) grabbed.bitmap.recycle()
            return@ScrubPreviewScheduler ScrubLoadResult.Abandoned
        }
        if (scrubFocusMs < 0L) scrubPreviewExtractor.pause()
        when (grabbed) {
            ScrubGrab.Abandoned -> ScrubLoadResult.Abandoned
            ScrubGrab.Miss -> {
                _scrubPreviewFailed.update { it + bucket }
                ScrubLoadResult.Failed
            }
            is ScrubGrab.Image -> {
                val bitmap = grabbed.bitmap
                val current = _scrubPreviewFrames.value
                val merged = HashMap<Long, Bitmap>(current.size + 1)
                merged.putAll(current)
                merged[bucket] = bitmap
                val keep = ScrubPreviewPlan.retain(
                    keys = merged.keys,
                    anchorMs = controller.state.value.positionMs,
                    scrubTargetMs = scrubFocusMs.takeIf { it >= 0L },
                    maxEntries = ScrubPreviewPlan.MAX_MEMORY_FRAMES,
                )
                _scrubPreviewFrames.value = merged.filterKeys { it in keep }
                scrubPreviewScheduler.invalidateReadyBuckets(merged.keys - keep)
                ScrubLoadResult.Ready
            }
        }
    }

    private fun abandonScrubExtract(bucket: Long): Boolean {
        val focus = scrubFocusMs
        val focusBucketReady = focus >= 0L &&
            ScrubPreviewPlan.bucketStartMs(focus) in _scrubPreviewFrames.value
        val focusBucketFailed = focus >= 0L &&
            ScrubPreviewPlan.bucketStartMs(focus) in _scrubPreviewFailed.value
        return ScrubPreviewPlan.shouldAbandonScrubExtract(
            requestedBucketMs = bucket,
            focusMs = focus,
            focusBucketReady = focusBucketReady,
            focusBucketFailed = focusBucketFailed,
        )
    }

    val playerState: StateFlow<PlayerState> = controller.state.stateIn(
        scope = sessionScope,
        started = SharingStarted.Eagerly,
        initialValue = controller.state.value,
    )

    private val _subtitleUiState = MutableStateFlow(SubtitleUiState())
    val subtitleUiState: StateFlow<SubtitleUiState> = _subtitleUiState.asStateFlow()
    private val subtitleSelectionGate = SubtitleSelectionGate()

    private val _siblingNavState = MutableStateFlow(SiblingNavUiState())
    val siblingNavState: StateFlow<SiblingNavUiState> = _siblingNavState.asStateFlow()

    private val voskInstaller = VoskModelInstaller(application)
    private val sherpaInstaller = SherpaModelInstaller(application)
    private val userPreferences = UserPreferences(application)
    private var realListenEngine: RealListenTranslateEngine? = null
    private var listenTransports = SmbTransportOwner()
    private var listenPrepareJob: Job? = null
    @Volatile
    private var listenPrepareGeneration: Long = 0L
    private val listenPrepareMutex = Mutex()
    private var listenRestartPendingAfterSourceChange = false
    private var listenPreparationPendingAfterBackground = false
    private val listenBaseContentKey = initialListenContentKey(application, request)

    private val listenSession = ListenTranslateSession(
        repository = listenTranslateRepository,
        engine = PendingListenEngine,
        scope = sessionScope,
        identity = request.identity,
        contentKey = ListenCacheVariant.contentKey(listenBaseContentKey, null),
    )
    val listenTranslateUiState: StateFlow<ListenTranslateUiState> = listenSession.uiState

    private val audioFocus = PlayerAudioFocus(
        context = application,
        onFocusLost = { playbackLifecycle.onFocusLost() },
        onFocusGained = { playbackLifecycle.onFocusGained() },
    )
    private val playbackLifecycle: PlaybackLifecycle = PlaybackLifecycle(
        controller = controller,
        requestFocus = audioFocus::request,
        abandonFocus = audioFocus::abandon,
        restoreSurface = { ready ->
            val vlc = controller as? VlcPlayerController
            if (vlc != null) vlc.rebindVideoOutput(ready) else {
                controller.refreshVideoSurfaces()
                ready()
            }
        },
        repaint = {
            val snapshot = controller.state.value
            if (com.framenest.player.PlayerRotationPolicy.shouldRepaintOnForeground(
                    snapshot.firstFrameReady, snapshot.phase,
                )
            ) {
                (controller as? VlcPlayerController)?.repaintCurrentFrame()
                    ?: controller.seekTo(snapshot.positionMs)
            }
        },
    )

    private var progressJob: Job? = null
    /** Outstanding open lifecycle; retry replaces any in-flight open. */
    private var openJob: Job? = null
    private var directoryJob: Job? = null
    private var directoryGeneration = 0L
    private var directoryTransports = SmbTransportOwner()
    private val retryResumeLatch = RetryResumeLatch()
    /** Volatile: read on a background teardown thread to skip a duplicate save. */
    @Volatile
    private var lastSavedPositionMs: Long? = null
    /** Only successful writes deduplicate; a queued final snapshot can retry a failed pause save. */
    @Volatile
    private var lastSavedProgress: Pair<Long, Long>? = null
    private var preserveUnchangedHistory = false
    private var progressTouched = false
    private var startPositionMs: Long = request.startPositionMs
    /**
     * One-shot latch for the resume-position seek. Replacing `startPositionMs > 0`
     * as the "fired?" flag fixes a race where pressing play before the first Ready
     * state let `startPositionMs` drag the viewer back to the resume point on a
     * later Paused event. See [ResumeSeekGate].
     */
    private val resumeSeekGate = ResumeSeekGate()
    private var subtitleBootstrapDone: Boolean = false
    private var siblingBootstrapDone: Boolean = false
    private var preferredLanguages: List<String> = resolvePreferredLanguages(application)
    init {
        // Drop the reused browse TCP session before libVLC opens its own SMB
        // transport. A half-dead browse socket after playback is what left the
        // folder spinner running indefinitely.
        browseSessionReleaser()
        openJob = sessionScope.launch {
            loadResumeAndOpen()
        }
        sessionScope.launch {
            controller.state.collect { state ->
                if (playbackLifecycle.closed) return@collect
                playbackLifecycle.onPlaybackState(state)
                if (state.firstFrameReady) retryResumeLatch.ready()
                if (state.phase == PlayerState.Phase.Preparing && subtitleBootstrapDone) {
                    // EOF replay creates a fresh native media, so its subtitle slaves
                    // must be attached again when the new first frame is available.
                    retireDirectoryScan()
                    subtitleLoader.cancelPendingLoads()
                    subtitleBootstrapDone = false
                    siblingBootstrapDone = false
                    subtitleSelectionGate.advance()
                }
                listenSession.setContentKey(
                    ListenCacheVariant.contentKey(
                        listenBaseContentKey,
                        selectedAudioTrackOrdinal(state),
                    ),
                )
                listenSession.onPlaybackTick(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    playing = state.phase == PlayerState.Phase.Playing,
                    buffering = state.isBuffering,
                    playbackRate = state.playbackRate,
                )
                if (state.phase == PlayerState.Phase.Playing) {
                    progressTouched = true
                    ensureProgressLoop()
                } else if (state.phase == PlayerState.Phase.Ended) {
                    saveProgressNow(force = true)
                    stopProgressLoop()
                }
                if (state.firstFrameReady && (!subtitleBootstrapDone || !siblingBootstrapDone)) {
                    val includeSubtitles = !subtitleBootstrapDone
                    val includeSiblings = !siblingBootstrapDone
                    subtitleBootstrapDone = true
                    siblingBootstrapDone = true
                    bootstrapDirectoryFeatures(
                        state = state,
                        includeSubtitles = includeSubtitles,
                        includeSiblings = includeSiblings,
                    )
                }
                scrubPreviewScheduler.updatePlayback(
                    durationMs = state.durationMs,
                    anchorMs = state.positionMs,
                    buffering = state.isBuffering,
                    // A second SMB reader during playback competes with the picture.
                    // Remote previews wait until the finger is down.
                    active = playbackLifecycle.foreground && scrubWarmsDuringPlayback &&
                        state.firstFrameReady &&
                        state.phase != PlayerState.Phase.Idle &&
                        state.phase != PlayerState.Phase.Preparing &&
                        state.phase != PlayerState.Phase.Error,
                )
            }
        }
    }

    fun setListenTranslateEnabled(enabled: Boolean) {
        if (playbackLifecycle.closed) return
        if (!enabled) {
            listenPreparationPendingAfterBackground = false
            listenRestartPendingAfterSourceChange = false
            listenPrepareGeneration += 1L
            listenPrepareJob?.cancel()
            listenPrepareJob = null
            retireListenTransports()
            listenSession.setEnabled(false)
            listenSession.setInstallingModels(installing = false)
            // Release the native recognizer (and its SMB audio handle) now instead
            // of holding ~240MB of SenseVoice weights until the player is left.
            val engine = realListenEngine
            realListenEngine = null
            if (engine != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { engine.close() }
                }
            }
            return
        }
        listenRestartPendingAfterSourceChange = false
        restartListenPreparation()
    }

    fun setListenSourceLang(code: String) {
        if (playbackLifecycle.closed) return
        val restart = listenSession.uiState.value.let { it.enabled || it.isInstallingModels }
        if (restart) listenSession.setEnabled(false)
        listenSession.setSourceLang(code)
        if (restart) restartListenPreparation()
    }

    fun setListenTargetLang(code: String) {
        if (playbackLifecycle.closed) return
        val restart = listenSession.uiState.value.let { it.enabled || it.isInstallingModels }
        if (restart) listenSession.setEnabled(false)
        listenSession.setTargetLang(code)
        if (restart) restartListenPreparation()
    }

    fun setListenExperimentalSilenceGate(enabled: Boolean) {
        if (playbackLifecycle.closed) return
        listenSession.setExperimentalSilenceGate(enabled)
    }

    fun setListenDisplayMode(mode: ListenDisplayMode) {
        if (playbackLifecycle.closed) return
        listenSession.setDisplayMode(mode)
    }

    private fun restartListenPreparation() {
        if (playbackLifecycle.closed) return
        if (!playbackLifecycle.foreground) {
            listenPreparationPendingAfterBackground = true
            return
        }
        listenPreparationPendingAfterBackground = false
        if (listenSession.uiState.value.enabled) {
            listenSession.setEnabled(false)
        }
        val sourceLang = listenSession.uiState.value.sourceLang
        val targetLang = listenSession.uiState.value.targetLang
        val generation = ++listenPrepareGeneration
        listenPrepareJob?.cancel()
        retireListenTransports()
        val transports = listenTransports
        listenSession.setInstallingModels(
            installing = true,
            message = "正在切换到 $sourceLang→$targetLang…",
            error = null,
        )
        listenPrepareJob = sessionScope.launch {
            enableRealListenTranslate(generation, sourceLang, targetLang, transports)
        }
    }

    private fun reportListenPreparation(generation: Long, message: String) {
        sessionScope.launch {
            if (generation == listenPrepareGeneration && !playbackLifecycle.closed) {
                listenSession.setInstallingModels(installing = true, message = message)
            }
        }
    }

    private suspend fun enableRealListenTranslate(
        generation: Long,
        sourceLang: String,
        targetLang: String,
        transports: SmbTransportOwner,
    ) = listenPrepareMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (generation != listenPrepareGeneration) return@withLock
        val experimentalSilenceGate = listenSession.uiState.value.experimentalSilenceGate
        val previous = realListenEngine
        realListenEngine = null
        closeListenPreparationResources(listOf({ previous?.close() }))
        val asrSupport: AsrModelSupport = when (userPreferences.asrEngine()) {
            AsrEngineChoice.VOSK -> VoskAsrModelSupport(voskInstaller)
            AsrEngineChoice.SHERPA -> SherpaAsrModelSupport(sherpaInstaller)
        }
        val asrReady = asrSupport.isInstalled(sourceLang)
        listenSession.setModelsReady(false)
        listenSession.setInstallingModels(
            installing = true,
            message = if (asrReady) {
                "本机 ASR 已就绪（$sourceLang），正在启动听译…"
            } else {
                asrSupport.downloadingMessage(sourceLang)
            },
            error = null,
        )
        var pendingAudio: ListenAudioSource? = null
        var pendingAsr: AsrEngine? = null
        var pendingMt: MlKitMtEngine? = null
        suspend fun closePendingResources() {
            closeListenPreparationResources(
                listOf(
                    { pendingAudio?.close() },
                    { pendingAsr?.close() },
                    { pendingMt?.close() },
                ),
            )
            pendingAudio = null
            pendingAsr = null
            pendingMt = null
        }
        val prepared = try {
            val engine = withContext(Dispatchers.IO) {
                ensureSmbConnectedForListen(transports)
                val audio = buildListenAudioSource(transports)
                    ?: error("当前片源暂不支持听译音频（需要本地文件或 SMB 随机读）")
                pendingAudio = audio
                var lastProgressPercent = -1
                val allowMeteredDownloads = userPreferences.allowMeteredModelDownloads()
                val installingEngineLabel = when (asrSupport.engineChoice()) {
                    AsrEngineChoice.VOSK -> "Vosk($sourceLang)"
                    AsrEngineChoice.SHERPA -> "SenseVoice"
                }
                asrSupport.ensureInstalled(
                    langTag = sourceLang,
                    allowMeteredDownloads = allowMeteredDownloads,
                ) { p ->
                    if (generation != listenPrepareGeneration) return@ensureInstalled
                    val percent = (p * 100f).toInt().coerceIn(0, 100)
                    if (percent != lastProgressPercent) {
                        lastProgressPercent = percent
                        reportListenPreparation(generation, "正在准备 $installingEngineLabel：$percent%")
                    }
                }
                currentCoroutineContext().ensureActive()
                val asr: AsrEngine = when (asrSupport.engineChoice()) {
                    AsrEngineChoice.VOSK -> VoskAsrEngine()
                    AsrEngineChoice.SHERPA ->
                        SherpaAsrEngine(assetManager = getApplication<Application>().assets)
                }
                val mt = MlKitMtEngine(allowMeteredDownloads = allowMeteredDownloads)
                pendingAsr = asr
                pendingMt = mt
                reportListenPreparation(generation, "正在载入本机 ASR 模型 $sourceLang…")
                val modelDir = asrSupport.modelDir(sourceLang)
                    ?: error(asrSupport.incompleteModelMessage(sourceLang))
                asr.ensureModel(modelDir, sourceLang)
                reportListenPreparation(generation, "正在准备本机翻译模型 $sourceLang→$targetLang…")
                mt.ensureModel(sourceLang, targetLang)
                currentCoroutineContext().ensureActive()
                val engine = RealListenTranslateEngine(
                    audio = audio,
                    asr = asr,
                    mt = mt,
                    asrModels = asrSupport,
                    selectedAudioTrackOrdinal = { selectedAudioTrackOrdinal() },
                    asrModelLabel = { asrSupport.modelLabel(sourceLang) },
                    mtModelLabel = { "mlkit-v1-$sourceLang-$targetLang" },
                    experimentalSilenceGate = experimentalSilenceGate,
                )
                if (generation != listenPrepareGeneration) {
                    throw CancellationException("stale listen-translate preparation")
                }
                engine
            }
            currentCoroutineContext().ensureActive()
            if (generation != listenPrepareGeneration || playbackLifecycle.closed) {
                throw CancellationException("stale listen-translate preparation")
            }
            realListenEngine = engine
            listenSession.setEngine(engine)
            pendingAudio = null
            pendingAsr = null
            pendingMt = null
            true
        } catch (cancelled: CancellationException) {
            transports.retire()
            closePendingResources()
            throw cancelled
        } catch (t: Throwable) {
            transports.retire()
            closePendingResources()
            if (generation == listenPrepareGeneration) {
                val msg = listenTranslatePreparationError(t)
                listenSession.setInstallingModels(
                    installing = false,
                    message = "听译未启动",
                    error = msg,
                )
                listenSession.setModelsReady(false)
                listenSession.setEnabled(false)
            }
            false
        }
        if (!prepared || generation != listenPrepareGeneration) return@withLock
        listenSession.setModelsReady(true)
        listenSession.setInstallingModels(
            installing = false,
            message = asrSupport.readyMessage(sourceLang),
            error = null,
        )
        listenSession.setEnabled(true)
    }

    private fun buildListenAudioSource(transports: SmbTransportOwner): ListenAudioSource? {
        val app = getApplication<Application>()
        return when (val ds = request.dataSource) {
            is PlaybackDataSource.LocalFile -> ListenAudioSources.forLocalFile(ds.path)
            is PlaybackDataSource.LocalRawResource ->
                ListenAudioSources.forRaw(app, ds.resId)
            is PlaybackDataSource.SeekableSmb -> {
                if (transports.currentClient() == null) return null
                ListenAudioSources.forSmb(
                    clientProvider = { transports.currentClient() },
                    reconnectClient = {
                        ensureSmbConnectedForListen(transports)
                        transports.currentClient()
                    },
                    share = ds.share,
                    path = ds.path,
                )
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                if (transports.currentClient() == null) return null
                ListenAudioSources.forSmb(
                    clientProvider = { transports.currentClient() },
                    reconnectClient = {
                        ensureSmbConnectedForListen(transports)
                        transports.currentClient()
                    },
                    share = ds.share,
                    path = ds.path,
                )
            }
        }
    }

    private fun selectedAudioTrackOrdinal(
        state: PlayerState = controller.state.value,
    ): Int? {
        val selectedId = state.selectedAudioTrackId ?: return null
        return state.audioTracks
            .filter { it.id >= 0 }
            .indexOfFirst { it.id == selectedId }
            .takeIf { it >= 0 }
    }

    /**
     * Ensure an SMB session exists for second-path audio decode while VLC plays.
     */
    private suspend fun ensureSmbConnectedForListen(transports: SmbTransportOwner) = withContext(Dispatchers.IO) {
        transports.ensureActive()
        val creds = when (val ds = request.dataSource) {
            is PlaybackDataSource.SeekableSmb -> SmbSessionCredentials(
                ds.host, ds.port, ds.username, ds.password.copyOf(), ds.domain, ds.requireEncryption,
            )
            is PlaybackDataSource.DirectSmbUrl -> SmbSessionCredentials(
                ds.host, ds.port ?: 445, ds.username, ds.password.toCharArray(), ds.domain.orEmpty(), ds.requireEncryption,
            )
            else -> return@withContext
        }
        transports.currentClient()?.let(transports::release)
        val client = auxiliaryClientFactory()
        try {
            // Publish before connect, not after its potentially blocking negotiation.
            transports.register(client)
            // A cancelled reconnect may have raced a foreground/background detach.
            // Register first, then check: either interrupt owns it or catch releases it.
            currentCoroutineContext().ensureActive()
            client.connect(creds)
            transports.ensureActive()
            currentCoroutineContext().ensureActive()
        } catch (t: Throwable) {
            transports.release(client)
            throw t
        } finally {
            creds.clearPassword()
        }
    }

    private fun retireListenTransports() {
        listenTransports.retire()
        listenTransports = SmbTransportOwner()
    }

    fun play() {
        if (playbackLifecycle.closed) return
        if (controller.state.value.phase == PlayerState.Phase.Error) {
            retry()
            return
        }
        progressTouched = true
        resumeSeekGate.markFired()
        startPositionMs = 0L
        playbackLifecycle.play()
    }

    fun pause() {
        if (playbackLifecycle.closed) return
        playbackLifecycle.pause()
        saveProgressNow(force = true)
    }

    /**
     * User's chosen rate saved while a press-and-hold 2x boost is active;
     * null when not boosting. Restored verbatim on release.
     */
    private var speedBoostSavedRate: Float? = null

    /**
     * Finger is down on the timeline. The playing surface stays where it is;
     * preview frames are decoded beside it and the real seek happens on release.
     */
    fun setScrubbing(active: Boolean) {
        if (!active) {
            scrubGestureGeneration++
            scrubFocusMs = -1L
            scrubPreviewScheduler.setScrubbing(false, null)
            scrubPreviewExtractor.pause()
        }
    }

    fun previewSeekTo(positionMs: Long) {
        if (playbackLifecycle.closed || !playbackLifecycle.foreground) return
        val starting = scrubFocusMs < 0L
        scrubFocusMs = positionMs
        if (starting) {
            scrubGestureGeneration++
            _scrubPreviewFailed.value = emptySet()
        }
        scrubPreviewScheduler.setScrubbing(true, positionMs)
    }

    fun seekTo(positionMs: Long) {
        scrubGestureGeneration++
        scrubFocusMs = -1L
        scrubPreviewScheduler.setScrubbing(false, null)
        scrubPreviewExtractor.pause()
        commitSeek(positionMs)
    }

    private fun commitSeek(positionMs: Long) {
        if (playbackLifecycle.closed || !playbackLifecycle.foreground) return
        val phase = controller.state.value.phase
        if (phase == PlayerState.Phase.Idle || phase == PlayerState.Phase.Preparing || phase == PlayerState.Phase.Error) return
        progressTouched = true
        resumeSeekGate.markFired()
        retryResumeLatch.userSeek(positionMs)
        startPositionMs = 0L
        controller.seekTo(positionMs)
        listenSession.onSeek(positionMs)
    }

    /**
     * Relative skip (e.g. ±10s from double-tap). Clamped to media bounds.
     * Uses the same seek path as the slider.
     */
    fun skipBy(deltaMs: Long) {
        val state = controller.state.value
        val phase = state.phase
        if (phase == PlayerState.Phase.Error ||
            phase == PlayerState.Phase.Idle ||
            phase == PlayerState.Phase.Preparing
        ) {
            return
        }
        val target = SkipSeekMath.targetPositionMs(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            deltaMs = deltaMs,
        )
        seekTo(target)
    }

    fun selectAudioTrack(trackId: Int) {
        if (playbackLifecycle.closed) return
        // Only re-key listen-translate after the native switch succeeds, so a
        // rejected switch cannot strand the cache on the wrong audio ordinal.
        if (!controller.selectAudioTrack(trackId)) return
        val state = controller.state.value
        val ordinal = state.audioTracks
            .filter { it.id >= 0 }
            .indexOfFirst { it.id == trackId }
            .takeIf { it >= 0 }
        listenSession.setContentKey(ListenCacheVariant.contentKey(listenBaseContentKey, ordinal))
    }

    fun setPlaybackRate(rate: Float) {
        if (playbackLifecycle.closed) return
        // An explicit rate choice always wins over a held speed boost.
        speedBoostSavedRate = null
        controller.setPlaybackRate(rate)
    }

    fun cyclePlaybackRate() {
        if (playbackLifecycle.closed) return
        val boostedFrom = speedBoostSavedRate
        speedBoostSavedRate = null
        val boostedRate = controller.state.value.playbackRate
        // While boosting the controller sits at 2x; cycle from the user's real
        // rate so one tap moves 1x -> 1.25x instead of 2x -> 0.5x.
        val base = if (boostedFrom != null && boostedRate == SpeedBoostPolicy.BOOST_RATE) {
            boostedFrom
        } else {
            boostedRate
        }
        controller.setPlaybackRate(PlaybackRates.next(base))
    }

    /**
     * Press-and-hold 2x skim (top-tier player signature). Saves the user's
     * chosen rate and temporarily pins playback to [SpeedBoostPolicy.BOOST_RATE];
     * [stopSpeedBoost] restores it on release. Returns false when boost cannot
     * start (not really playing), so the gesture layer can fall through to the
     * normal tap behavior instead of swallowing it.
     */
    fun startSpeedBoost(): Boolean {
        if (speedBoostSavedRate != null) return true
        if (!SpeedBoostPolicy.isEligiblePhase(controller.state.value.phase)) return false
        speedBoostSavedRate = controller.state.value.playbackRate
        controller.setPlaybackRate(SpeedBoostPolicy.BOOST_RATE)
        return true
    }

    fun stopSpeedBoost() {
        val saved = speedBoostSavedRate ?: return
        speedBoostSavedRate = null
        val restore = SpeedBoostPolicy.restoreRateOrNull(
            currentRate = controller.state.value.playbackRate,
            savedUserRate = saved,
        ) ?: return
        controller.setPlaybackRate(restore)
    }

    /**
     * Load same-directory video siblings for prev/next. Safe to call multiple times;
     * failures leave [siblingNavState] empty (no prev/next UI).
     */
    fun loadSiblingPlaylist() {
        if (playbackLifecycle.closed) return
        val includeSubtitles = !subtitleBootstrapDone || _subtitleUiState.value.scanning
        subtitleBootstrapDone = true
        siblingBootstrapDone = true
        bootstrapDirectoryFeatures(controller.state.value, includeSubtitles, includeSiblings = true)
    }

    private fun retireDirectoryScan() {
        directoryGeneration++
        directoryJob?.cancel()
        directoryJob = null
        directoryTransports.retire()
        directoryTransports = SmbTransportOwner()
    }

    private fun listDirectoryFileNames(
        transports: SmbTransportOwner,
        share: String,
        parentPath: String,
        host: String,
        port: Int,
        username: String,
        password: CharArray,
        domain: String,
        requireEncryption: Boolean,
    ): List<String> {
        transports.ensureActive()
        directoryFileNamesLoader?.let { return it(share, parentPath) }
        // Directory enumeration gets its own connection. SMBJ caches DiskShare by
        // name, so using the playback/listen client could close an active read handle.
        val client = auxiliaryClientFactory()
        val creds = SmbSessionCredentials(
            host = host,
            port = port,
            username = username,
            password = password.copyOf(),
            domain = domain,
            requireEncryption = requireEncryption,
        )
        return try {
            transports.register(client)
            client.connect(creds)
            transports.ensureActive()
            client.listDirectory(share, parentPath)
                .filter { !it.isDirectory }
                .map { it.name }
        } finally {
            transports.release(client)
            creds.clearPassword()
        }
    }

    /** Cycle BestFit → FitScreen → Fill → 16:9 → 4:3 → Original. */
    fun cycleVideoScaleMode() {
        if (playbackLifecycle.closed) return
        val next = controller.state.value.videoScaleMode.next()
        controller.setVideoScaleMode(next)
    }

    fun setVideoScaleMode(mode: VideoScaleMode) {
        if (playbackLifecycle.closed) return
        controller.setVideoScaleMode(mode)
    }

    fun selectSubtitleOff() {
        if (playbackLifecycle.closed) return
        subtitleSelectionGate.advance()
        subtitleLoader.cancelPendingLoads()
        controller.disableSubtitles()
        _subtitleUiState.update {
            it.copy(selectedKey = SubtitleSelectionKeys.OFF, errorMessage = null)
        }
    }

    fun selectEmbeddedSubtitle(trackId: Int) {
        if (playbackLifecycle.closed) return
        subtitleSelectionGate.advance()
        subtitleLoader.cancelPendingLoads()
        controller.selectSubtitleTrack(trackId)
        _subtitleUiState.update {
            it.copy(
                selectedKey = SubtitleSelectionKeys.embedded(trackId),
                errorMessage = null,
            )
        }
    }

    fun selectExternalSubtitle(option: ExternalSubtitleOption) {
        if (playbackLifecycle.closed) return
        val selectionGeneration = subtitleSelectionGate.advance()
        subtitleLoader.cancelPendingLoads()
        sessionScope.launch {
            loadAndSelectExternal(
                option = option,
                userInitiated = true,
                selectionGeneration = selectionGeneration,
            )
        }
    }

    fun adjustSubtitleDelayMs(deltaMs: Long) {
        if (playbackLifecycle.closed) return
        val next = (_subtitleUiState.value.delayMs + deltaMs).coerceIn(-10_000L, 10_000L)
        controller.setSubtitleDelayMs(next)
        _subtitleUiState.update { it.copy(delayMs = next) }
    }

    fun setSubtitleFontRelSize(relSize: Int) {
        if (playbackLifecycle.closed) return
        val size = if (relSize in SubtitleFontSizes.ALL) {
            relSize
        } else {
            SubtitleFontSizes.NORMAL
        }
        controller.setSubtitleFontRelSize(size)
        _subtitleUiState.update { it.copy(fontRelSize = size) }
    }

    fun retry() {
        if (playbackLifecycle.closed) return
        // Capture on main BEFORE replacing the job/closing native media. A second
        // retry during I/O teardown must retain this target, not its synthetic Idle/0.
        startPositionMs = retryResumeLatch.capture(
            if (resumeSeekGate.hasFired) retryResumePosition(
                controller.progressState.positionMs, lastSavedPositionMs,
            ) else startPositionMs,
        )
        resumeSeekGate.reset()
        pauseListenForSourceChange()
        retireListenTransports()
        retireDirectoryScan()
        subtitleLoader.cancelPendingLoads()
        subtitleSelectionGate.advance()
        subtitleBootstrapDone = false
        siblingBootstrapDone = false
        stopProgressLoop()
        openJob?.cancel()
        openJob = sessionScope.launch {
            (controller as? VlcPlayerController)?.closeCurrentMedia()
            withContext(Dispatchers.IO) { retryTeardown() }
            currentCoroutineContext().ensureActive()
            _subtitleUiState.value = SubtitleUiState(
                delayMs = _subtitleUiState.value.delayMs,
                fontRelSize = _subtitleUiState.value.fontRelSize,
            )
            _siblingNavState.value = SiblingNavUiState()
            startPositionMs = retryResumeLatch.capture(startPositionMs)
            loadResumeAndOpen(forceReloadHistory = false)
        }
    }

    /** Background retains the session and user intent; navigation uses [onLeave]. */
    fun onLeaveOrBackground() {
        if (playbackLifecycle.closed) return
        val snapshot = controller.progressState
        playbackLifecycle.onBackground()
        listenSession.setForeground(false)
        // Coroutine cancellation cannot interrupt a synchronous NAS read. Detach and
        // abort its client off-main; the retained engine reconnects on foreground.
        listenTransports.interruptClients()
        if (listenPrepareJob?.isActive == true) {
            listenPreparationPendingAfterBackground = true
            listenPrepareGeneration++
            listenPrepareJob?.cancel()
            listenPrepareJob = null
            retireListenTransports()
            listenSession.setInstallingModels(installing = false)
        }
        stopSpeedBoost()
        setScrubbing(false)
        scrubPreviewScheduler.updatePlayback(
            snapshot.durationMs, snapshot.positionMs, buffering = true, active = false,
        )
        scheduleProgressSave(snapshot, force = true)
    }

    /** Only a real background return restores playback; first entry stays paused. */
    fun onReturnToForeground() {
        if (playbackLifecycle.closed) return
        playbackLifecycle.onForeground()
        listenSession.setForeground(true)
        if (listenPreparationPendingAfterBackground) restartListenPreparation()
    }

    /** Release every destination-owned resource now, without waiting for navigation to clear the VM. */
    fun onLeave() {
        if (playbackLifecycle.closed) return
        val snapshot = controller.progressState
        scheduleProgressSave(snapshot, force = true)
        if (!playbackLifecycle.close()) return
        subtitleSelectionGate.advance()
        retireDirectoryScan()
        directoryTransports.retire()
        subtitleLoader.close()
        listenTransports.retire()
        listenPrepareGeneration++
        sessionJob.cancel()
        openJob = null
        listenPrepareJob = null
        stopProgressLoop()
        speedBoostSavedRate = null
        scrubFocusMs = -1L
        scrubPreviewScheduler.close()
        _scrubPreviewFrames.value = emptyMap()
        _scrubPreviewFailed.value = emptySet()
        _subtitleUiState.value = SubtitleUiState()
        _siblingNavState.value = SiblingNavUiState()
        listenSession.setEnabled(false)
        listenSession.setInstallingModels(false)
        listenSession.release()
        val engine = realListenEngine
        realListenEngine = null
        // Cancellation may still be unwinding an IO subtitle/ASR operation. Wait
        // off-main before deleting this session's temporary files and closing input.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Auxiliary transports were detached above and abort independently on I/O.
            runCatching { scrubPreviewExtractor.close() }
            sessionJob.join()
            runCatching { engine?.close() }
            runCatching { subtitleLoader.clearCache() }
        }
    }

    private suspend fun loadResumeAndOpen(forceReloadHistory: Boolean = true) {
        if (forceReloadHistory) {
            val history = progressPersistence.afterSaves { historyRepository.get(request.identity) }
            preserveUnchangedHistory = history != null
            if (history != null && request.startPositionMs <= 0L) {
                startPositionMs = history.resumePositionMs
            }
        }
        currentCoroutineContext().ensureActive()
        if (playbackLifecycle.closed) return
        openSource()
    }

    private suspend fun openSource() {
        val mediaSource = try {
            resolveMediaSource(request.dataSource)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (se: SmbException) {
            val err = PlayerErrorMapper.fromSmb(se.error)
            Log.w(TAG, "SMB open failed code=${err.code} msg=${err.message}")
            purgeListenTranslateForMissingMedia()
            injectError(err)
            return
        } catch (t: Throwable) {
            val err = PlayerErrorMapper.fromThrowable(t)
            Log.w(TAG, "Open failed: ${err.message}")
            purgeListenTranslateForMissingMedia()
            injectError(err)
            return
        }
        // Pass resume into the controller once. Direct SMB retains it until play(),
        // then applies one fast/keyframe seek instead of a precise paused seek.
        val initialPositionMs = startPositionMs.coerceAtLeast(0L)
        currentCoroutineContext().ensureActive()
        if (playbackLifecycle.closed) return
        controller.prepare(mediaSource, initialPositionMs)
        resumeSeekGate.markFired()
        startPositionMs = 0L
        restartListenAfterSourceChangeIfNeeded()
    }

    private fun restartListenAfterSourceChangeIfNeeded() {
        val wasEnabled = listenSession.uiState.value.enabled
        if (!listenRestartPendingAfterSourceChange && !wasEnabled) return
        listenRestartPendingAfterSourceChange = false
        if (wasEnabled) listenSession.setEnabled(false)
        restartListenPreparation()
    }

    private fun pauseListenForSourceChange() {
        val state = listenSession.uiState.value
        if (!state.enabled && !state.isInstallingModels) return
        listenRestartPendingAfterSourceChange = true
        listenPrepareGeneration += 1L
        listenPrepareJob?.cancel()
        listenPrepareJob = null
        listenSession.setEnabled(false)
        listenSession.setInstallingModels(installing = false)
    }

    private fun injectError(error: PlayerError) {
        (controller as? VlcPlayerController)?.reportExternalError(error)
            ?: controller.prepare(MediaSource.LocalFile("/__framenest_missing__"))
    }

    /** Decision 0005: drop local listen-translate rows when the media cannot be opened. */
    private fun purgeListenTranslateForMissingMedia() {
        sessionScope.launch {
            runCatching { listenTranslateRepository.purgeMedia(request.identity) }
        }
    }

    private suspend fun resolveMediaSource(dataSource: PlaybackDataSource): MediaSource =
        when (dataSource) {
            is PlaybackDataSource.LocalRawResource -> {
                MediaSource.RawResource(dataSource.resId)
            }
            is PlaybackDataSource.LocalFile -> {
                MediaSource.LocalFile(dataSource.path)
            }
            is PlaybackDataSource.SeekableSmb -> openProductSmb(dataSource)
            is PlaybackDataSource.DirectSmbUrl -> {
                openDirectSmbUrl(dataSource)
            }
        }

    private fun openDirectSmbUrl(dataSource: PlaybackDataSource.DirectSmbUrl): MediaSource {
        val uri = SmbMediaUri.build(
            host = dataSource.host,
            share = dataSource.share,
            path = dataSource.path,
            port = dataSource.port,
        )
        val creds = if (dataSource.username.isNotEmpty()) {
            SmbCredentials(
                username = dataSource.username,
                password = dataSource.password,
                domain = dataSource.domain,
            )
        } else {
            null
        }
        return MediaSource.Smb(uri = uri, credentials = creds)
    }

    /** One direct libVLC SMB product path for every file size; credentials stay out of the URI. */
    private fun openProductSmb(dataSource: PlaybackDataSource.SeekableSmb): MediaSource {
        val uri = SmbMediaUri.build(
            host = dataSource.host,
            port = dataSource.port,
            share = dataSource.share,
            path = dataSource.path,
        )
        return MediaSource.Smb(
            uri = uri,
            credentials = SmbCredentials(
                username = dataSource.username,
                password = String(dataSource.password),
                domain = dataSource.domain.ifEmpty { null },
            ),
        )
    }

    private fun ensureProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = sessionScope.launch {
            while (isActive) {
                delay(PlaybackProgressRules.PERIODIC_SAVE_INTERVAL_MS)
                val state = controller.state.value
                if (state.phase == PlayerState.Phase.Playing) {
                    saveProgressNow(force = false)
                }
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun saveProgressNow(force: Boolean) {
        if (!playbackLifecycle.closed) scheduleProgressSave(controller.progressState, force)
    }

    private fun scheduleProgressSave(state: PlayerState, force: Boolean) {
        // Merely opening a preview must preserve the entire existing row, including
        // completion/duration, until Play or an explicit seek changes progress.
        if (preserveUnchangedHistory && !progressTouched) return
        if (state.phase == PlayerState.Phase.Idle || state.phase == PlayerState.Phase.Preparing ||
            state.phase == PlayerState.Phase.Error
        ) return
        val snapshot = state.positionMs to state.durationMs
        if (!force && !PlaybackProgressRules.shouldPersist(lastSavedPositionMs, state.positionMs)) return
        progressPersistence.save {
            if (lastSavedProgress == snapshot) return@save
            try {
                historyRepository.saveProgress(
                    identity = request.identity,
                    displayName = request.displayName,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                )
                lastSavedPositionMs = state.positionMs
                lastSavedProgress = snapshot
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "saveProgress failed: ${CredentialRedactor.redact(t.message)}")
            }
        }
    }

    private data class PlaybackDirectoryFeatures(
        val subtitleOptions: List<ExternalSubtitleOption>?,
        val siblingPlaylist: SiblingPlaylist?,
    )

    /**
     * Enumerates the SMB parent directory once, then derives sidecar subtitles and
     * sibling navigation from the same snapshot. The listing connection remains
     * isolated from playback/read handles.
     */
    private fun bootstrapDirectoryFeatures(
        state: PlayerState,
        includeSubtitles: Boolean,
        includeSiblings: Boolean,
    ) {
        retireDirectoryScan()
        val generation = directoryGeneration
        val transports = directoryTransports
        val selectionGeneration = subtitleSelectionGate.snapshot()
        directoryJob = sessionScope.launch {
            val smbParams = smbSubtitleParamsOrNull()
            if (smbParams == null) {
                if (includeSiblings) {
                    _siblingNavState.value = SiblingNavUiState.from(SiblingPlaylist.Empty)
                }
                if (includeSubtitles) {
                    autoSelectEmbeddedOnly(state, selectionGeneration)
                }
                return@launch
            }
            if (includeSubtitles) {
                _subtitleUiState.update {
                    it.copy(scanning = true, errorMessage = null, message = null)
                }
            }
            if (includeSiblings) {
                _siblingNavState.value = _siblingNavState.value.copy(loading = true)
            }

            val featuresResult = try {
                val features = withContext(Dispatchers.IO) {
                    val fileNames = listDirectoryFileNames(
                        transports = transports,
                        share = smbParams.share,
                        parentPath = SmbPathUtils.parentOf(smbParams.path),
                        host = smbParams.host,
                        port = smbParams.port,
                        username = smbParams.username,
                        password = smbParams.password,
                        domain = smbParams.domain,
                        requireEncryption = smbParams.requireEncryption,
                    )
                    PlaybackDirectoryFeatures(
                        subtitleOptions = if (includeSubtitles) {
                            sidecarScanner.optionsFromFileNames(
                                videoPath = smbParams.path,
                                directoryFileNames = fileNames,
                                preferredLanguages = preferredLanguages,
                            )
                        } else {
                            null
                        },
                        siblingPlaylist = if (includeSiblings) {
                            SiblingPlaylistFactory.build(
                                currentPath = request.identity.path,
                                directoryFileNames = fileNames,
                            )
                        } else {
                            null
                        },
                    )
                }
                Result.success(features)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                smbParams.password.fill('\u0000')
            }

            currentCoroutineContext().ensureActive()
            if (playbackLifecycle.closed || generation != directoryGeneration) return@launch
            // A manual subtitle choice retires automatic selection, not this
            // directory result: sibling navigation and scan completion still publish.
            featuresResult.fold(
                onSuccess = { features ->
                    features.siblingPlaylist?.let { playlist ->
                        _siblingNavState.value = SiblingNavUiState.from(
                            playlist = playlist,
                            loading = false,
                        )
                    }
                    val options = features.subtitleOptions ?: return@fold
                    _subtitleUiState.update {
                        it.copy(
                            scanning = false,
                            externalOptions = options,
                            errorMessage = null,
                        )
                    }
                    val best = options.firstOrNull()
                    if (!subtitleSelectionGate.isCurrent(selectionGeneration)) {
                        return@fold
                    }
                    if (best != null) {
                        loadAndSelectExternal(
                            option = best,
                            userInitiated = false,
                            selectionGeneration = selectionGeneration,
                        )
                    } else {
                        autoSelectEmbeddedOnly(controller.state.value, selectionGeneration)
                    }
                },
                onFailure = { err ->
                    val msg = CredentialRedactor.redact(
                        err.message ?: "Playback directory scan failed",
                    )
                    Log.w(TAG, "playback directory scan failed: $msg")
                    if (includeSiblings) {
                        _siblingNavState.value = SiblingNavUiState.from(SiblingPlaylist.Empty)
                    }
                    if (includeSubtitles) {
                        _subtitleUiState.update {
                            it.copy(
                                scanning = false,
                                externalOptions = emptyList(),
                                // Do not treat scan failure as fatal; still try embedded.
                                message = null,
                                errorMessage = null,
                            )
                        }
                        autoSelectEmbeddedOnly(controller.state.value, selectionGeneration)
                    }
                },
            )
        }
    }

    private suspend fun loadAndSelectExternal(
        option: ExternalSubtitleOption,
        userInitiated: Boolean,
        selectionGeneration: Long,
    ) {
        val smbParams = smbSubtitleParamsOrNull()
        if (smbParams == null) {
            if (userInitiated) {
                _subtitleUiState.update {
                    it.copy(errorMessage = "External subtitles require SMB playback")
                }
            }
            return
        }
        val passwordCopy = smbParams.password.copyOf()
        val loadResult = try {
            subtitleLoader.loadToLocalFile(
                ExternalSubtitleLoader.LoadRequest(
                    host = smbParams.host,
                    port = smbParams.port,
                    username = smbParams.username,
                    password = passwordCopy,
                    domain = smbParams.domain,
                    requireEncryption = smbParams.requireEncryption,
                    share = smbParams.share,
                    remotePath = option.remotePath,
                    fileName = option.fileName,
                ),
            )
        } finally {
            passwordCopy.fill('\u0000')
            smbParams.password.fill('\u0000')
        }

        currentCoroutineContext().ensureActive()
        if (playbackLifecycle.closed || !subtitleSelectionGate.isCurrent(selectionGeneration)) return

        loadResult.fold(
            onSuccess = { loaded ->
                val ok = runCatching {
                    controller.addExternalSubtitle(loaded.localFile.absolutePath, select = true)
                }.getOrDefault(false)
                if (ok) {
                    val updated = option.copy(localPath = loaded.localFile.absolutePath)
                    _subtitleUiState.update { state ->
                        val options = state.externalOptions.map {
                            if (it.remotePath == option.remotePath) updated else it
                        }.ifEmpty { listOf(updated) }
                        state.copy(
                            externalOptions = options,
                            selectedKey = updated.selectionKey,
                            message = loaded.encodingNote,
                            errorMessage = null,
                        )
                    }
                } else {
                    Log.w(TAG, "addExternalSubtitle failed; video continues")
                    _subtitleUiState.update {
                        it.copy(
                            errorMessage = if (userInitiated) {
                                "Could not attach external subtitle"
                            } else {
                                null
                            },
                            message = loaded.encodingNote,
                        )
                    }
                    if (!userInitiated) {
                        autoSelectEmbeddedOnly(controller.state.value, selectionGeneration)
                    }
                }
            },
            onFailure = { err ->
                val msg = CredentialRedactor.redact(err.message ?: "Subtitle load failed")
                Log.w(TAG, "external subtitle load failed: $msg")
                // Never break video playback on subtitle failure.
                _subtitleUiState.update {
                    it.copy(
                        errorMessage = if (userInitiated) msg else null,
                        message = if (!userInitiated) null else it.message,
                    )
                }
                if (!userInitiated) {
                    autoSelectEmbeddedOnly(controller.state.value, selectionGeneration)
                }
            },
        )
    }

    private fun autoSelectEmbeddedOnly(state: PlayerState, selectionGeneration: Long) {
        if (!subtitleSelectionGate.isCurrent(selectionGeneration)) return
        val tracks = SubtitleTrackLists.embedded(state.subtitleTracks)
        if (tracks.isEmpty()) {
            controller.disableSubtitles()
            _subtitleUiState.update {
                it.copy(selectedKey = SubtitleSelectionKeys.OFF, scanning = false)
            }
            return
        }
        val best = tracks.maxByOrNull { track ->
            SubtitleMatcher.embeddedTrackLanguageScore(track.name, preferredLanguages)
        }
        val score = best?.let {
            SubtitleMatcher.embeddedTrackLanguageScore(it.name, preferredLanguages)
        } ?: 0
        if (best != null && score > 0) {
            controller.selectSubtitleTrack(best.id)
            _subtitleUiState.update {
                it.copy(
                    selectedKey = SubtitleSelectionKeys.embedded(best.id),
                    scanning = false,
                )
            }
        } else {
            // No language preference hit — leave off; user can pick manually.
            controller.disableSubtitles()
            _subtitleUiState.update {
                it.copy(selectedKey = SubtitleSelectionKeys.OFF, scanning = false)
            }
        }
    }

    private data class SmbSubtitleParams(
        val host: String,
        val port: Int,
        val username: String,
        val password: CharArray,
        val domain: String,
        val requireEncryption: Boolean,
        val share: String,
        val path: String,
    )

    private fun smbSubtitleParamsOrNull(): SmbSubtitleParams? =
        when (val ds = request.dataSource) {
            is PlaybackDataSource.SeekableSmb -> SmbSubtitleParams(
                host = ds.host,
                port = ds.port,
                username = ds.username,
                password = ds.password.copyOf(),
                domain = ds.domain,
                requireEncryption = ds.requireEncryption,
                share = ds.share,
                path = ds.path,
            )
            is PlaybackDataSource.DirectSmbUrl -> SmbSubtitleParams(
                host = ds.host,
                port = ds.port ?: 445,
                username = ds.username,
                password = ds.password.toCharArray(),
                domain = ds.domain.orEmpty(),
                requireEncryption = ds.requireEncryption,
                share = ds.share,
                path = ds.path,
            )
            else -> null
        }

    override fun onCleared() {
        onLeave()
        super.onCleared()
    }

    val displayName: String get() = request.displayName
    val identity get() = request.identity

    class Factory(
        private val application: Application,
        private val request: PlaybackRequest,
        private val historyRepository: PlaybackHistoryRepository = resolveHistory(application),
        private val listenTranslateRepository: ListenTranslateRepository =
            resolveListenTranslate(application),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                return PlayerViewModel(
                    application,
                    request,
                    historyRepository,
                    listenTranslateRepository,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        }

        companion object {
            fun resolveHistory(application: Application): PlaybackHistoryRepository {
                (application as? FrameNestApplication)?.historyRepository?.let { return it }
                val db = AppDatabase.createInMemory(application)
                return PlaybackHistoryRepository(db.playbackHistoryDao())
            }
        }
    }

    companion object {
        private const val TAG = "FrameNestPlayerVM"

        private fun initialListenContentKey(
            application: Application,
            request: PlaybackRequest,
        ): String = when (val source = request.dataSource) {
            is PlaybackDataSource.LocalFile -> {
                val file = File(source.path)
                ListenContentKey.of(
                    sizeBytes = file.length().takeIf { file.isFile },
                    modifiedTimeMs = file.lastModified().takeIf { it > 0L },
                )
            }
            is PlaybackDataSource.LocalRawResource -> runCatching {
                application.resources.openRawResourceFd(source.resId).use { afd ->
                    ListenContentKey.of(
                        sizeBytes = afd.length.takeIf { it >= 0L },
                        modifiedTimeMs = null,
                    )
                }
            }.getOrDefault("")
            is PlaybackDataSource.SeekableSmb,
            is PlaybackDataSource.DirectSmbUrl,
            -> ""
        }

        private object PendingListenEngine : ListenTranslateEngine {
            override val asrModelId: String = "pending"
            override val mtModelId: String = "pending"
            override suspend fun processWindow(
                startMs: Long,
                endMs: Long,
                sourceLang: String,
                targetLang: String,
            ): ListenWindowResult = throw ModelsNotReadyException("听译引擎未就绪")
        }

        fun resolvePreferredLanguages(application: Application): List<String> {
            val userTags = (application as? FrameNestApplication)
                ?.container
                ?.userPreferences
                ?.subtitleLanguageTags()
                .orEmpty()
            return SubtitleLanguagePrefs.preferredLanguages(userPreferred = userTags)
        }

        fun resolveListenTranslate(application: Application): ListenTranslateRepository {
            (application as? FrameNestApplication)?.container?.listenTranslateRepository
                ?.let { return it }
            val db = AppDatabase.createInMemory(application)
            return ListenTranslateRepository(db.listenTranslateDao())
        }
    }
}

internal fun listenTranslatePreparationError(error: Throwable): String {
    val detail = error.message?.take(200)
    return when {
        error is NoClassDefFoundError && detail?.contains("org.vosk", ignoreCase = true) == true ->
            "本机语音识别组件加载失败，请更新应用后重试"
        // sherpa-onnx JNI (.so 缺失/ABI 不匹配) 直接以该 Error 抛出，保持未包装
        // 才能匹配到这里，而不是淹没在普通失败文案里。
        error is UnsatisfiedLinkError ->
            "本机语音识别组件加载失败，请更新应用后重试"
        error is NullPointerException && detail?.contains("null object reference", ignoreCase = true) == true ->
            "本机翻译组件初始化失败，请更新应用后重试"
        else -> detail?.takeIf { it.isNotBlank() } ?: "听译模型准备失败"
    }
}
