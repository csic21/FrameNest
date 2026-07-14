package com.framenest.feature.player

import android.app.Application
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
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.AppDatabase
import com.framenest.feature.listen_translate.ListenDisplayMode
import com.framenest.feature.listen_translate.ListenTranslateEngine
import com.framenest.feature.listen_translate.ListenTranslateSession
import com.framenest.feature.listen_translate.ListenTranslateUiState
import com.framenest.feature.listen_translate.ListenWindowResult
import com.framenest.feature.listen_translate.ModelsNotReadyException
import com.framenest.feature.listen_translate.RealListenTranslateEngine
import com.framenest.feature.listen_translate.asr.VoskAsrEngine
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
import com.framenest.feature.subtitle.SubtitleUiState
import com.framenest.player.CredentialRedactor
import com.framenest.player.MediaSource
import com.framenest.player.PlayerController
import com.framenest.player.PlayerError
import com.framenest.player.PlayerErrorMapper
import com.framenest.player.PlayerState
import com.framenest.player.SmbCredentials
import com.framenest.player.SmbMediaUri
import com.framenest.player.SmbSeekableMedia
import com.framenest.player.VideoScaleMode
import com.framenest.player.VlcPlayerController
import com.framenest.smb.SmbException
import com.framenest.smb.SmbjClient
import com.framenest.smb.SmbCredentials as SmbSessionCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Product player session: open media (path B preferred), first-frame paused,
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
    private val subtitleLoader: ExternalSubtitleLoader = ExternalSubtitleLoader(application),
) : AndroidViewModel(application) {

    val controller: PlayerController = controllerFactory(application)

    val playerState: StateFlow<PlayerState> = controller.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = controller.state.value,
    )

    private val _subtitleUiState = MutableStateFlow(SubtitleUiState())
    val subtitleUiState: StateFlow<SubtitleUiState> = _subtitleUiState.asStateFlow()

    private val voskInstaller = VoskModelInstaller(application)
    private val voskAsr = VoskAsrEngine()
    private val mlKitMt = MlKitMtEngine()
    private var realListenEngine: RealListenTranslateEngine? = null
    private var listenOnlySmbClient: SmbjClient? = null

    private val listenSession = ListenTranslateSession(
        repository = listenTranslateRepository,
        engine = PendingListenEngine,
        scope = viewModelScope,
        identity = request.identity,
        contentKey = "",
    )
    val listenTranslateUiState: StateFlow<ListenTranslateUiState> = listenSession.uiState

    private val audioFocus = PlayerAudioFocus(application) {
        pauseFromSystem()
    }

    private var smbClient: SmbjClient? = null
    private var progressJob: Job? = null
    private var lastSavedPositionMs: Long? = null
    private var startPositionMs: Long = request.startPositionMs
    private var subtitleBootstrapDone: Boolean = false
    private var preferredLanguages: List<String> = resolvePreferredLanguages(application)
    /**
     * After path B (Proxy FD) fails before first frame, retry once with libVLC
     * direct `smb://` (path A). Avoids permanent OpenFailed on demux/imem issues.
     */
    private var smbDirectFallbackUsed: Boolean = false
    /** True when the last successful open used path B seekable descriptor. */
    private var lastOpenUsedPathB: Boolean = false

    init {
        viewModelScope.launch {
            loadResumeAndOpen()
        }
        viewModelScope.launch {
            controller.state.collect { state ->
                listenSession.onPlaybackTick(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    playing = state.phase == PlayerState.Phase.Playing,
                )
                if (state.phase == PlayerState.Phase.Playing) {
                    ensureProgressLoop()
                } else if (state.phase == PlayerState.Phase.Ended) {
                    saveProgressNow(force = true)
                    stopProgressLoop()
                }
                // After first frame ready, seek to resume position once.
                if (state.firstFrameReady &&
                    startPositionMs > 0L &&
                    (state.phase == PlayerState.Phase.Ready ||
                        state.phase == PlayerState.Phase.Paused)
                ) {
                    val target = startPositionMs
                    startPositionMs = 0L
                    val duration = state.durationMs
                    // Re-apply completion rule with the real media length so a bad
                    // history row (duration 0 / wrong length) does not seek to EOF.
                    val safeTarget = if (duration > 0L &&
                        PlaybackProgressRules.isCompleted(target, duration)
                    ) {
                        0L
                    } else if (duration > 0L) {
                        target.coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
                    } else {
                        target
                    }
                    if (safeTarget > 0L) {
                        controller.seekTo(safeTarget)
                    }
                }
                if (state.firstFrameReady && !subtitleBootstrapDone) {
                    subtitleBootstrapDone = true
                    bootstrapSubtitles(state)
                }
                maybeFallbackSeekableSmbToDirect(state)
            }
        }
    }

    fun setListenTranslateEnabled(enabled: Boolean) {
        if (!enabled) {
            listenSession.setEnabled(false)
            return
        }
        viewModelScope.launch {
            enableRealListenTranslate()
        }
    }

    fun setListenSourceLang(code: String) {
        listenSession.setSourceLang(code)
        // Re-download ASR model if already enabled.
        if (listenSession.uiState.value.enabled) {
            viewModelScope.launch { enableRealListenTranslate() }
        }
    }

    fun setListenTargetLang(code: String) {
        listenSession.setTargetLang(code)
        if (listenSession.uiState.value.enabled) {
            viewModelScope.launch { enableRealListenTranslate() }
        }
    }

    fun setListenDisplayMode(mode: ListenDisplayMode) {
        listenSession.setDisplayMode(mode)
    }

    private suspend fun enableRealListenTranslate() {
        val sourceLang = listenSession.uiState.value.sourceLang
        val targetLang = listenSession.uiState.value.targetLang
        val asrReady = voskInstaller.isInstalled(sourceLang)
        listenSession.setInstallingModels(
            installing = true,
            message = if (asrReady) {
                "本机 ASR 已就绪（$sourceLang），正在启动听译…"
            } else {
                "正在下载 Vosk($sourceLang)…\n" +
                    "也可先到「设置 → 听译模型」安装并查看状态"
            },
            error = null,
        )
        val prepared = runCatching {
            withContext(Dispatchers.IO) {
                ensureSmbConnectedForListen()
                val audio = buildListenAudioSource()
                    ?: error("当前片源暂不支持听译音频（需要本地文件或 SMB 随机读）")
                voskInstaller.ensureInstalled(sourceLang) { p ->
                    // progress callback on IO; UI message is approximate
                    if (p >= 0.99f || p < 0.05f) return@ensureInstalled
                }
                mlKitMt.ensureModel(sourceLang, targetLang)
                val engine = RealListenTranslateEngine(
                    audio = audio,
                    vosk = voskAsr,
                    mt = mlKitMt,
                    voskModels = voskInstaller,
                    asrModelLabel = { "vosk-small-$sourceLang" },
                    mtModelLabel = { "mlkit-$sourceLang-$targetLang" },
                )
                realListenEngine?.close()
                realListenEngine = engine
                listenSession.setEngine(engine)
            }
        }
        if (prepared.isFailure) {
            val msg = prepared.exceptionOrNull()?.message?.take(200)
                ?: "听译模型准备失败"
            listenSession.setInstallingModels(installing = false, error = msg)
            listenSession.setModelsReady(false)
            listenSession.setEnabled(false)
            return
        }
        listenSession.setModelsReady(true)
        listenSession.setInstallingModels(
            installing = false,
            message = "本机听译已就绪：Vosk small($sourceLang) + ML Kit Translate",
            error = null,
        )
        listenSession.setEnabled(true)
    }

    private fun buildListenAudioSource(): ListenAudioSource? {
        val app = getApplication<Application>()
        return when (val ds = request.dataSource) {
            is PlaybackDataSource.LocalFile -> ListenAudioSources.forLocalFile(ds.path)
            is PlaybackDataSource.LocalRawResource ->
                ListenAudioSources.forRaw(app, ds.resId)
            is PlaybackDataSource.SeekableSmb -> {
                val client = smbClient ?: return null
                ListenAudioSources.forSmb(app, client, ds.share, ds.path)
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                val client = listenOnlySmbClient ?: return null
                ListenAudioSources.forSmb(app, client, ds.share, ds.path)
            }
        }
    }

    /**
     * Ensure an SMB session exists for second-path audio decode while VLC plays.
     */
    private suspend fun ensureSmbConnectedForListen() = withContext(Dispatchers.IO) {
        when (val ds = request.dataSource) {
            is PlaybackDataSource.SeekableSmb -> {
                if (smbClient != null) return@withContext
                val client = SmbjClient()
                val creds = SmbSessionCredentials(
                    host = ds.host,
                    port = ds.port,
                    username = ds.username,
                    password = ds.password.copyOf(),
                    domain = ds.domain,
                )
                try {
                    client.connect(creds)
                    smbClient = client
                } finally {
                    creds.clearPassword()
                }
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                if (listenOnlySmbClient != null) return@withContext
                val client = SmbjClient()
                val creds = SmbSessionCredentials(
                    host = ds.host,
                    port = ds.port ?: 445,
                    username = ds.username,
                    password = ds.password.toCharArray(),
                    domain = ds.domain.orEmpty(),
                )
                try {
                    client.connect(creds)
                    listenOnlySmbClient = client
                } finally {
                    creds.clearPassword()
                }
            }
            else -> Unit
        }
    }

    fun play() {
        val phase = controller.state.value.phase
        if (phase == PlayerState.Phase.Error) {
            retry()
            return
        }
        if (!audioFocus.request()) {
            Log.w(TAG, "Audio focus not granted; playing anyway")
        }
        controller.play()
    }

    fun pause() {
        if (controller.state.value.phase != PlayerState.Phase.Playing) return
        controller.pause()
        viewModelScope.launch { saveProgressNow(force = true) }
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    /** Cycle BestFit → FitScreen → Fill → 16:9 → 4:3 → Original. */
    fun cycleVideoScaleMode() {
        val next = controller.state.value.videoScaleMode.next()
        controller.setVideoScaleMode(next)
    }

    fun setVideoScaleMode(mode: VideoScaleMode) {
        controller.setVideoScaleMode(mode)
    }

    fun selectSubtitleOff() {
        controller.disableSubtitles()
        _subtitleUiState.update {
            it.copy(selectedKey = SubtitleSelectionKeys.OFF, errorMessage = null)
        }
    }

    fun selectEmbeddedSubtitle(trackId: Int) {
        controller.selectSubtitleTrack(trackId)
        _subtitleUiState.update {
            it.copy(
                selectedKey = SubtitleSelectionKeys.embedded(trackId),
                errorMessage = null,
            )
        }
    }

    fun selectExternalSubtitle(option: ExternalSubtitleOption) {
        viewModelScope.launch {
            loadAndSelectExternal(option, userInitiated = true)
        }
    }

    fun adjustSubtitleDelayMs(deltaMs: Long) {
        val next = (_subtitleUiState.value.delayMs + deltaMs).coerceIn(-10_000L, 10_000L)
        controller.setSubtitleDelayMs(next)
        _subtitleUiState.update { it.copy(delayMs = next) }
    }

    fun setSubtitleFontRelSize(relSize: Int) {
        val size = if (relSize in SubtitleFontSizes.ALL) {
            relSize
        } else {
            SubtitleFontSizes.NORMAL
        }
        controller.setSubtitleFontRelSize(size)
        _subtitleUiState.update { it.copy(fontRelSize = size) }
    }

    fun retry() {
        viewModelScope.launch {
            // Close proxy FD / media first so SmbRandomAccess can release cleanly,
            // then disconnect SMB off the main thread.
            (controller as? VlcPlayerController)?.closeCurrentMedia()
            withContext(Dispatchers.IO) {
                teardownMediaResources()
            }
            // Manual retry may try path B again, then auto-fallback can re-arm once.
            smbDirectFallbackUsed = false
            lastOpenUsedPathB = false
            subtitleBootstrapDone = false
            _subtitleUiState.value = SubtitleUiState(
                delayMs = _subtitleUiState.value.delayMs,
                fontRelSize = _subtitleUiState.value.fontRelSize,
            )
            loadResumeAndOpen(forceReloadHistory = false)
        }
    }

    /**
     * Call when UI leaves or process goes to background pause policy.
     *
     * Progress is written on an application-style IO scope (not [viewModelScope]) so the
     * write can finish after [onCleared] cancels the ViewModel scope during popBackStack.
     */
    fun onLeaveOrBackground() {
        pauseFromSystem()
        val state = controller.state.value
        if (state.phase == PlayerState.Phase.Idle ||
            state.phase == PlayerState.Phase.Preparing ||
            state.phase == PlayerState.Phase.Error
        ) {
            return
        }
        val position = state.positionMs
        val duration = state.durationMs
        val identity = request.identity
        val name = request.displayName
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                historyRepository.saveProgress(
                    identity = identity,
                    displayName = name,
                    positionMs = position,
                    durationMs = duration,
                )
                lastSavedPositionMs = position
            } catch (t: Throwable) {
                Log.w(TAG, "leave saveProgress failed: ${CredentialRedactor.redact(t.message)}")
            }
        }
    }

    private fun pauseFromSystem() {
        val phase = controller.state.value.phase
        if (phase == PlayerState.Phase.Playing) {
            controller.pause()
        }
    }

    private suspend fun loadResumeAndOpen(forceReloadHistory: Boolean = true) {
        if (forceReloadHistory) {
            val history = historyRepository.get(request.identity)
            if (history != null && request.startPositionMs <= 0L) {
                startPositionMs = history.resumePositionMs
            }
        }
        openSource()
    }

    private suspend fun openSource() {
        val mediaSource = try {
            resolveMediaSource(request.dataSource, forceDirectSmb = false)
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
        controller.prepare(mediaSource)
    }

    private fun injectError(error: PlayerError) {
        (controller as? VlcPlayerController)?.reportExternalError(error)
            ?: controller.prepare(MediaSource.LocalFile("/__framenest_missing__"))
    }

    /** Decision 0005: drop local listen-translate rows when the media cannot be opened. */
    private fun purgeListenTranslateForMissingMedia() {
        viewModelScope.launch {
            runCatching { listenTranslateRepository.purgeMedia(request.identity) }
        }
    }

    /**
     * Path B (SMBJ + ProxyFileDescriptor) sometimes ends demux before any Vout
     * ("Playback ended before a video frame was ready") or hits imem read errors.
     * Fall back once to path A (libVLC direct smb:// + option credentials).
     */
    private fun maybeFallbackSeekableSmbToDirect(state: PlayerState) {
        if (smbDirectFallbackUsed || !lastOpenUsedPathB) return
        if (state.phase != PlayerState.Phase.Error) return
        if (state.firstFrameReady) return
        val err = state.error ?: return
        if (err.code != PlayerError.Code.OpenFailed &&
            err.code != PlayerError.Code.PlaybackError
        ) {
            return
        }
        val dataSource = request.dataSource as? PlaybackDataSource.SeekableSmb ?: return
        smbDirectFallbackUsed = true
        lastOpenUsedPathB = false
        viewModelScope.launch {
            Log.w(
                TAG,
                "path B failed before first frame (${err.code}); " +
                    "falling back to libVLC direct smb://",
            )
            (controller as? VlcPlayerController)?.closeCurrentMedia()
            withContext(Dispatchers.IO) {
                teardownMediaResources()
            }
            val direct = try {
                withContext(Dispatchers.IO) {
                    openDirectSmb(dataSource)
                }
            } catch (se: SmbException) {
                injectError(PlayerErrorMapper.fromSmb(se.error))
                return@launch
            } catch (t: Throwable) {
                injectError(PlayerErrorMapper.fromThrowable(t))
                return@launch
            }
            controller.prepare(direct)
        }
    }

    private suspend fun resolveMediaSource(
        dataSource: PlaybackDataSource,
        forceDirectSmb: Boolean,
    ): MediaSource =
        when (dataSource) {
            is PlaybackDataSource.LocalRawResource -> {
                lastOpenUsedPathB = false
                MediaSource.RawResource(dataSource.resId)
            }
            is PlaybackDataSource.LocalFile -> {
                lastOpenUsedPathB = false
                MediaSource.LocalFile(dataSource.path)
            }
            is PlaybackDataSource.SeekableSmb -> withContext(Dispatchers.IO) {
                if (forceDirectSmb) {
                    openDirectSmb(dataSource)
                } else {
                    openSeekableSmb(dataSource)
                }
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                lastOpenUsedPathB = false
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

    private fun openDirectSmb(dataSource: PlaybackDataSource.SeekableSmb): MediaSource {
        lastOpenUsedPathB = false
        teardownSmbClientOnly()
        val uri = SmbMediaUri.build(
            host = dataSource.host,
            share = dataSource.share,
            path = dataSource.path,
            port = dataSource.port,
        )
        val passwordString = String(dataSource.password)
        return MediaSource.Smb(
            uri = uri,
            credentials = SmbCredentials(
                username = dataSource.username,
                password = passwordString,
                domain = dataSource.domain.ifEmpty { null },
            ),
        )
    }

    private fun openSeekableSmb(dataSource: PlaybackDataSource.SeekableSmb): MediaSource {
        teardownSmbClientOnly()
        val client = SmbjClient()
        smbClient = client
        val sessionCreds = SmbSessionCredentials(
            host = dataSource.host,
            port = dataSource.port,
            username = dataSource.username,
            password = dataSource.password.copyOf(),
            domain = dataSource.domain,
        )
        try {
            client.connect(sessionCreds)
            val randomAccess = client.openRandomAccess(dataSource.share, dataSource.path)
            val size = randomAccess.size
            // Multi-GiB files frequently break ProxyFileDescriptor + libVLC imem
            // ("stream: read error" / EncounteredError). Prefer path A (direct smb://
            // + option credentials) for those sizes; keep path B for normal files.
            if (size >= LARGE_SMB_FILE_BYTES) {
                Log.i(
                    TAG,
                    "SMB file size=$size ≥ ${LARGE_SMB_FILE_BYTES}; " +
                        "using libVLC direct smb:// for reliability",
                )
                runCatching { randomAccess.close() }
                teardownSmbClientOnly()
                return openDirectSmb(dataSource)
            }
            val opened = SmbSeekableMedia.open(
                context = getApplication(),
                randomAccess = randomAccess,
                debugLabel = "smb://${dataSource.share}/${dataSource.path.trimStart('/')}",
            )
            lastOpenUsedPathB = true
            return opened.mediaSource
        } catch (t: Throwable) {
            teardownSmbClientOnly()
            lastOpenUsedPathB = false
            throw t
        } finally {
            sessionCreds.clearPassword()
        }
    }

    private fun ensureProgressLoop() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
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

    private suspend fun saveProgressNow(force: Boolean) {
        val state = controller.state.value
        if (state.phase == PlayerState.Phase.Idle ||
            state.phase == PlayerState.Phase.Preparing ||
            state.phase == PlayerState.Phase.Error
        ) {
            return
        }
        val position = state.positionMs
        val duration = state.durationMs
        if (!force &&
            !PlaybackProgressRules.shouldPersist(lastSavedPositionMs, position)
        ) {
            return
        }
        try {
            historyRepository.saveProgress(
                identity = request.identity,
                displayName = request.displayName,
                positionMs = position,
                durationMs = duration,
            )
            lastSavedPositionMs = position
        } catch (t: Throwable) {
            Log.w(TAG, "saveProgress failed: ${CredentialRedactor.redact(t.message)}")
        }
    }

    private fun bootstrapSubtitles(state: PlayerState) {
        viewModelScope.launch {
            val smbParams = smbSubtitleParamsOrNull()
            if (smbParams == null) {
                autoSelectEmbeddedOnly(state)
                return@launch
            }
            _subtitleUiState.update {
                it.copy(scanning = true, errorMessage = null, message = null)
            }
            val scanResult = try {
                sidecarScanner.scan(
                    request = SidecarSubtitleScanner.ScanRequest(
                        host = smbParams.host,
                        port = smbParams.port,
                        username = smbParams.username,
                        password = smbParams.password.copyOf(),
                        domain = smbParams.domain,
                        share = smbParams.share,
                        videoPath = smbParams.path,
                    ),
                    preferredLanguages = preferredLanguages,
                )
            } finally {
                smbParams.password.fill('\u0000')
            }

            scanResult.fold(
                onSuccess = { options ->
                    _subtitleUiState.update {
                        it.copy(
                            scanning = false,
                            externalOptions = options,
                            errorMessage = null,
                        )
                    }
                    val best = options.firstOrNull()
                    if (best != null) {
                        loadAndSelectExternal(best, userInitiated = false)
                    } else {
                        autoSelectEmbeddedOnly(controller.state.value)
                    }
                },
                onFailure = { err ->
                    val msg = CredentialRedactor.redact(
                        err.message ?: "Subtitle directory scan failed",
                    )
                    Log.w(TAG, "sidecar scan failed: $msg")
                    _subtitleUiState.update {
                        it.copy(
                            scanning = false,
                            externalOptions = emptyList(),
                            // Do not treat scan failure as fatal; still try embedded.
                            message = null,
                            errorMessage = null,
                        )
                    }
                    autoSelectEmbeddedOnly(controller.state.value)
                },
            )
        }
    }

    private suspend fun loadAndSelectExternal(
        option: ExternalSubtitleOption,
        userInitiated: Boolean,
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
                    share = smbParams.share,
                    remotePath = option.remotePath,
                    fileName = option.fileName,
                ),
            )
        } finally {
            passwordCopy.fill('\u0000')
            smbParams.password.fill('\u0000')
        }

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
                        autoSelectEmbeddedOnly(controller.state.value)
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
                    autoSelectEmbeddedOnly(controller.state.value)
                }
            },
        )
    }

    private fun autoSelectEmbeddedOnly(state: PlayerState) {
        val tracks = state.subtitleTracks.filter { it.id >= 0 }
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
                share = ds.share,
                path = ds.path,
            )
            is PlaybackDataSource.DirectSmbUrl -> SmbSubtitleParams(
                host = ds.host,
                port = ds.port ?: 445,
                username = ds.username,
                password = ds.password.toCharArray(),
                domain = ds.domain.orEmpty(),
                share = ds.share,
                path = ds.path,
            )
            else -> null
        }

    private fun teardownMediaResources() {
        stopProgressLoop()
        teardownSmbClientOnly()
    }

    private fun teardownSmbClientOnly() {
        val client = smbClient
        smbClient = null
        if (client != null) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
        val listenClient = listenOnlySmbClient
        listenOnlySmbClient = null
        if (listenClient != null) {
            runCatching { listenClient.disconnect() }
            runCatching { listenClient.close() }
        }
    }

    override fun onCleared() {
        // Snapshot everything needed for background teardown. Do not block the main
        // thread here — popBackStack animation runs concurrently with onCleared.
        val stateSnapshot = controller.state.value
        val shouldSave =
            stateSnapshot.phase != PlayerState.Phase.Idle &&
                stateSnapshot.phase != PlayerState.Phase.Error &&
                stateSnapshot.phase != PlayerState.Phase.Preparing
        val savePosition = stateSnapshot.positionMs
        val saveDuration = stateSnapshot.durationMs
        val saveIdentity = request.identity
        val saveDisplayName = request.displayName
        val history = historyRepository

        listenSession.release()
        val listenEngine = realListenEngine
        realListenEngine = null
        stopProgressLoop()
        audioFocus.abandon()

        val player = controller
        val asr = voskAsr
        val mt = mlKitMt
        val clients = listOfNotNull(smbClient, listenOnlySmbClient)
        smbClient = null
        listenOnlySmbClient = null
        val subtitles = subtitleLoader

        // Leave already started an async save; this thread is a bounded backup plus
        // VLC/ASR/SMB teardown that used to freeze the exit transition on the main thread.
        Thread(
            {
                if (shouldSave) {
                    runCatching {
                        runBlocking {
                            withTimeoutOrNull(1_500L) {
                                history.saveProgress(
                                    identity = saveIdentity,
                                    displayName = saveDisplayName,
                                    positionMs = savePosition,
                                    durationMs = saveDuration,
                                )
                            }
                        }
                    }.onFailure { t ->
                        Log.w(
                            TAG,
                            "teardown saveProgress: ${CredentialRedactor.redact(t.message)}",
                        )
                    }
                }
                // Order: stop player / close proxy AFD, then SMB sessions.
                runCatching { player.release() }
                runCatching { listenEngine?.close() }
                runCatching { asr.close() }
                runCatching { mt.close() }
                clients.forEach { client ->
                    runCatching { client.disconnect() }
                    runCatching { client.close() }
                }
                runCatching { subtitles.clearCache() }
            },
            "framenest-player-teardown",
        ).apply {
            isDaemon = true
            start()
        }
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

        /**
         * At/above this size, prefer libVLC direct `smb://` over ProxyFileDescriptor
         * (path B). Multi-GiB MP4s hit imem read errors with fixed AFD lengths.
         */
        private const val LARGE_SMB_FILE_BYTES: Long = 1L shl 32 // 4 GiB

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
