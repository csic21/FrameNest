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
import com.framenest.feature.listen_translate.ListenTranslateSession
import com.framenest.feature.listen_translate.ListenTranslateUiState
import com.framenest.feature.listen_translate.StubListenTranslateEngine
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
import com.framenest.player.VlcPlayerController
import com.framenest.smb.SmbException
import com.framenest.smb.SmbjClient
import com.framenest.smb.SmbCredentials as SmbSessionCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * Product player session: open media (path B preferred), first-frame paused,
 * progress save, retry, release coordination, subtitle selection (FN-06),
 * and listen-translate overlay (FN-12).
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

    private val listenSession = ListenTranslateSession(
        repository = listenTranslateRepository,
        engine = StubListenTranslateEngine(),
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
                    state.phase == PlayerState.Phase.Ready
                ) {
                    val target = startPositionMs
                    startPositionMs = 0L
                    controller.seekTo(target)
                }
                if (state.firstFrameReady && !subtitleBootstrapDone) {
                    subtitleBootstrapDone = true
                    bootstrapSubtitles(state)
                }
            }
        }
    }

    fun setListenTranslateEnabled(enabled: Boolean) {
        listenSession.setEnabled(enabled)
    }

    fun setListenSourceLang(code: String) {
        listenSession.setSourceLang(code)
    }

    fun setListenTargetLang(code: String) {
        listenSession.setTargetLang(code)
    }

    fun setListenDisplayMode(mode: ListenDisplayMode) {
        listenSession.setDisplayMode(mode)
    }

    fun play() {
        if (!audioFocus.request()) {
            Log.w(TAG, "Audio focus not granted; playing anyway")
        }
        controller.play()
    }

    fun pause() {
        controller.pause()
        viewModelScope.launch { saveProgressNow(force = true) }
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
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
            teardownMediaResources()
            subtitleBootstrapDone = false
            _subtitleUiState.value = SubtitleUiState(
                delayMs = _subtitleUiState.value.delayMs,
                fontRelSize = _subtitleUiState.value.fontRelSize,
            )
            loadResumeAndOpen(forceReloadHistory = false)
        }
    }

    /** Call when UI leaves or process goes to background pause policy. */
    fun onLeaveOrBackground() {
        pauseFromSystem()
        viewModelScope.launch { saveProgressNow(force = true) }
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

    private suspend fun resolveMediaSource(dataSource: PlaybackDataSource): MediaSource =
        when (dataSource) {
            is PlaybackDataSource.LocalRawResource ->
                MediaSource.RawResource(dataSource.resId)
            is PlaybackDataSource.LocalFile ->
                MediaSource.LocalFile(dataSource.path)
            is PlaybackDataSource.SeekableSmb -> withContext(Dispatchers.IO) {
                openSeekableSmb(dataSource)
            }
            is PlaybackDataSource.DirectSmbUrl -> {
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
                MediaSource.Smb(uri = uri, credentials = creds)
            }
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
            val opened = SmbSeekableMedia.open(
                context = getApplication(),
                randomAccess = randomAccess,
                debugLabel = "smb://${dataSource.share}/${dataSource.path.trimStart('/')}",
            )
            return opened.mediaSource
        } catch (t: Throwable) {
            teardownSmbClientOnly()
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
    }

    override fun onCleared() {
        runCatching {
            val state = controller.state.value
            if (state.phase != PlayerState.Phase.Idle &&
                state.phase != PlayerState.Phase.Error &&
                state.phase != PlayerState.Phase.Preparing
            ) {
                runBlocking {
                    saveProgressNow(force = true)
                }
            }
        }
        listenSession.release()
        stopProgressLoop()
        audioFocus.abandon()
        controller.release()
        teardownSmbClientOnly()
        runCatching { subtitleLoader.clearCache() }
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
