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
import com.framenest.data.listen_translate.ListenContentKey
import com.framenest.data.listen_translate.ListenTranslateRepository
import com.framenest.data.server.AppDatabase
import com.framenest.data.settings.UserPreferences
import com.framenest.feature.listen_translate.ListenDisplayMode
import com.framenest.feature.listen_translate.ListenCacheVariant
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
import com.framenest.player.PlaybackRates
import com.framenest.player.PlayerController
import com.framenest.player.PlayerError
import com.framenest.player.PlayerErrorMapper
import com.framenest.player.PlayerState
import com.framenest.player.SmbCredentials
import com.framenest.player.SmbMediaUri
import com.framenest.player.VideoScaleMode
import com.framenest.player.VlcPlayerController
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val subtitleSelectionGate = SubtitleSelectionGate()

    private val _siblingNavState = MutableStateFlow(SiblingNavUiState())
    val siblingNavState: StateFlow<SiblingNavUiState> = _siblingNavState.asStateFlow()

    private val voskInstaller = VoskModelInstaller(application)
    private val userPreferences = UserPreferences(application)
    private var realListenEngine: RealListenTranslateEngine? = null
    private var listenOnlySmbClient: SmbjClient? = null
    private var listenPrepareJob: Job? = null
    private var listenPrepareGeneration: Long = 0L
    private val listenPrepareMutex = Mutex()
    private var listenRestartPendingAfterSourceChange = false
    private val listenBaseContentKey = initialListenContentKey(application, request)

    private val listenSession = ListenTranslateSession(
        repository = listenTranslateRepository,
        engine = PendingListenEngine,
        scope = viewModelScope,
        identity = request.identity,
        contentKey = ListenCacheVariant.contentKey(listenBaseContentKey, null),
    )
    val listenTranslateUiState: StateFlow<ListenTranslateUiState> = listenSession.uiState

    private var playPendingAudioFocus: Boolean = false
    private val audioFocus = PlayerAudioFocus(
        context = application,
        onFocusLost = {
            playPendingAudioFocus = false
            pauseFromSystem()
        },
        onFocusGained = {
            if (playPendingAudioFocus) {
                playPendingAudioFocus = false
                controller.play()
            }
        },
    )

    private var progressJob: Job? = null
    /** Outstanding open lifecycle; retry replaces any in-flight open. */
    private var openJob: Job? = null
    /** Volatile: read on a background teardown thread to skip a duplicate save. */
    @Volatile
    private var lastSavedPositionMs: Long? = null
    /** Background progress save kicked by [onLeaveOrBackground]; cancelled in [onCleared]. */
    private var leaveSaveJob: Job? = null
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
        openJob = viewModelScope.launch {
            loadResumeAndOpen()
        }
        viewModelScope.launch {
            controller.state.collect { state ->
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
                )
                if (state.phase == PlayerState.Phase.Playing) {
                    ensureProgressLoop()
                } else if (state.phase == PlayerState.Phase.Ended) {
                    saveProgressNow(force = true)
                    stopProgressLoop()
                }
                if (state.firstFrameReady && !subtitleBootstrapDone) {
                    subtitleBootstrapDone = true
                    bootstrapSubtitles(state)
                }
                if (state.firstFrameReady && !siblingBootstrapDone) {
                    siblingBootstrapDone = true
                    loadSiblingPlaylist()
                }
            }
        }
    }

    fun setListenTranslateEnabled(enabled: Boolean) {
        if (!enabled) {
            listenRestartPendingAfterSourceChange = false
            listenPrepareGeneration += 1L
            listenPrepareJob?.cancel()
            listenPrepareJob = null
            listenSession.setEnabled(false)
            listenSession.setInstallingModels(installing = false)
            return
        }
        listenRestartPendingAfterSourceChange = false
        restartListenPreparation()
    }

    fun setListenSourceLang(code: String) {
        val restart = listenSession.uiState.value.let { it.enabled || it.isInstallingModels }
        if (restart) listenSession.setEnabled(false)
        listenSession.setSourceLang(code)
        if (restart) restartListenPreparation()
    }

    fun setListenTargetLang(code: String) {
        val restart = listenSession.uiState.value.let { it.enabled || it.isInstallingModels }
        if (restart) listenSession.setEnabled(false)
        listenSession.setTargetLang(code)
        if (restart) restartListenPreparation()
    }

    fun setListenDisplayMode(mode: ListenDisplayMode) {
        listenSession.setDisplayMode(mode)
    }

    private fun restartListenPreparation() {
        if (listenSession.uiState.value.enabled) {
            listenSession.setEnabled(false)
        }
        val sourceLang = listenSession.uiState.value.sourceLang
        val targetLang = listenSession.uiState.value.targetLang
        val generation = ++listenPrepareGeneration
        listenPrepareJob?.cancel()
        listenSession.setInstallingModels(
            installing = true,
            message = "正在切换到 $sourceLang→$targetLang…",
            error = null,
        )
        listenPrepareJob = viewModelScope.launch {
            enableRealListenTranslate(generation, sourceLang, targetLang)
        }
    }

    private suspend fun enableRealListenTranslate(
        generation: Long,
        sourceLang: String,
        targetLang: String,
    ) = listenPrepareMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (generation != listenPrepareGeneration) return@withLock
        val previous = realListenEngine
        realListenEngine = null
        withContext(Dispatchers.IO) {
            runCatching { previous?.close() }
        }
        val asrReady = voskInstaller.isInstalled(sourceLang)
        listenSession.setModelsReady(false)
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
        var pendingAudio: ListenAudioSource? = null
        var pendingAsr: VoskAsrEngine? = null
        var pendingMt: MlKitMtEngine? = null
        val prepared = try {
            withContext(Dispatchers.IO) {
                ensureSmbConnectedForListen()
                val audio = buildListenAudioSource()
                    ?: error("当前片源暂不支持听译音频（需要本地文件或 SMB 随机读）")
                pendingAudio = audio
                var lastProgressPercent = -1
                val allowMeteredDownloads = userPreferences.allowMeteredModelDownloads()
                voskInstaller.ensureInstalled(
                    langTag = sourceLang,
                    allowMeteredDownloads = allowMeteredDownloads,
                ) { p ->
                    if (generation != listenPrepareGeneration) return@ensureInstalled
                    val percent = (p * 100f).toInt().coerceIn(0, 100)
                    if (percent != lastProgressPercent) {
                        lastProgressPercent = percent
                        listenSession.setInstallingModels(
                            installing = true,
                            message = "正在准备 Vosk($sourceLang)：$percent%",
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
                val asr = VoskAsrEngine()
                val mt = MlKitMtEngine(allowMeteredDownloads = allowMeteredDownloads)
                pendingAsr = asr
                pendingMt = mt
                listenSession.setInstallingModels(
                    installing = true,
                    message = "正在载入本机 ASR 模型 $sourceLang…",
                )
                val modelDir = voskInstaller.modelDir(sourceLang)
                    ?: error("Vosk($sourceLang) 模型安装不完整")
                asr.ensureModel(modelDir, sourceLang)
                listenSession.setInstallingModels(
                    installing = true,
                    message = "正在准备本机翻译模型 $sourceLang→$targetLang…",
                )
                mt.ensureModel(sourceLang, targetLang)
                currentCoroutineContext().ensureActive()
                val engine = RealListenTranslateEngine(
                    audio = audio,
                    vosk = asr,
                    mt = mt,
                    voskModels = voskInstaller,
                    selectedAudioTrackOrdinal = { selectedAudioTrackOrdinal() },
                    asrModelLabel = {
                        "vosk-${VoskModelInstaller.modelVersionTag(sourceLang) ?: sourceLang}"
                    },
                    mtModelLabel = { "mlkit-v1-$sourceLang-$targetLang" },
                )
                if (generation != listenPrepareGeneration) {
                    engine.close()
                    throw CancellationException("stale listen-translate preparation")
                }
                realListenEngine = engine
                listenSession.setEngine(engine)
                pendingAudio = null
                pendingAsr = null
                pendingMt = null
            }
            true
        } catch (cancelled: CancellationException) {
            runCatching { pendingAudio?.close() }
            runCatching { pendingAsr?.close() }
            runCatching { pendingMt?.close() }
            throw cancelled
        } catch (t: Throwable) {
            runCatching { pendingAudio?.close() }
            runCatching { pendingAsr?.close() }
            runCatching { pendingMt?.close() }
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
                if (listenOnlySmbClient == null) return null
                ListenAudioSources.forSmb(app, { listenOnlySmbClient }, ds.share, ds.path)
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                if (listenOnlySmbClient == null) return null
                ListenAudioSources.forSmb(app, { listenOnlySmbClient }, ds.share, ds.path)
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
    private suspend fun ensureSmbConnectedForListen() = withContext(Dispatchers.IO) {
        when (val ds = request.dataSource) {
            is PlaybackDataSource.SeekableSmb -> {
                // Never reuse the playback client: SMBJ can return the same cached
                // DiskShare, and closing an auxiliary handle would break VLC reads.
                runCatching { listenOnlySmbClient?.close() }
                listenOnlySmbClient = null
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
                    listenOnlySmbClient = client
                } finally {
                    creds.clearPassword()
                }
            }
            is PlaybackDataSource.DirectSmbUrl -> {
                runCatching { listenOnlySmbClient?.close() }
                listenOnlySmbClient = null
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
        // User took over playback: cancel any pending resume-seek so a later Paused
        // does not drag the viewer back to the resume point.
        resumeSeekGate.markFired()
        startPositionMs = 0L
        when (audioFocus.request()) {
            AudioFocusRequestResult.Granted -> {
                playPendingAudioFocus = false
                controller.play()
            }
            AudioFocusRequestResult.Delayed -> {
                playPendingAudioFocus = true
                Log.i(TAG, "Audio focus delayed; waiting before playback")
            }
            AudioFocusRequestResult.Failed -> {
                playPendingAudioFocus = false
                Log.w(TAG, "Audio focus denied; playback not started")
            }
        }
    }

    fun pause() {
        if (controller.state.value.phase != PlayerState.Phase.Playing) return
        controller.pause()
        viewModelScope.launch { saveProgressNow(force = true) }
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    /**
     * Relative skip (e.g. ±10s from double-tap). Clamped to media bounds.
     * Uses the same single-seek policy as the slider.
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
        // User navigated explicitly — do not let a pending resume seek pull them back.
        resumeSeekGate.markFired()
        startPositionMs = 0L
        controller.seekTo(target)
    }

    fun selectAudioTrack(trackId: Int) {
        val state = controller.state.value
        val ordinal = state.audioTracks
            .filter { it.id >= 0 }
            .indexOfFirst { it.id == trackId }
            .takeIf { it >= 0 }
        listenSession.setContentKey(ListenCacheVariant.contentKey(listenBaseContentKey, ordinal))
        controller.selectAudioTrack(trackId)
    }

    fun setPlaybackRate(rate: Float) {
        controller.setPlaybackRate(rate)
    }

    fun cyclePlaybackRate() {
        val next = PlaybackRates.next(controller.state.value.playbackRate)
        controller.setPlaybackRate(next)
    }

    /**
     * Load same-directory video siblings for prev/next. Safe to call multiple times;
     * failures leave [siblingNavState] empty (no prev/next UI).
     */
    fun loadSiblingPlaylist() {
        viewModelScope.launch {
            _siblingNavState.value = _siblingNavState.value.copy(loading = true)
            val playlist = runCatching { listSiblingPlaylist() }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "sibling list failed: ${CredentialRedactor.redact(t.message)}",
                    )
                }
                .getOrDefault(SiblingPlaylist.Empty)
            _siblingNavState.value = SiblingNavUiState.from(playlist, loading = false)
        }
    }

    private suspend fun listSiblingPlaylist(): SiblingPlaylist = withContext(Dispatchers.IO) {
        val path = request.identity.path
        val names = listSiblingFileNames()
        SiblingPlaylistFactory.build(currentPath = path, directoryFileNames = names)
    }

    /** Use a short-lived listing session so playback/read handles remain isolated. */
    private fun listSiblingFileNames(): List<String> {
        return when (val ds = request.dataSource) {
            is PlaybackDataSource.SeekableSmb ->
                listDirectoryFileNames(
                    share = ds.share,
                    parentPath = SmbPathUtils.parentOf(ds.path),
                    host = ds.host,
                    port = ds.port,
                    username = ds.username,
                    password = ds.password,
                    domain = ds.domain,
                )
            is PlaybackDataSource.DirectSmbUrl -> {
                val chars = ds.password.toCharArray()
                try {
                    listDirectoryFileNames(
                        share = ds.share,
                        parentPath = SmbPathUtils.parentOf(ds.path),
                        host = ds.host,
                        port = ds.port ?: 445,
                        username = ds.username,
                        password = chars,
                        domain = ds.domain.orEmpty(),
                    )
                } finally {
                    chars.fill('\u0000')
                }
            }
            is PlaybackDataSource.LocalFile,
            is PlaybackDataSource.LocalRawResource,
            -> emptyList()
        }
    }

    private fun listDirectoryFileNames(
        share: String,
        parentPath: String,
        host: String,
        port: Int,
        username: String,
        password: CharArray,
        domain: String,
    ): List<String> {
        // Directory enumeration gets its own connection. SMBJ caches DiskShare by
        // name, so using the playback/listen client could close an active read handle.
        val client = SmbjClient()
        val creds = SmbSessionCredentials(
            host = host,
            port = port,
            username = username,
            password = password.copyOf(),
            domain = domain,
        )
        return try {
            client.connect(creds)
            client.listDirectory(share, parentPath)
                .filter { !it.isDirectory }
                .map { it.name }
        } finally {
            runCatching { client.close() }
            creds.clearPassword()
        }
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
        subtitleSelectionGate.advance()
        controller.disableSubtitles()
        _subtitleUiState.update {
            it.copy(selectedKey = SubtitleSelectionKeys.OFF, errorMessage = null)
        }
    }

    fun selectEmbeddedSubtitle(trackId: Int) {
        subtitleSelectionGate.advance()
        controller.selectSubtitleTrack(trackId)
        _subtitleUiState.update {
            it.copy(
                selectedKey = SubtitleSelectionKeys.embedded(trackId),
                errorMessage = null,
            )
        }
    }

    fun selectExternalSubtitle(option: ExternalSubtitleOption) {
        val selectionGeneration = subtitleSelectionGate.advance()
        viewModelScope.launch {
            loadAndSelectExternal(
                option = option,
                userInitiated = true,
                selectionGeneration = selectionGeneration,
            )
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
        pauseListenForSourceChange()
        // Replace any in-flight open so rapid retries do not overlap.
        openJob?.cancel()
        openJob = viewModelScope.launch {
            // Snapshot before closeCurrentMedia(), which intentionally resets state to Idle/0.
            val livePositionBeforeClose = controller.state.value.positionMs.takeIf { it > 0L }
            // Close proxy FD / media first so SmbRandomAccess can release cleanly,
            // then disconnect SMB off the main thread.
            (controller as? VlcPlayerController)?.closeCurrentMedia()
            withContext(Dispatchers.IO) {
                teardownMediaResources()
            }
            subtitleBootstrapDone = false
            subtitleSelectionGate.advance()
            // Re-arm resume seek for the fresh open — never blindly restore
            // request.startPositionMs:
            // - If the gate already fired (or the user pressed play), jump back to
            //   last persisted / live position so mid-playback recovery does not
            //   rewind to the entry resume point.
            // - If the open failed before fire, leave [startPositionMs] as-is so a
            //   history-derived start is not wiped when request.startPositionMs == 0.
            if (resumeSeekGate.hasFired) {
                startPositionMs = retryResumePosition(
                    livePositionMs = livePositionBeforeClose,
                    lastSavedPositionMs = lastSavedPositionMs,
                )
            }
            if (startPositionMs > 0L) {
                resumeSeekGate.reset()
            }
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
        // Skip if already persists to this exact position (races with periodic saves
        // and with onCleared's fallback write).
        if (lastSavedPositionMs == position) return
        // Cancel any prior in-flight leave save so a newer snapshot wins; this is the
        // single coordinated entry point (instead of an unmanaged CoroutineScope).
        leaveSaveJob?.cancel()
        leaveSaveJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
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
            resolveMediaSource(request.dataSource)
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
        viewModelScope.launch {
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
        val selectionGeneration = subtitleSelectionGate.snapshot()
        viewModelScope.launch {
            val smbParams = smbSubtitleParamsOrNull()
            if (smbParams == null) {
                autoSelectEmbeddedOnly(state, selectionGeneration)
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
                    autoSelectEmbeddedOnly(controller.state.value, selectionGeneration)
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
                    share = smbParams.share,
                    remotePath = option.remotePath,
                    fileName = option.fileName,
                ),
            )
        } finally {
            passwordCopy.fill('\u0000')
            smbParams.password.fill('\u0000')
        }

        if (!subtitleSelectionGate.isCurrent(selectionGeneration)) return

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
        listenPrepareGeneration += 1L
        listenPrepareJob?.cancel()
        listenPrepareJob = null
        val listenEngine = realListenEngine
        realListenEngine = null
        stopProgressLoop()
        audioFocus.abandon()
        // Cancel the leave-save so a final snapshot wins and we don't double-write.
        leaveSaveJob?.cancel()
        leaveSaveJob = null

        val player = controller
        val clients = listOfNotNull(listenOnlySmbClient)
        listenOnlySmbClient = null
        val subtitles = subtitleLoader

        // Leave already started an async save; this thread is a bounded backup plus
        // VLC/ASR/SMB teardown that used to freeze the exit transition on the main thread.
        Thread(
            {
                // Skip if the leave-save or a periodic save already persisted this exact
                // position — read volatile lastSavedPositionMs to avoid double-writing
                // (and potentially racing a stale earlier position) on popBack.
                if (shouldSave && lastSavedPositionMs != savePosition) {
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
        error is NullPointerException && detail?.contains("null object reference", ignoreCase = true) == true ->
            "本机翻译组件初始化失败，请更新应用后重试"
        else -> detail?.takeIf { it.isNotBlank() } ?: "听译模型准备失败"
    }
}
