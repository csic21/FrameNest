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
import com.framenest.data.history.AppDatabase
import com.framenest.data.history.PlaybackHistoryRepository
import com.framenest.data.history.PlaybackProgressRules
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Product player session: open media (path B preferred), first-frame paused,
 * progress save, retry, and release coordination.
 */
class PlayerViewModel(
    application: Application,
    private val request: PlaybackRequest,
    private val historyRepository: PlaybackHistoryRepository,
    private val controllerFactory: (Application) -> PlayerController = { app ->
        VlcPlayerController(app, enableHwDecoder = true)
    },
) : AndroidViewModel(application) {

    val controller: PlayerController = controllerFactory(application)

    val playerState: StateFlow<PlayerState> = controller.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = controller.state.value,
    )

    private val audioFocus = PlayerAudioFocus(application) {
        pauseFromSystem()
    }

    private var smbClient: SmbjClient? = null
    private var progressJob: Job? = null
    private var lastSavedPositionMs: Long? = null
    private var startPositionMs: Long = request.startPositionMs

    init {
        viewModelScope.launch {
            loadResumeAndOpen()
        }
        viewModelScope.launch {
            controller.state.collect { state ->
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
            }
        }
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

    fun retry() {
        viewModelScope.launch {
            teardownMediaResources()
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
            injectError(err)
            return
        } catch (t: Throwable) {
            val err = PlayerErrorMapper.fromThrowable(t)
            Log.w(TAG, "Open failed: ${err.message}")
            injectError(err)
            return
        }
        controller.prepare(mediaSource)
    }

    private fun injectError(error: PlayerError) {
        (controller as? VlcPlayerController)?.reportExternalError(error)
            ?: controller.prepare(MediaSource.LocalFile("/__framenest_missing__"))
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
        stopProgressLoop()
        audioFocus.abandon()
        controller.release()
        teardownSmbClientOnly()
        super.onCleared()
    }

    val displayName: String get() = request.displayName
    val identity get() = request.identity

    class Factory(
        private val application: Application,
        private val request: PlaybackRequest,
        private val historyRepository: PlaybackHistoryRepository = resolveHistory(application),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                return PlayerViewModel(application, request, historyRepository) as T
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
    }
}
