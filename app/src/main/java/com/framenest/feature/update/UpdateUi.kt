package com.framenest.feature.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Place once in the root app shell, outside navigation destinations. */
@Composable
fun UpdateHost(controller: UpdateController, isPlaybackRoute: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPlaybackRoute by rememberUpdatedState(isPlaybackRoute)
    var foreground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var installConfirmation by remember { mutableStateOf(false) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    val canPresent = foreground && !isPlaybackRoute

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            controller.setPresentationAllowed(foreground && !currentPlaybackRoute)
            if (foreground) controller.checkAutomatically()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.setPresentationAllowed(false)
        }
    }
    SideEffect { controller.setPresentationAllowed(canPresent) }
    LaunchedEffect(canPresent) {
        if (!canPresent) {
            installConfirmation = false
            installJob?.cancel()
        }
    }

    if (canPresent && state.dialogVisible) {
        if (installConfirmation) {
            AlertDialog(
                onDismissRequest = { installConfirmation = false },
                title = { Text("安装更新？") },
                text = { Text("将打开 Android 系统安装器，由你确认覆盖安装 ${state.manifest?.versionName.orEmpty()}。不会自动卸载当前应用。") },
                confirmButton = {
                    TextButton(onClick = {
                        installConfirmation = false
                        installJob = scope.launch {
                            val file = controller.verifiedInstallFile() ?: return@launch
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    clipData = ClipData.newRawUri("FrameNest update", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                                controller.installerOpened()
                            } catch (_: ActivityNotFoundException) {
                                controller.installerUnavailable()
                            } catch (_: SecurityException) {
                                controller.installerUnavailable()
                            } catch (_: IllegalArgumentException) {
                                controller.installerUnavailable()
                            }
                        }
                    }, modifier = Modifier.testTag("update_confirm_install")) { Text("打开安装器") }
                },
                dismissButton = { TextButton(onClick = { installConfirmation = false }) { Text("取消") } },
            )
        } else {
            UpdateDialog(
                state = state,
                onDismiss = controller::dismiss,
                onDownload = controller::download,
                onRetry = controller::retry,
                onCancel = controller::cancelDownload,
                onInstall = {
                    if (canRequestInstall(context)) {
                        installConfirmation = true
                    } else {
                        try {
                            // This button requests source permission only. Returning never starts installation.
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } catch (_: ActivityNotFoundException) {
                            controller.installerUnavailable()
                        } catch (_: SecurityException) {
                            controller.installerUnavailable()
                        }
                    }
                },
                canInstallPackages = state.phase == UpdatePhase.Ready && canRequestInstall(context),
            )
        }
    }
}

/** Settings keeps an explicit entry even when an automatic check is deferred or dismissed. */
@Composable
fun UpdateSettingsEntry(controller: UpdateController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    Column(modifier = modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = controller::checkManually, modifier = Modifier.testTag("settings_check_update")) {
            Text(if (state.phase == UpdatePhase.Checking) "正在检查更新…" else "检查更新")
        }
        Text("每天自动检查一次；下载和安装都需你确认，播放时不弹出更新。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    canInstallPackages: Boolean,
) {
    AlertDialog(
        modifier = Modifier.testTag("update_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(state.manifest?.let { "发现新版本 ${it.versionName}" } ?: "应用更新") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.manifest?.let {
                    Text(it.notes.ifBlank { "修复与体验改进" }, modifier = Modifier.testTag("update_notes"))
                }
                state.asset?.let { Text("安装包：${formatMegabytes(it.size)} MB · ${it.abi}", style = MaterialTheme.typography.bodySmall) }
                when (state.phase) {
                    UpdatePhase.Checking -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查更新…") }
                    UpdatePhase.Downloading -> {
                        val total = state.asset?.size ?: 1L
                        LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("已下载 ${formatMegabytes(state.downloadedBytes)} / ${formatMegabytes(total)} MB", modifier = Modifier.testTag("update_progress"))
                    }
                    UpdatePhase.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在校验安装包与签名…") }
                    UpdatePhase.Ready -> Text(if (canInstallPackages) "安装包已通过校验，点击安装后仍需系统确认。" else "安装前需在 Android 设置中允许此应用安装更新。返回后请再次点击安装。")
                    UpdatePhase.Available -> Text("下载可能使用移动数据；可随时取消。")
                    else -> Unit
                }
                state.message?.let { Text(it, modifier = Modifier.testTag("update_message")) }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.Available -> TextButton(onClick = onDownload, modifier = Modifier.testTag("update_download")) { Text("下载更新") }
                UpdatePhase.Ready -> TextButton(onClick = onInstall, modifier = Modifier.testTag("update_install")) { Text(if (canInstallPackages) "安装更新" else "允许安装来源") }
                UpdatePhase.Error -> TextButton(onClick = onRetry, modifier = Modifier.testTag("update_retry")) { Text("重试") }
                UpdatePhase.Downloading, UpdatePhase.Verifying -> TextButton(onClick = onCancel) { Text("取消下载") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (state.phase in setOf(UpdatePhase.Available, UpdatePhase.Ready, UpdatePhase.Error)) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

private fun formatMegabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

private fun canRequestInstall(context: Context): Boolean = try {
    context.packageManager.canRequestPackageInstalls()
} catch (_: SecurityException) {
    false
}
