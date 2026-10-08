package com.framenest.feature.update

import android.content.Context
import androidx.annotation.MainThread
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class UpdatePhase { Idle, Checking, NoUpdate, Available, Downloading, Verifying, Ready, Error }

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val manifest: UpdateManifest? = null,
    val asset: UpdateAsset? = null,
    val downloadedBytes: Long = 0,
    val message: String? = null,
    val dialogVisible: Boolean = false,
)

/** One application-scoped controller. No Activity, launcher, or install intent is retained here. */
@MainThread
class UpdateController internal constructor(
    private val backend: UpdateBackend,
    private val store: UpdateCheckStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        AndroidUpdateBackend(context.applicationContext),
        AndroidUpdateCheckStore(context.applicationContext),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val mutableState = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var operation = 0L
    private var readyFile: File? = null
    private var presentationAllowed = false
    private var presentationEpoch = 0L
    private var dismissEpoch = 0L

    /** Must be updated from the host's route and lifecycle, including on dispose. */
    fun setPresentationAllowed(allowed: Boolean) {
        if (presentationAllowed != allowed) presentationEpoch++
        presentationAllowed = allowed
    }

    fun checkAutomatically() {
        if (activeJob?.isActive == true) return
        if (state.value.dialogVisible || state.value.phase in setOf(UpdatePhase.Checking, UpdatePhase.Downloading, UpdatePhase.Verifying)) return
        val time = now()
        if (!UpdatePolicy.autoCheckDue(time, store.lastAttempt)) return
        store.lastAttempt = time // Failures are rate-limited too; manual retry always remains available.
        check(manual = false)
    }

    fun checkManually() {
        if (state.value.phase in setOf(UpdatePhase.Available, UpdatePhase.Downloading, UpdatePhase.Verifying, UpdatePhase.Ready)) {
            mutableState.value = state.value.copy(dialogVisible = true)
            return
        }
        if (state.value.phase == UpdatePhase.Checking) {
            mutableState.value = state.value.copy(dialogVisible = true)
            return
        }
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (activeJob?.isActive == true) return
        val token = ++operation
        val dismissedAtStart = dismissEpoch
        val previousFile = readyFile
        readyFile = null
        mutableState.value = UpdateUiState(phase = UpdatePhase.Checking, dialogVisible = manual)
        activeJob = scope.launch {
            try {
                if (previousFile != null) backend.discard(previousFile)
                val manifest = UpdatePolicy.validate(backend.check())
                if (token != operation) return@launch
                val asset = UpdatePolicy.selectAsset(manifest, backend.supportedAbis)
                mutableState.value = when {
                    !UpdatePolicy.isNewer(manifest.versionCode, backend.installedVersionCode) ->
                        UpdateUiState(UpdatePhase.NoUpdate, message = "当前已是最新版本", dialogVisible = state.value.dialogVisible)
                    manifest.minSdk > backend.sdkInt || asset == null ->
                        UpdateUiState(UpdatePhase.NoUpdate, message = "新版本暂不支持此设备或系统版本", dialogVisible = state.value.dialogVisible)
                    else -> UpdateUiState(
                        UpdatePhase.Available, manifest, asset,
                        dialogVisible = state.value.dialogVisible || (!manual && dismissedAtStart == dismissEpoch && manifest.versionCode != store.dismissedVersion),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == operation) mutableState.value = UpdateUiState(
                    UpdatePhase.Error, message = safeMessage(error), dialogVisible = state.value.dialogVisible,
                )
            }
        }
    }

    fun download() {
        val current = state.value
        if (activeJob?.isActive == true || current.phase !in setOf(UpdatePhase.Available, UpdatePhase.Error)) return
        val manifest = current.manifest ?: return
        val asset = current.asset ?: return
        val token = ++operation
        mutableState.value = current.copy(phase = UpdatePhase.Downloading, downloadedBytes = 0, message = null, dialogVisible = true)
        activeJob = scope.launch {
            var downloaded: File? = null
            try {
                var lastPercent = -1L
                downloaded = backend.download(manifest, asset) { bytes ->
                    val percent = bytes * 100 / asset.size
                    if (percent != lastPercent) {
                        lastPercent = percent
                        scope.launch {
                            if (token == operation && state.value.phase == UpdatePhase.Downloading) {
                                mutableState.value = state.value.copy(downloadedBytes = bytes.coerceIn(0, asset.size))
                            }
                        }
                    }
                }
                if (token != operation) return@launch
                mutableState.value = state.value.copy(phase = UpdatePhase.Verifying, downloadedBytes = asset.size)
                backend.verify(downloaded, manifest, asset)
                coroutineContext.ensureActive()
                if (token != operation) return@launch
                readyFile = downloaded
                mutableState.value = state.value.copy(phase = UpdatePhase.Ready)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (token == operation) mutableState.value = state.value.copy(phase = UpdatePhase.Error, message = safeMessage(error))
            } finally {
                if (downloaded != null && downloaded != readyFile) withContext(NonCancellable) { backend.discard(downloaded) }
            }
        }
    }

    fun retry() {
        if (state.value.manifest != null && state.value.asset != null) download() else checkManually()
    }

    fun cancelDownload() {
        if (state.value.phase !in setOf(UpdatePhase.Downloading, UpdatePhase.Verifying)) return
        operation++
        activeJob?.cancel()
        activeJob = null
        val file = readyFile
        readyFile = null
        if (file != null) scope.launch { backend.discard(file) }
        mutableState.value = state.value.copy(phase = UpdatePhase.Available, downloadedBytes = 0, message = null, dialogVisible = false)
    }

    fun dismiss() {
        dismissEpoch++
        if (state.value.phase in setOf(UpdatePhase.Downloading, UpdatePhase.Verifying)) {
            cancelDownload()
            return
        }
        state.value.manifest?.let { store.dismissedVersion = it.versionCode }
        mutableState.value = state.value.copy(dialogVisible = false)
    }

    /** Called only after an explicit install confirmation, in the current UI's cancellable scope. */
    suspend fun verifiedInstallFile(): File? {
        val current = state.value
        if (!presentationAllowed || !current.dialogVisible || current.phase != UpdatePhase.Ready) return null
        val file = readyFile ?: return null
        val manifest = current.manifest ?: return null
        val asset = current.asset ?: return null
        val token = operation
        val epoch = presentationEpoch
        mutableState.value = current.copy(phase = UpdatePhase.Verifying)
        try {
            backend.verify(file, manifest, asset)
            coroutineContext.ensureActive()
            return if (token == operation && epoch == presentationEpoch && presentationAllowed && state.value.dialogVisible) file else null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (token == operation) {
                readyFile = null
                mutableState.value = state.value.copy(phase = UpdatePhase.Error, message = safeMessage(error))
                withContext(NonCancellable) { backend.discard(file) }
            }
            return null
        } finally {
            if (token == operation && state.value.phase == UpdatePhase.Verifying) {
                mutableState.value = state.value.copy(phase = UpdatePhase.Ready)
            }
        }
    }

    fun installerOpened() {
        mutableState.value = state.value.copy(dialogVisible = false)
    }

    fun installerUnavailable() {
        mutableState.value = state.value.copy(message = "无法打开系统安装器或来源设置，请稍后重试")
    }

    private fun safeMessage(error: Exception): String =
        if (error is UpdateProblem) error.message ?: "更新失败，请重试"
        else "更新失败，请检查网络后重试"
}
